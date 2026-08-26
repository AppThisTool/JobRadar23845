package de.jobradar.app

import android.net.Uri
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

data class JobOffer(val id:String,val title:String,val company:String,val location:String,val source:String,val url:String,val description:String="",val hints:List<String> = emptyList())
data class SearchResult(val jobs:List<JobOffer>,val sourceMessages:List<String>)

object JobSearchRepository {
 private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS).followRedirects(true).build()
 private val queries=listOf("Friseur","Herrenfriseur","Barber","Quereinsteiger Teilzeit","Empfang Teilzeit","Rezeption Teilzeit","Kundenservice Teilzeit","Verkauf Teilzeit","Bürohilfe Teilzeit")
 private val hair=listOf("friseur","friseurin","herrenfriseur","barber","hairstylist","hair stylist")
 private val simple=listOf("quereinsteiger","empfang","rezeption","kundenservice","kundenberater","service","verkauf","verkäufer","kasse","kassierer","bürohilfe","bürokraft","mitarbeiter","aushilfe")
 private val reject=listOf("projektmanager","projektleiter","project manager","software","entwickler","developer","ingenieur","engineer","architekt","teamleiter","filialleiter","manager","management","lager","kommissionier","zusteller","paketzustell","produktion","montage","handwerker","pflegefach","pflegekraft","reinigungskraft","security","nachtschicht")

 suspend fun search(postalCode:String,radiusKm:Int):SearchResult=withContext(Dispatchers.IO){coroutineScope{
   val tasks=mutableListOf<Deferred<Pair<List<JobOffer>,String>>>()
   tasks+=async{searchBA(postalCode,radiusKm)}
   tasks+=async{searchArbeitnow(postalCode)}
   tasks+=async{searchMeinestadt(radiusKm)}
   tasks+=async{searchJooble(radiusKm)}
   tasks+=async{searchStepstone(radiusKm)}
   tasks+=async{searchKleinanzeigen(radiusKm)}
   tasks+=async{searchKimeta(radiusKm)}
   val parts=tasks.awaitAll();val jobs=parts.flatMap{it.first}.filter{isSuitable(it)}.distinctBy{key(it)}.sortedWith(compareBy<JobOffer>{rank(it)}.thenBy{it.title.lowercase()})
   SearchResult(jobs,parts.map{it.second})
 }}
 private fun key(j:JobOffer)="${clean(j.title)}|${clean(j.company)}|${clean(j.location)}"
 private fun clean(s:String)=s.lowercase().replace(Regex("[^a-z0-9äöüß]"),"")
 private fun isSuitable(j:JobOffer):Boolean{val title=j.title.lowercase();val text=(j.title+" "+j.description).lowercase();if(reject.any{it in title})return false;if(hair.any{it in text})return true;return simple.any{it in text}&&reject.none{it in text}}
 private fun rank(j:JobOffer):Int{val t=(j.title+" "+j.description).lowercase();return when{hair.any{it in t}->0;"quereinsteiger" in t->1;else->2}}
 private fun hints(text:String):List<String>{val t=text.lowercase();val h=mutableListOf<String>();if("teilzeit" in t||Regex("2[5-9]|3[0-5]").containsMatchIn(t)&&"stund" in t)h+="Teilzeit/ca. 30 Std. möglich";if("quereinsteiger" in t)h+="Quereinstieg";if("montag bis freitag" in t||"mo-fr" in t||"kein wochenende" in t)h+="Hinweis: werktags";return h}

 private fun get(url:String):String?=try{val r=client.newCall(Request.Builder().url(url).header("User-Agent","Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36").header("Accept-Language","de-DE,de;q=0.9").build()).execute();r.use{if(it.isSuccessful)it.body?.string() else null}}catch(_:Exception){null}

 private fun searchBA(pc:String,r:Int):Pair<List<JobOffer>,String>{val out=mutableListOf<JobOffer>();var ok=0;queries.forEach{q->try{val u="https://rest.arbeitsagentur.de/jobboerse/jobsuche-service/pc/v6/jobs?angebotsart=1&was=${Uri.encode(q)}&wo=$pc&umkreis=$r&page=1&size=100";val req=Request.Builder().url(u).header("X-API-Key","jobboerse-jobsuche").build();client.newCall(req).execute().use{res->if(!res.isSuccessful)return@use;val a=JSONObject(res.body?.string().orEmpty()).optJSONArray("stellenangebote")?:return@use;ok++;for(i in 0 until a.length()){val x=a.optJSONObject(i)?:continue;val p=x.optJSONObject("arbeitsort");val loc=listOf(p?.optString("plz").orEmpty(),p?.optString("ort").orEmpty()).filter{it.isNotBlank()}.joinToString(" ");val title=x.optString("titel");val desc=x.optString("beruf");val link=x.optString("externeUrl","https://www.arbeitsagentur.de/jobsuche/suche?angebotsart=1&was=${Uri.encode(title)}&wo=${Uri.encode(loc)}");out+=JobOffer(x.optString("refnr","ba-$i-${title.hashCode()}"),title,x.optString("arbeitgeber","Arbeitgeber"),loc,"BA",link,desc,hints("$title $desc"))}}}catch(_:Exception){}};return out to "BA:$ok"}

 private fun searchArbeitnow(pc:String):Pair<List<JobOffer>,String>{return try{val s=get("https://www.arbeitnow.com/api/job-board-api")?:return emptyList<JobOffer>() to "AN:0";val a=JSONObject(s).optJSONArray("data")?:return emptyList<JobOffer>() to "AN:0";val out=mutableListOf<JobOffer>();for(i in 0 until a.length()){val x=a.optJSONObject(i)?:continue;if(x.optBoolean("remote",false))continue;val title=x.optString("title");val desc=x.optString("description");val loc=x.optString("location");if(!"$title $desc $loc".contains(pc,true))continue;out+=JobOffer(x.optString("slug","an-$i"),title,x.optString("company_name","Arbeitgeber"),loc,"AN",x.optString("url"),desc,hints("$title $desc"))};out to "AN:${out.size}"}catch(_:Exception){emptyList<JobOffer>() to "AN:0"}}

 private fun htmlSearch(source:String,url:String,base:String,titleSelectors:List<String>):Pair<List<JobOffer>,String>{val body=get(url)?:return emptyList<JobOffer>() to "$source:0";return try{val doc=Jsoup.parse(body,base);val out=mutableListOf<JobOffer>();val seen=mutableSetOf<String>();for(sel in titleSelectors){for(e in doc.select(sel)){val a=if(e.tagName()=="a")e else e.selectFirst("a[href]")?:e.closest("a[href]")?:continue;val title=e.text().trim().ifBlank{a.text().trim()};if(title.length !in 4..180)continue;val href=a.absUrl("href").ifBlank{a.attr("href")};if(href.isBlank())continue;val box=e.closest("article")?:e.closest("li")?:e.parent();val text=box?.text().orEmpty();val k=clean(title)+href;if(!seen.add(k))continue;out+=JobOffer("$source-${k.hashCode()}",title,companyFrom(text,title),locationFrom(text),source,href,text,hints(text))}};out to "$source:${out.size}"}catch(_:Exception){emptyList<JobOffer>() to "$source:0"}}
 private fun companyFrom(text:String,title:String):String{val rest=text.replace(title,"").trim();return rest.split(" · "," | ","\n").firstOrNull{it.length in 2..80}?:"Arbeitgeber"}
 private fun locationFrom(text:String):String{val m=Regex("\\b(2[0-9]{4})\\b[^·|\\n]{0,45}").find(text);return m?.value?.trim()?:Regex("(Itzstedt|Norderstedt|Kaltenkirchen|Henstedt-Ulzburg|Bad Segeberg|Hamburg|Quickborn|Bargteheide|Ahrensburg)",RegexOption.IGNORE_CASE).find(text)?.value?:"Region 23845"}

 private fun searchMeinestadt(r:Int)=htmlSearch("MS","https://jobs.meinestadt.de/itzstedt/suche?words=${Uri.encode("Friseur Quereinsteiger Teilzeit")}&radius=$r","https://jobs.meinestadt.de",listOf("article a[href*='/job/']","a[data-testid*=job]","a[href*='/stellenangebot']","h2 a[href]","h3 a[href]"))
 private fun searchJooble(r:Int)=htmlSearch("JB","https://de.jooble.org/SearchResult?ukw=${Uri.encode("Friseur Quereinsteiger Teilzeit")}&rgns=Itzstedt&radius=$r","https://de.jooble.org",listOf("article a[href]","a[href*='/jdp/']","h2 a[href]","h3 a[href]"))
 private fun searchStepstone(r:Int)=htmlSearch("SS","https://www.stepstone.de/jobs/friseur/in-itzstedt?radius=$r","https://www.stepstone.de",listOf("article a[href]","a[data-at='job-item-title']","h2 a[href]"))
 private fun searchKleinanzeigen(r:Int)=htmlSearch("KA","https://www.kleinanzeigen.de/s-jobs/itzstedt/friseur/k0c102r$r","https://www.kleinanzeigen.de",listOf("article a[href*='/s-anzeige/']","a.ellipsis[href]","h2 a[href]"))
 private fun searchKimeta(r:Int)=htmlSearch("KM","https://www.kimeta.de/stellenangebote-friseur-in-itzstedt?radius=$r","https://www.kimeta.de",listOf("article a[href]","a[href*='stellenangebot']","h2 a[href]","h3 a[href]"))
}
