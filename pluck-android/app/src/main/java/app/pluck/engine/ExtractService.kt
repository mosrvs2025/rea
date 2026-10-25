package app.pluck.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.pluck.MainActivity
import app.pluck.PluckApp
import app.pluck.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps the process alive while extraction runs and shows progress in the notification shade. */
class ExtractService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private val manager get() = (application as PluckApp).manager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) manager.cancel()
        createChannels()
        ServiceCompat.startForeground(this, ONGOING_ID, progress(manager.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (watcher == null) {
            watcher = scope.launch {
                manager.state.collect { s ->
                    val nm = getSystemService(NotificationManager::class.java)
                    if (s.active) {
                        nm.notify(ONGOING_ID, progress(s))
                    } else {
                        ServiceCompat.stopForeground(this@ExtractService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        if (s.jobs.isNotEmpty()) nm.notify(DONE_ID, done(s))
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, getString(R.string.channel_done), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun open() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progress(s: ExtractState): Notification {
        val total = s.jobs.size.coerceAtLeast(1)
        val finished = s.jobs.count { it.status !is JobStatus.Queued && it.status !is JobStatus.Running }
        val current = s.jobs.firstOrNull { it.status is JobStatus.Running }
        val run = current?.status as? JobStatus.Running
        val fraction = if (run != null && run.total > 0) run.done.toFloat() / run.total else 0f
        val cancel = PendingIntent.getService(this, 1, Intent(this, ExtractService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Extracting ${minOf(finished + 1, total)} of $total")
            .setContentText(current?.app?.label)
            .setProgress(100, ((finished + fraction) / total * 100).toInt().coerceIn(0, 100), s.jobs.isEmpty())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open())
            .addAction(0, "Cancel", cancel)
            .build()
    }

    private fun done(s: ExtractState): Notification {
        val n = s.saved.size
        return NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Pluck finished")
            .setContentText(if (n == 0) "Nothing was extracted" else "$n app${if (n == 1) "" else "s"} saved to Downloads/Pluck")
            .setAutoCancel(true)
            .setContentIntent(open())
            .build()
    }

    private companion object {
        const val CH_PROGRESS = "progress"
        const val CH_DONE = "done"
        const val ONGOING_ID = 1
        const val DONE_ID = 2
        const val ACTION_CANCEL = "app.pluck.CANCEL"
    }
}
