package de.jobradar.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.work.*
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
    var radius by remember { mutableFloatStateOf(15f) }
    Scaffold { padding ->
        Column(Modifier.padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("JobRadar 23845", style = MaterialTheme.typography.headlineMedium)
            Text("Friseur & passende Quereinsteiger-Jobs")
            Text("Suchradius: ${radius.toInt()} km")
            Slider(value = radius, onValueChange = { radius = it }, valueRange = 5f..50f, steps = 8)
            Text("Gesucht wird bevorzugt nach ca. 30 Std./Woche, ohne Wochenendarbeit und ohne schwere körperliche Tätigkeit.")
            Button(onClick = { }) { Text("Jetzt Stellen suchen") }
            HorizontalDivider()
            Text("Quellen: Bundesagentur für Arbeit, Arbeitnow; Jooble und Adzuna vorbereitet")
            Text("Die tägliche automatische Suche ist aktiviert.")
        }
    }
}
