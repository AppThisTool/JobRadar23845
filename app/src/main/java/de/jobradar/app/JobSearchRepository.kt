package de.jobradar.app

import android.net.Uri
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import kotlin.math.*

data class JobOffer(
    val id:String,
    val title:String,
    val company:String,
    val location:String,
    val source:String,
    val url:String,
    val description:String="",
    val hints:List<String> = emptyList(),
    val distanceKm:Double? = null
)
data class SearchResult(val jobs:List<JobOffer>,val sourceMessages:List<String>)

object JobSearchRepository {
 private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS).followRedirects(true).build()
 private const val HOME_LAT=53.8086
 private const val HOME_LON=10.1592

 private val queries=listOf("Friseur","Herrenfriseur","Barber","Quereinsteiger Teilzeit","Empfang Teilzeit","Rezeption Teilzeit","Kundenservice Teilzeit","Verkauf Teilzeit","Bürohilfe Teilzeit")
 private val hair=listOf("friseur","friseurin","herrenfriseur","barber","hairstylist","hair stylist")
 private val simpleTitles=listOf("empfang","rezeption","kundenservice","kundenberater","verkauf","verkäufer","kasse","kassierer","bürohilfe","bürokraft","aushilfe","servicekraft","mitarbeiter kundenservice","mitarbeiter empfang","mitarbeiter verkauf")
 private val reject=listOf("projektmanager","projektleiter","project manager","business analyst","analyst","software","entwickler","developer","ingenieur","engineer","architekt","teamleiter","filialleiter","schichtleiter","gebietsverkaufsleiter","verkaufsleiter","leiter","leitung","manager","management","meister","friseurmeister","lager","kommissionier","zusteller","paketzustell","produktion","montage","handwerker","pflegefach","pflegekraft","reinigungskraft","security","nachtschicht")
 private val navNoise=listOf("berufsfeld","handwerk","gesundheit","bildung","it ","vertrieb und verkauf","stellenangebote","jobs in","suchergebnisse")
 private val geoCache=mutableMapOf<String,Pair<Double,Double>?>()

 suspend fun search(postalCode:String,radiusKm:Int):SearchResult=withContext(Dispatchers.IO){coroutineScope{
   val tasks=mutableListOf<Deferred<Pair<List<JobOffer>,String>>>()
   tasks+=async{searchBA(postalCode,radiusKm)}
   tasks+=async{searchArbeitnow(postalCode)}
   tasks+=async{searchMeinestadt(radiusKm)}
   tasks+=async{searchJooble(radiusKm)}
   tasks+=async{searchStepstone(radiusKm)}
   tasks+=async{searchKleinanzeigen(radiusKm)}
   tasks+=async{searchKimeta(radiusKm)}
   val parts=tasks.awaitAll()
   val pre=parts.flatMap{it.first}.filter{isSuitable(it)}.distinctBy{key(it)}
   val checked=pre.mapNotNull{job->
       val coords=geocode(job.location) ?: return@mapNotNull null
       val dist=distance(HOME_LAT,HOME_LON,coords.first,coords.second)
       if(dist>radiusKm+0.8) null else job.copy(distanceKm=dist)
   }
   val jobs=checked.sortedWith(compareBy<JobOffer>{rank(it)}.thenBy{it.distanceKm?:999.0}.thenBy{it.title.lowercase()})
   SearchResult(jobs,parts.map{it.second})
 }}

 private fun key(j:JobOffer)="${clean(j.title)}|${clean(j.company)}|${clean(j.location)}"
 private fun clean(s:String)=s.lowercase().replace(Regex("[^a-z0-9äöüß]"),"")
 private fun isSuitable(j:JobOffer):Boolean{
   val title=j.title.lowercase().trim();val text=(j.title+" "+j.description).lowercase()
   if(title.length !in 5..160) return false
   if(navNoise.any{title==it || title.startsWith("$it ")}) return false
   if(reject.any{it in title}) return false
   if(hair.any{it in title}) return true
   if(simpleTitles.none{it in title}) return false
   if(reject.any{it in text}) return false
   return true
 }
 private fun rank(j:JobOffer):Int{val t=j.title.lowercase();return when{hair.any{it in t}->0;else->1}}
 private fun hints(text:String):List<String>{val t=text.lowercase();val h=mutableListOf<String>();if("teilzeit" in t||Regex("(?:2[5-9]|3[0-5])\\s*(?:std|stunden)").containsMatchIn(t))h+="Teilzeit / ca. 30 Std.";if("quereinsteiger" in t)h+="Quereinstieg möglich";if("montag bis freitag" in t||"mo-fr" in t||"kein wochenende" in t)h+="Werktags";return h}

 private fun get(url:String):String?=try{val r=client.newCall(Request.Builder().url(url).header("User-Agent","JobRadar23845/2.3 (Android)").header("Accept-Language","de-DE,de;q=0.9").build()).execute();r.use{if(it.isSuccessful)it.body?.string() else null}}catch(_:Exception){null}

 private fun searchBA(pc:String,r:Int):Pair<List<JobOffer>,String>{val out=mutableListOf<JobOffer>();queries.forEach{q->try{val u="https://rest.arbeitsagentur.de/jobboerse/jobsuche-service/pc/v6/jobs?angebotsart=1&was=${Uri.encode(q)}&wo=$pc&umkreis=$r&page=1&size=100";val req=Request.Builder().url(u).header("X-API-Key","jobboerse-jobsuche").build();client.newCall(req).execute().use{res->if(!res.isSuccessful)return@use;val a=JSONObject(res.body?.string().orEmpty()).optJSONArray("stellenangebote")?:return@use;for(i in 0 until a.length()){val x=a.optJSONObject(i)?:continue;val p=x.optJSONObject("arbeitsort");val street=p?.optString("strasse").orEmpty();val zip=p?.optString("plz").orEmpty();val city=p?.optString("ort").orEmpty();val loc=listOf(street,zip,city).filter{it.isNotBlank()}.joinToString(", ");val title=x.optString("titel").trim();val desc=x.optString("beruf");val link=x.optString("externeUrl","https://www.arbeitsagentur.de/jobsuche/suche?angebotsart=1&was=${Uri.encode(title)}&wo=${Uri.encode(city)}");if(title.isNotBlank()&&loc.isNotBlank())out+=JobOffer(x.optString("refnr","ba-$i-${title.hashCode()}"),title,x.optString("arbeitgeber","Arbeitgeber"),loc,"BA",link,desc,hints("$title $desc"))}}}catch(_:Exception){}};return out to "BA:${out.size}"}

 private fun searchArbeitnow(pc:String):Pair<List<JobOffer>,String>{return try{val s=get("https://www.arbeitnow.com/api/job-board-api")?:return emptyList<JobOffer>() to "AN:0";val a=JSONObject(s).optJSONArray("data")?:return emptyList<JobOffer>() to "AN:0";val out=mutableListOf<JobOffer>();for(i in 0 until a.length()){val x=a.optJSONObject(i)?:continue;if(x.optBoolean("remote",false))continue;val title=x.optString("title").trim();val desc=x.optString("description");val loc=x.optString("location").trim();if(title.isBlank()||loc.isBlank())continue;out+=JobOffer(x.optString("slug","an-$i"),title,x.optString("company_name","Arbeitgeber"),loc,"AN",x.optString("url"),desc,hints("$title $desc"))};out to "AN:${out.size}"}catch(_:Exception){emptyList<JobOffer>() to "AN:0"}}

 private fun htmlSearch(source:String,url:String,base:String,titleSelectors:List<String>):Pair<List<JobOffer>,String>{val body=get(url)?:return emptyList<JobOffer>() to "$source:0";return try{val doc=Jsoup.parse(body,base);val out=mutableListOf<JobOffer>();val seen=mutableSetOf<String>();for(sel in titleSelectors){for(e in doc.select(sel)){val a=if(e.tagName()=="a")e else e.selectFirst("a[href]")?:e.closest("a[href]")?:continue;val title=e.text().trim().ifBlank{a.text().trim()};if(title.length !in 5..160)continue;val href=a.absUrl("href").ifBlank{a.attr("href")};if(href.isBlank())continue;val box=e.closest("article")?:e.closest("li")?:e.parent();val text=box?.text().orEmpty().trim();val loc=locationFrom(text);if(loc==null)continue;val company=companyFrom(text,title);if(company==null)continue;val k=clean(title)+href;if(!seen.add(k))continue;out+=JobOffer("$source-${k.hashCode()}",title,company,loc,source,href,text,hints(text))}};out to "$source:${out.size}"}catch(_:Exception){emptyList<JobOffer>() to "$source:0"}}

 private fun companyFrom(text:String,title:String):String?{val candidates=text.replace(title,"").split(" · "," | ","\n"," • ").map{it.trim()}.filter{it.length in 2..80};return candidates.firstOrNull{!navNoise.any{n->it.lowercase().contains(n)}&&!Regex("^\\d+\\s*(stellen|jobs)?$",RegexOption.IGNORE_CASE).matches(it)}}
 private fun locationFrom(text:String):String?{
   val zip=Regex("\\b2[0-9]{4}\\b\\s+[A-ZÄÖÜ][A-Za-zÄÖÜäöüß .-]{2,40}").find(text)?.value?.trim();if(zip!=null)return zip
   val districts=Regex("\\b(Hamburg[- ](?:Altona|Bergedorf|Eimsbüttel|Harburg|Mitte|Nord|Wandsbek)|Norderstedt(?:[- ][A-Za-zÄÖÜäöüß]+)?|Kaltenkirchen|Henstedt-Ulzburg|Bad Segeberg|Quickborn|Bargteheide|Ahrensburg|Itzstedt|Seth|Nahe|Kayhude|Bargfeld-Stegen|Oering|Tangstedt|Kisdorf|Ellerau|Wakendorf II|Wakendorf I|Leezen|Bornhöved)\\b",RegexOption.IGNORE_CASE).find(text)?.value
   return districts
 }

 private fun geocode(location:String):Pair<Double,Double>?{
   val key=location.lowercase().trim();if(geoCache.containsKey(key))return geoCache[key]
   val q=Uri.encode("$location, Schleswig-Holstein, Deutschland")
   val body=get("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&countrycodes=de&q=$q")?:return null.also{geoCache[key]=null}
   return try{val arr=JSONArray(body);if(arr.length()==0)null else{val o=arr.getJSONObject(0);o.getDouble("lat") to o.getDouble("lon")}}catch(_:Exception){null}.also{geoCache[key]=it}
 }
 private fun distance(a:Double,b:Double,c:Double,d:Double):Double{val r=6371.0;val dLat=Math.toRadians(c-a);val dLon=Math.toRadians(d-b);val x=sin(dLat/2).pow(2)+cos(Math.toRadians(a))*cos(Math.toRadians(c))*sin(dLon/2).pow(2);return 2*r*asin(sqrt(x))}

 private fun searchMeinestadt(r:Int)=htmlSearch("MS","https://jobs.meinestadt.de/itzstedt/suche?words=${Uri.encode("Friseur Quereinsteiger Teilzeit")}&radius=$r","https://jobs.meinestadt.de",listOf("article a[href*='/job/']","a[data-testid*=job]","a[href*='/stellenangebot']","h2 a[href]","h3 a[href]"))
 private fun searchJooble(r:Int)=htmlSearch("JB","https://de.jooble.org/SearchResult?ukw=${Uri.encode("Friseur Quereinsteiger Teilzeit")}&rgns=Itzstedt&radius=$r","https://de.jooble.org",listOf("article a[href]","a[href*='/jdp/']","h2 a[href]","h3 a[href]"))
 private fun searchStepstone(r:Int)=htmlSearch("SS","https://www.stepstone.de/jobs/friseur/in-itzstedt?radius=$r","https://www.stepstone.de",listOf("article a[href]","a[data-at='job-item-title']","h2 a[href]"))
 private fun searchKleinanzeigen(r:Int)=htmlSearch("KA","https://www.kleinanzeigen.de/s-jobs/itzstedt/friseur/k0c102r$r","https://www.kleinanzeigen.de",listOf("article a[href*='/s-anzeige/']","a.ellipsis[href]","h2 a[href]"))
 private fun searchKimeta(r:Int)=htmlSearch("KM","https://www.kimeta.de/stellenangebote-friseur-in-itzstedt?radius=$r","https://www.kimeta.de",listOf("article a[href]","a[href*='stellenangebot']","h2 a[href]","h3 a[href]"))
}
