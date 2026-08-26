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
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity:ComponentActivity(){private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()){};override fun onCreate(b:Bundle?){super.onCreate(b);if(Build.VERSION.SDK_INT>=33)permission.launch(Manifest.permission.POST_NOTIFICATIONS);schedule();setContent{MaterialTheme{JobRadarScreen()}}};private fun schedule(){val r=PeriodicWorkRequestBuilder<JobSearchWorker>(24,TimeUnit.HOURS).build();WorkManager.getInstance(this).enqueueUniquePeriodicWork("daily-job-search",ExistingPeriodicWorkPolicy.UPDATE,r)}}

@Composable fun JobRadarScreen(){
 val context=LocalContext.current;val scope=rememberCoroutineScope();val prefs=remember{context.getSharedPreferences("jobradar_settings",0)}
 var radius by remember{mutableFloatStateOf(prefs.getInt("radius",15).toFloat())};var loading by remember{mutableStateOf(false)};var jobs by remember{mutableStateOf<List<JobOffer>>(emptyList())};var status by remember{mutableStateOf("Noch keine Suche durchgeführt.")}
 Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  Text("JobRadar 23845",style=MaterialTheme.typography.headlineMedium);Text("Herrenfriseurin & passende Quereinsteiger-Jobs")
  Text("Umkreis ab Itzstedt: ${radius.toInt()} km");Slider(value=radius,onValueChange={radius=it;prefs.edit().putInt("radius",it.toInt()).apply()},valueRange=5f..50f,steps=8,enabled=!loading)
  Text("Friseur/Barber oder einfache Quereinsteiger-Tätigkeiten · möglichst ca. 30 Std. · kein Wochenende · keine schwere körperliche Arbeit")
  Button(enabled=!loading,onClick={loading=true;status="Stellen werden geprüft …";scope.launch{try{val result=JobSearchRepository.search("23845",radius.toInt());jobs=result.jobs;status=if(jobs.isEmpty())"Keine ausreichend passenden Stellen im Umkreis gefunden." else "${jobs.size} passende Stellen innerhalb ${radius.toInt()} km gefunden."}catch(_:Exception){status="Suche derzeit nicht möglich."}finally{loading=false}}}){if(loading){CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp);Spacer(Modifier.width(8.dp))};Text(if(loading)"Suche läuft …" else "Jetzt Stellen suchen")}
  Text(status);HorizontalDivider()
  if(jobs.isNotEmpty())LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp)){items(jobs,key={"${it.source}-${it.id}"}){j->ElevatedCard(Modifier.fillMaxWidth().clickable{if(j.url.isNotBlank())context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(j.url)))}){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
    Text(j.title,style=MaterialTheme.typography.titleMedium);Text(j.company,style=MaterialTheme.typography.bodyMedium)
    Text("📍 ${j.location}"+(j.distanceKm?.let{" · ${String.format(Locale.GERMANY,"%.1f",it)} km von Itzstedt"}?:""),style=MaterialTheme.typography.bodyMedium)
    if(j.hints.isNotEmpty())Text(j.hints.joinToString(" · "),style=MaterialTheme.typography.labelMedium)
    Text("Anzeige ansehen ›",style=MaterialTheme.typography.bodySmall)
  }}}}
  else Text("Es werden nur Treffer angezeigt, deren Arbeitsort und Eignung ausreichend geprüft werden konnten.")
 }
}
