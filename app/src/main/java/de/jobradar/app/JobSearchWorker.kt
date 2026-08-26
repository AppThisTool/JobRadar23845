package de.jobradar.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class JobSearchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // Multi-source search hook. The source adapters can be expanded independently.
        return Result.success()
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
            .build()
        manager.notify(23845, notification)
    }
}
