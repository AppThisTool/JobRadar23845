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
    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);if(Build.VERSION.SDK_INT>=33)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);scheduleDailySearch();setContent{MaterialTheme{JobRadarScreen()}}}
    private fun scheduleDailySearch(){val request=PeriodicWorkRequestBuilder<JobSearchWorker>(24,TimeUnit.HOURS).build();WorkManager.getInstance(this).enqueueUniquePeriodicWork("daily-job-search",ExistingPeriodicWorkPolicy.UPDATE,request)}
}

@Composable fun JobRadarScreen(){
    val context=LocalContext.current;val scope=rememberCoroutineScope();val prefs=remember{context.getSharedPreferences("jobradar_settings",0)}
    var radius by remember{mutableFloatStateOf(prefs.getInt("radius",15).toFloat())};var loading by remember{mutableStateOf(false)};var jobs by remember{mutableStateOf<List<JobOffer>>(emptyList())};var status by remember{mutableStateOf("Noch keine Suche durchgeführt.")}
    Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("JobRadar 23845",style=MaterialTheme.typography.headlineMedium);Text("Herrenfriseurin & passende Quereinsteiger-Jobs");Text("Suchradius: ${radius.toInt()} km")
        Slider(value=radius,onValueChange={radius=it;prefs.edit().putInt("radius",it.toInt()).apply()},valueRange=5f..50f,steps=8,enabled=!loading)
        Text("Gesucht: Friseur/Barber sowie einfache Quereinsteiger-Jobs. Bevorzugt ca. 30 Std./Woche, ohne Wochenende und ohne schwere körperliche Arbeit.")
        Button(enabled=!loading,onClick={loading=true;status="Mehrere Stellenbörsen werden durchsucht …";scope.launch{try{val result=JobSearchRepository.search("23845",radius.toInt());jobs=result.jobs;status=if(jobs.isEmpty())"Keine passenden Treffer gefunden." else "${jobs.size} passende Stellen gefunden."}catch(_:Exception){status="Suche derzeit nicht möglich. Bitte später erneut versuchen."}finally{loading=false}}}){if(loading){CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp);Spacer(Modifier.width(10.dp))};Text(if(loading)"Suche läuft …" else "Jetzt Stellen suchen")}
        Text(status);HorizontalDivider()
        if(jobs.isNotEmpty())LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp)){items(jobs,key={"${it.source}-${it.id}"}){job->ElevatedCard(Modifier.fillMaxWidth().clickable{if(job.url.isNotBlank())context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(job.url)))}){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(job.title,style=MaterialTheme.typography.titleMedium);Text(job.company);Text(job.location);if(job.hints.isNotEmpty())Text(job.hints.joinToString(" · "),style=MaterialTheme.typography.labelMedium);Text("Stellenanzeige öffnen",style=MaterialTheme.typography.bodySmall)}}}}
        else{Text("Die Suche läuft quellenübergreifend im Hintergrund; doppelte Anzeigen werden zusammengeführt.");Text("Die tägliche automatische Suche ist aktiviert.")}
    }
}
