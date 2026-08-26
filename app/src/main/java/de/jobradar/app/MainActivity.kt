package de.jobradar.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.*
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        scheduleDailySearch()
        setContent { MaterialTheme { JobRadarScreen() } }
    }

    private fun scheduleDailySearch() {
        val request = PeriodicWorkRequestBuilder<JobSearchWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("daily-job-search", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

@Composable
fun JobRadarScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var radius by remember { mutableFloatStateOf(15f) }
    var loading by remember { mutableStateOf(false) }
    var jobs by remember { mutableStateOf<List<JobOffer>>(emptyList()) }
    var status by remember { mutableStateOf("Noch keine Suche durchgeführt.") }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("JobRadar 23845", style = MaterialTheme.typography.headlineMedium)
        Text("Friseur & passende Quereinsteiger-Jobs")
        Text("Suchradius: ${radius.toInt()} km")
        Slider(value = radius, onValueChange = { radius = it }, valueRange = 5f..50f, steps = 8, enabled = !loading)
        Text("Bevorzugt: ca. 30 Std./Woche, kein Wochenende und keine schwere körperliche Tätigkeit.")

        Button(enabled = !loading, onClick = {
            loading = true
            status = "Stellen werden durchsucht …"
            scope.launch {
                try {
                    val result = JobSearchRepository.search("23845", radius.toInt())
                    jobs = result.jobs
                    status = if (jobs.isEmpty()) "Keine passenden Treffer gefunden." else "${jobs.size} passende Stellen gefunden."
                } catch (e: Exception) {
                    status = "Suche derzeit nicht möglich. Bitte später erneut versuchen."
                } finally { loading = false }
            }
        }) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(if (loading) "Suche läuft …" else "Jetzt Stellen suchen")
        }

        Text(status, style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider()

        if (jobs.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(jobs, key = { "${it.source}-${it.id}" }) { job ->
                    ElevatedCard(Modifier.fillMaxWidth().clickable {
                        if (job.url.isNotBlank()) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(job.url)))
                    }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(job.title, style = MaterialTheme.typography.titleMedium)
                            Text(job.company)
                            Text(job.location)
                            Text("Stellenanzeige öffnen", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else {
            Text("JobRadar durchsucht die angebundenen Stellenangebote automatisch im Hintergrund.")
            Text("Die tägliche automatische Suche ist aktiviert.")
        }
    }
}
