package app.squeeze.engine

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
import app.squeeze.MainActivity
import app.squeeze.R
import app.squeeze.SqueezeApp
import app.squeeze.ui.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps the process alive while the queue runs and shows progress in the notification shade. */
class CompressionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private val manager get() = (application as SqueezeApp).manager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) manager.cancel()
        createChannels()
        ServiceCompat.startForeground(
            this, ONGOING_ID, progressNotification(manager.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (watcher == null) {
            watcher = scope.launch {
                manager.state.collect { s ->
                    if (s.active) {
                        getSystemService(NotificationManager::class.java).notify(ONGOING_ID, progressNotification(s))
                    } else {
                        ServiceCompat.stopForeground(this@CompressionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        if (s.jobs.isNotEmpty()) getSystemService(NotificationManager::class.java).notify(DONE_ID, doneNotification(s))
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

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(s: QueueState): Notification {
        val total = s.jobs.size.coerceAtLeast(1)
        val finished = s.jobs.count { it.status !is Status.Queued && it.status !is Status.Running }
        val current = s.jobs.firstOrNull { it.status is Status.Running }
        val p = (current?.status as? Status.Running)?.progress ?: 0f
        val overall = ((finished + p) / total * 100).toInt().coerceIn(0, 100)
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, CompressionService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Compressing ${minOf(finished + 1, total)} of $total")
            .setContentText(current?.name)
            .setProgress(100, overall, s.jobs.isEmpty())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, "Cancel", cancel)
            .build()
    }

    private fun doneNotification(s: QueueState): Notification {
        val done = s.jobs.count { it.status is Status.Done }
        val text = if (done == 0) "Nothing needed compressing" else "$done saved · ${formatBytes(s.savedBytes)} freed"
        return NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Squeeze finished")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
    }

    private companion object {
        const val CH_PROGRESS = "progress"
        const val CH_DONE = "done"
        const val ONGOING_ID = 1
        const val DONE_ID = 2
        const val ACTION_CANCEL = "app.squeeze.CANCEL"
    }
}
