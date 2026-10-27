package app.ultimate.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

private class Frame(val bytes: ByteArray, val at: Long)

/**
 * Foreground service that keeps the camera open, streams a frame per second to the private server and (optionally) records
 * bounded video segments on the phone. Capture, network and recording are independent: losing the network never interrupts
 * the camera or the recording, and the network side reconnects on its own.
 */
class CameraService : LifecycleService() {
    private val frameExecutor = Executors.newSingleThreadExecutor { Thread(it, "watch-frames") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val latest = AtomicReference<Frame?>(null)
    private val networkChanged = Channel<Unit>(Channel.CONFLATED)
    private val stopping = AtomicBoolean(false)

    private var running = false
    private var request: StartRequest? = null
    private var provider: ProcessCameraProvider? = null
    private var recorder: SegmentRecorder? = null
    private var networkJob: Job? = null
    private var housekeeping: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var shownText = ""

    @Volatile private var frameIntervalMs = NORMAL_INTERVAL_MS
    @Volatile private var lastFrameAt = 0L
    @Volatile private var lastCaptureAt = 0L

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Camera monitoring", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Monitoring stopped", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            if (running) requestStop(WatchState(Phase.Idle, "Stopped")) else stopSelf()
            return START_NOT_STICKY
        }
        // startForegroundService() obliges us to call startForeground() promptly on every start, even a duplicate one we then ignore.
        // A duplicate must not replace the live status with "Starting…", so it re-posts whatever is current.
        val text = if (running) ServiceState.state.value.headline(request?.camera) else "Starting…"
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION_ID, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            else startForeground(NOTIFICATION_ID, notification(text))
            shownText = text
        } catch (e: Exception) {
            ServiceState.set(WatchState(Phase.Failed, "Android would not start the camera service (${e.javaClass.simpleName})"))
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        val req = PendingStart.take()
        if (req == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        stopping.set(false)
        request = req
        shownText = ""
        ServiceState.set(WatchState(Phase.Starting, "Connecting to ${req.endpoint}"))
        acquireLocks()
        watchNetwork()
        watchHeat()
        val client = WatchClient(req.endpoint, req.camera, req.password)
        networkJob = scope.launch { networkLoop(client) }
        housekeeping = lifecycleScope.launch {
            while (isActive) {
                delay(HOUSEKEEPING_MS)
                tick()
            }
        }
        bindCamera(req)
        return START_NOT_STICKY
    }

    // ─────────────────────────────── camera ───────────────────────────────

    private fun bindCamera(req: StartRequest) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!running || stopping.get()) return@addListener
            try {
                val p = future.get()
                provider = p
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                            .build(),
                    )
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(frameExecutor, ::onImage)
                val useCases = mutableListOf<UseCase>(analysis)
                if (req.record) {
                    val r = SegmentRecorder(this, Recordings.dir(this), req.limits) { text -> ServiceState.update { it.copy(recording = text) } }
                    recorder = r
                    useCases += r.useCase
                }
                val camera = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray())
                camera.cameraInfo.cameraState.observe(this) { onCameraState(it) }
                lastCaptureAt = SystemClock.elapsedRealtime()
                recorder?.start()
            } catch (e: Exception) {
                fail("The camera could not be started (${e.javaClass.simpleName}: ${e.message?.take(60)})")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onImage(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            lastCaptureAt = now
            if (running && !stopping.get() && now - lastFrameAt >= frameIntervalMs) {
                lastFrameAt = now
                latest.set(Frame(FrameEncoder.jpeg(image), now))
            }
        } catch (_: Throwable) {
            // A single bad frame must not end monitoring.
        } finally {
            image.close()
        }
    }

    private fun onCameraState(state: CameraState) {
        val error = state.error ?: return
        when (error.code) {
            CameraState.ERROR_CAMERA_DISABLED, CameraState.ERROR_CAMERA_FATAL_ERROR, CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED ->
                fail("The camera is unavailable (error ${error.code}). Another app or a device policy may be using it.")
            else -> ServiceState.update { it.copy(warning = "Camera busy or recovering (error ${error.code})") }
        }
    }

    // ─────────────────────────────── network ───────────────────────────────

    private suspend fun networkLoop(client: WatchClient) {
        val backoff = Backoff()
        var loggedIn = false
        var sent = 0L
        while (coroutineContext.isActive) {
            try {
                if (!loggedIn) {
                    client.login()
                    loggedIn = true
                    backoff.reset()
                }
                val frame = latest.getAndSet(null)
                if (frame == null || SystemClock.elapsedRealtime() - frame.at > STALE_FRAME_MS) {
                    delay(200)
                    continue
                }
                client.postFrame(frame.bytes)
                backoff.reset()
                sent++
                if (!stopping.get()) {
                    ServiceState.update { it.copy(phase = Phase.Live, detail = "", framesSent = sent) }
                    refreshNotification()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                when (val error = classify(t)) {
                    is WatchError.Fatal -> { fail(error.message ?: "Server problem"); return }
                    is WatchError.SessionExpired -> { client.forgetSession(); loggedIn = false }
                    is WatchError.FrameRejected -> Unit
                    is WatchError.Transient -> {
                        val wait = backoff.nextDelayMs()
                        if (stopping.get()) return
                        ServiceState.update { it.copy(phase = Phase.Reconnecting, detail = "${error.message} · retry ${wait / 1000}s (attempt ${backoff.attempts})") }
                        refreshNotification()
                        // A restored network cuts the wait short.
                        withTimeoutOrNull(wait) { networkChanged.receive() }?.let { backoff.reset() }
                    }
                }
            }
        }
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkChanged.trySend(Unit)
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }.onSuccess { networkCallback = callback }
    }

    // ─────────────────────────────── upkeep ───────────────────────────────

    private fun tick() {
        wakeLock?.acquire(WAKE_LOCK_MS)
        recorder?.housekeeping()
        val stalled = SystemClock.elapsedRealtime() - lastCaptureAt > CAMERA_STALL_MS
        ServiceState.update { s ->
            val heat = s.warning?.takeIf { it.startsWith("Phone is hot") }
            s.copy(warning = heat ?: if (stalled) "The camera has stopped delivering frames" else null)
        }
    }

    private fun acquireLocks() {
        // Bounded and refreshed by tick(): if upkeep ever stops, the lock lapses by itself instead of draining the battery.
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UltimateWatch:camera").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_MS)
        }
    }

    private fun watchHeat() {
        val pm = getSystemService(PowerManager::class.java)
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            when {
                status >= PowerManager.THERMAL_STATUS_EMERGENCY -> fail("The phone is overheating, so monitoring was stopped to protect it")
                status >= PowerManager.THERMAL_STATUS_SEVERE -> {
                    frameIntervalMs = HOT_INTERVAL_MS
                    ServiceState.update { it.copy(warning = "Phone is hot: slowed to one frame every 5 s and recording paused") }
                    lifecycleScope.launch { recorder?.suspend("phone is hot") }
                }
                else -> {
                    frameIntervalMs = NORMAL_INTERVAL_MS
                    ServiceState.update { s -> if (s.warning?.startsWith("Phone is hot") == true) s.copy(warning = null) else s }
                    lifecycleScope.launch { recorder?.suspend(null) }
                }
            }
        }
        pm.addThermalStatusListener(ContextCompat.getMainExecutor(this), listener)
        thermalListener = listener
    }

    // ─────────────────────────────── stopping ───────────────────────────────

    private fun fail(reason: String) {
        if (stopping.get()) return
        getSystemService(NotificationManager::class.java).notify(ALERT_ID, alert(reason))
        requestStop(WatchState(Phase.Failed, reason))
    }

    /** Ends monitoring: stops the network, closes the open recording so it is playable, releases the camera, then stops the service. */
    private fun requestStop(final: WatchState) {
        if (!stopping.compareAndSet(false, true)) return
        ServiceState.update { it.copy(phase = Phase.Stopping, detail = "Finishing recording", warning = null) }
        networkJob?.cancel()
        lifecycleScope.launch {
            withContext(NonCancellable) {
                withTimeoutOrNull(FINALIZE_TIMEOUT_MS) {
                    recorder?.let { r -> suspendCancellableCoroutine<Unit> { cont -> r.stop { if (cont.isActive) cont.resume(Unit) } } }
                }
                teardown()
                ServiceState.set(final)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /** Releases everything. Safe to call more than once and from onDestroy. */
    private fun teardown() {
        running = false
        networkJob?.cancel()
        housekeeping?.cancel()
        latest.set(null)
        provider?.unbindAll()
        provider = null
        recorder = null
        request = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        val cm = getSystemService(ConnectivityManager::class.java)
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallback = null
        val pm = getSystemService(PowerManager::class.java)
        thermalListener?.let { pm.removeThermalStatusListener(it) }
        thermalListener = null
    }

    override fun onDestroy() {
        // The system can destroy us without going through requestStop; make sure nothing is left holding the camera or a wake lock.
        val wasRunning = running
        recorder?.stop { }
        teardown()
        if (wasRunning && ServiceState.state.value.phase !in setOf(Phase.Idle, Phase.Failed)) ServiceState.set(WatchState(Phase.Idle, "Stopped"))
        scope.cancel()
        frameExecutor.shutdown()
        super.onDestroy()
    }

    // ─────────────────────────────── notifications ───────────────────────────────

    private fun refreshNotification() {
        val text = ServiceState.state.value.headline(request?.camera)
        if (text == shownText) return
        shownText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, CameraService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Ultimate Watch · camera is on")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun alert(reason: String): Notification {
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, ALERT_CHANNEL)
            .setContentTitle("Ultimate Watch stopped")
            .setContentText(reason)
            .setStyle(Notification.BigTextStyle().bigText(reason))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val ACTION_STOP = "app.ultimate.watch.STOP"
        private const val CHANNEL = "camera"
        private const val ALERT_CHANNEL = "alerts"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_ID = 2
        private const val NORMAL_INTERVAL_MS = 1_000L
        private const val HOT_INTERVAL_MS = 5_000L
        private const val STALE_FRAME_MS = 10_000L
        private const val CAMERA_STALL_MS = 20_000L
        private const val HOUSEKEEPING_MS = 30_000L
        private const val WAKE_LOCK_MS = 10 * 60_000L
        private const val FINALIZE_TIMEOUT_MS = 6_000L

        fun stop(context: Context) {
            context.startService(Intent(context, CameraService::class.java).setAction(ACTION_STOP))
        }
    }
}
