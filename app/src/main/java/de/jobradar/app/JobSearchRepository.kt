package de.jobradar.app

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class JobOffer(val id:String,val title:String,val company:String,val location:String,val source:String,val url:String,val description:String="")
data class SearchResult(val jobs:List<JobOffer>,val sourceMessages:List<String>)

object JobSearchRepository {
    private val client=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).build()

    // Berufsprofil: gelernte Herrenfriseurin + realistische Quereinsteiger-Tätigkeiten.
    private val queries=listOf("Friseur","Herrenfriseur","Barber","Quereinsteiger Teilzeit","Empfang Teilzeit","Rezeption Teilzeit","Kundenservice Teilzeit","Verkauf Teilzeit","Bürohilfe Teilzeit")
    private val hairWords=listOf("friseur","friseurin","hair stylist","hairstylist","barber","hairstylistin")
    private val careerWords=listOf("quereinsteiger","empfang","rezeption","kundenservice","kundenberater","servicekraft","verkauf","verkäufer","kasse","kassierer","bürohilfe","bürokraft","mitarbeiter")
    private val rejectWords=listOf("projektmanager","projektleiter","project manager","software","entwickler","developer","ingenieur","engineer","architekt","leitung","teamleiter","filialleiter","manager","management","lager","kommissionier","zusteller","paketzustell","produktion","montage","handwerker","pflegefach","pflegekraft","reinigungskraft","security","nachtschicht")

    suspend fun search(postalCode:String,radiusKm:Int):SearchResult=withContext(Dispatchers.IO){coroutineScope{
        val parts=listOf(async{searchBundesagentur(postalCode,radiusKm)},async{searchArbeitnow(postalCode)}).awaitAll()
        val jobs=parts.flatMap{it.first}.filter{isSuitable(it)}.distinctBy{normalizeKey(it)}.sortedWith(compareBy<JobOffer>{rank(it)}.thenBy{it.title.lowercase()})
        SearchResult(jobs,parts.map{it.second})
    }}

    private fun normalizeKey(j:JobOffer)="${clean(j.title)}|${clean(j.company)}|${j.location.lowercase()}"
    private fun clean(s:String)=s.lowercase().replace(Regex("[^a-z0-9äöüß]"),"")
    private fun isSuitable(j:JobOffer):Boolean{
        val title=j.title.lowercase(); val text=(j.title+" "+j.description).lowercase()
        if(rejectWords.any{it in title}) return false
        if(hairWords.any{it in text}) return true
        return careerWords.any{it in title || it in text} && rejectWords.none{it in text}
    }
    private fun rank(j:JobOffer):Int{val t=(j.title+" "+j.description).lowercase();return when{hairWords.any{it in t}->0;"quereinsteiger" in t->1;else->2}}

    private fun searchBundesagentur(postalCode:String,radiusKm:Int):Pair<List<JobOffer>,String>{
        val jobs=mutableListOf<JobOffer>();var ok=0
        queries.forEach{q->try{
            val url="https://rest.arbeitsagentur.de/jobboerse/jobsuche-service/pc/v6/jobs?angebotsart=1&was=${Uri.encode(q)}&wo=${Uri.encode(postalCode)}&umkreis=$radiusKm&page=1&size=100"
            val req=Request.Builder().url(url).header("X-API-Key","jobboerse-jobsuche").header("User-Agent","JobRadar23845/2.2").build()
            client.newCall(req).execute().use{r->if(!r.isSuccessful)return@use;val root=JSONObject(r.body?.string().orEmpty());val a=root.optJSONArray("stellenangebote")?:root.optJSONArray("jobs")?:return@use;ok++
                for(i in 0 until a.length()){val x=a.optJSONObject(i)?:continue;val p=x.optJSONObject("arbeitsort");val city=p?.optString("ort").orEmpty();val zip=p?.optString("plz").orEmpty();val loc=listOf(zip,city).filter{it.isNotBlank()}.joinToString(" ");val title=x.optString("titel",x.optString("title","Stellenangebot"));val company=x.optString("arbeitgeber",x.optString("arbeitgeberName","Arbeitgeber"));val ref=x.optString("referenznummer",x.optString("refnr","ba-$i-${title.hashCode()}"));val link=x.optString("externeUrl","https://www.arbeitsagentur.de/jobsuche/suche?angebotsart=1&was=${Uri.encode(title)}&wo=${Uri.encode(loc)}");jobs+=JobOffer(ref,title,company,loc.ifBlank{postalCode},"Bundesagentur",link)
                }
            }
        }catch(_:Exception){}}
        return jobs to if(ok>0)"Bundesagentur: ${jobs.size}" else "Bundesagentur nicht erreichbar"
    }

    private fun searchArbeitnow(postalCode:String):Pair<List<JobOffer>,String>{
        // Arbeitnow besitzt keinen belastbaren Kilometer-Radius. Remote-Treffer werden bewusst NICHT
        // mehr eingemischt, weil sie sonst bei 15 km und 50 km identische Ergebnisse erzeugen.
        return try{val req=Request.Builder().url("https://www.arbeitnow.com/api/job-board-api").header("User-Agent","JobRadar23845/2.2").build();client.newCall(req).execute().use{r->
            if(!r.isSuccessful)return emptyList<JobOffer>() to "Arbeitnow HTTP ${r.code}";val a=JSONObject(r.body?.string().orEmpty()).optJSONArray("data")?:return emptyList<JobOffer>() to "Arbeitnow keine Daten";val out=mutableListOf<JobOffer>()
            for(i in 0 until a.length()){val x=a.optJSONObject(i)?:continue;if(x.optBoolean("remote",false))continue;val title=x.optString("title");val desc=x.optString("description");val loc=x.optString("location");val all="$title $desc $loc".lowercase();if(!all.contains(postalCode))continue;val j=JobOffer(x.optString("slug","an-$i-${title.hashCode()}"),title,x.optString("company_name","Arbeitgeber"),loc,"Arbeitnow",x.optString("url","https://www.arbeitnow.com"),desc);if(isSuitable(j))out+=j}
            out to "Arbeitnow: ${out.size}"
        }}catch(e:Exception){emptyList<JobOffer>() to "Arbeitnow ${e.javaClass.simpleName}"}
    }
}
