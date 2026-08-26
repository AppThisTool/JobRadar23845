package de.jobradar.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class JobSearchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            val result = JobSearchRepository.search("23845", 15)
            val prefs = applicationContext.getSharedPreferences("jobradar_seen", Context.MODE_PRIVATE)
            val seen = prefs.getStringSet("ids", emptySet())?.toMutableSet() ?: mutableSetOf()
            val newJobs = result.jobs.filter { "${it.source}:${it.id}" !in seen }
            if (newJobs.isNotEmpty()) {
                notifyNewJobs(newJobs.size)
                seen.addAll(newJobs.map { "${it.source}:${it.id}" })
                prefs.edit().putStringSet("ids", seen).apply()
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun notifyNewJobs(count: Int) {
        if (count <= 0) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel("jobs", "Neue Stellen", NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(applicationContext, "jobs")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("JobRadar 23845")
            .setContentText("$count neue passende Stellen gefunden")
            .setAutoCancel(true)
            .build()
        manager.notify(23845, notification)
    }
}
