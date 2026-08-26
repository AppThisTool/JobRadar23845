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

data class JobOffer(
    val id: String,
    val title: String,
    val company: String,
    val location: String,
    val source: String,
    val url: String,
    val description: String = ""
)

data class SearchResult(
    val jobs: List<JobOffer>,
    val sourceMessages: List<String>
)

object JobSearchRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val queries = listOf(
        "Herrenfriseur", "Friseur", "Barber", "Quereinsteiger",
        "Kundenservice", "Empfang", "Rezeption", "Verkauf",
        "Sachbearbeitung", "Büro"
    )

    suspend fun search(postalCode: String, radiusKm: Int): SearchResult = withContext(Dispatchers.IO) {
        coroutineScope {
            val baDeferred = async { searchBundesagentur(postalCode, radiusKm) }
            val arbeitnowDeferred = async { searchArbeitnow(postalCode) }

            val parts = listOf(baDeferred, arbeitnowDeferred).awaitAll()
            val jobs = parts.flatMap { it.first }
                .distinctBy { normalizeKey(it) }
                .sortedWith(compareBy<JobOffer> { suitabilityRank(it) }.thenBy { it.title.lowercase() })
            SearchResult(jobs, parts.map { it.second })
        }
    }

    private fun normalizeKey(job: JobOffer): String =
        "${job.title.lowercase().replace(Regex("[^a-z0-9äöüß]"), "")}|${job.company.lowercase().replace(Regex("[^a-z0-9äöüß]"), "")}|${job.location.lowercase()}"

    private fun suitabilityRank(job: JobOffer): Int {
        val text = (job.title + " " + job.description).lowercase()
        val preferred = listOf("herrenfriseur", "friseur", "barber", "quereinsteiger", "kundenservice", "empfang", "rezeption", "sachbearbeitung", "büro", "verkauf")
        val bad = listOf("lager", "kommissionier", "be- und entladen", "schwere last", "paketzustell", "nachtschicht")
        return when {
            bad.any { it in text } -> 3
            listOf("herrenfriseur", "friseur", "barber").any { it in text } -> 0
            preferred.any { it in text } -> 1
            else -> 2
        }
    }

    private fun searchBundesagentur(postalCode: String, radiusKm: Int): Pair<List<JobOffer>, String> {
        val jobs = mutableListOf<JobOffer>()
        var successfulQueries = 0
        queries.forEach { query ->
            try {
                val url = "https://rest.arbeitsagentur.de/jobboerse/jobsuche-service/pc/v6/jobs" +
                    "?angebotsart=1&was=${Uri.encode(query)}&wo=${Uri.encode(postalCode)}&umkreis=$radiusKm&arbeitszeit=tz&page=1&size=25"
                val request = Request.Builder()
                    .url(url)
                    .header("X-API-Key", "jobboerse-jobsuche")
                    .header("User-Agent", "JobRadar23845/2.1")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string().orEmpty()
                    val root = JSONObject(body)
                    val array = root.optJSONArray("stellenangebote") ?: root.optJSONArray("jobs") ?: return@use
                    successfulQueries++
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val place = item.optJSONObject("arbeitsort")
                        val city = place?.optString("ort").orEmpty()
                        val zip = place?.optString("plz").orEmpty()
                        val location = listOf(zip, city).filter { it.isNotBlank() }.joinToString(" ")
                        val title = item.optString("titel", item.optString("title", "Stellenangebot"))
                        val company = item.optString("arbeitgeber", item.optString("arbeitgeberName", "Arbeitgeber"))
                        val ref = item.optString("referenznummer", item.optString("refnr", "ba-$i-${title.hashCode()}"))
                        val detailUrl = item.optString("externeUrl", "https://www.arbeitsagentur.de/jobsuche/suche?angebotsart=1&was=${Uri.encode(title)}&wo=${Uri.encode(location)}")
                        jobs += JobOffer(ref, title, company, location.ifBlank { postalCode }, "Bundesagentur", detailUrl)
                    }
                }
            } catch (_: Exception) { }
        }
        val message = if (successfulQueries > 0) "Bundesagentur: ${jobs.size} Treffer" else "Bundesagentur: nicht erreichbar"
        return jobs to message
    }

    private fun searchArbeitnow(postalCode: String): Pair<List<JobOffer>, String> {
        return try {
            val request = Request.Builder()
                .url("https://www.arbeitnow.com/api/job-board-api")
                .header("User-Agent", "JobRadar23845/2.1")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList<JobOffer>() to "Arbeitnow: HTTP ${response.code}"
                val root = JSONObject(response.body?.string().orEmpty())
                val array = root.optJSONArray("data") ?: return emptyList<JobOffer>() to "Arbeitnow: keine Daten"
                val result = mutableListOf<JobOffer>()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val title = item.optString("title")
                    val description = item.optString("description")
                    val location = item.optString("location")
                    val searchable = "$title $description $location".lowercase()
                    val interesting = queries.any { searchable.contains(it.lowercase()) }
                    // Arbeitnow liefert keinen zuverlässigen km-Radius. Deshalb nur lokale PLZ-Treffer oder Remote-Stellen ergänzen.
                    val localEnough = searchable.contains(postalCode) || item.optBoolean("remote", false)
                    if (!interesting || !localEnough) continue
                    result += JobOffer(
                        id = item.optString("slug", "arbeitnow-$i-${title.hashCode()}"),
                        title = title,
                        company = item.optString("company_name", "Arbeitgeber"),
                        location = location.ifBlank { if (item.optBoolean("remote", false)) "Remote" else postalCode },
                        source = "Arbeitnow",
                        url = item.optString("url", "https://www.arbeitnow.com"),
                        description = description
                    )
                }
                result to "Arbeitnow: ${result.size} zusätzliche Treffer"
            }
        } catch (e: Exception) {
            emptyList<JobOffer>() to "Arbeitnow: ${e.javaClass.simpleName}"
        }
    }
}
