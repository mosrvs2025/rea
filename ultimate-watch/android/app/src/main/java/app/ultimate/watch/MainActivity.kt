package app.ultimate.watch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var url: EditText
    private lateinit var name: EditText
    private lateinit var password: EditText
    private lateinit var record: CheckBox
    private lateinit var segment: EditText
    private lateinit var cap: EditText
    private lateinit var days: EditText
    private lateinit var free: EditText
    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private val prefs by lazy { getSharedPreferences("watch", Context.MODE_PRIVATE) }

    @android.annotation.SuppressLint("InlinedApi") // POST_NOTIFICATIONS is only ever requested on API 33+; elsewhere the lookup is just a missing key.
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.CAMERA] == true || hasCamera()) launchService(notificationsHidden = granted[Manifest.permission.POST_NOTIFICATIONS] == false)
        else status.text = "Camera permission is required to start monitoring."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        fun label(text: String, size: Float = 16f) = TextView(this).apply { this.text = text; textSize = size }.also(column::addView)
        fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT) =
            EditText(this).apply { this.hint = hint; setText(value); inputType = type }.also(column::addView)
        val number = InputType.TYPE_CLASS_NUMBER

        label("ULTIMATE WATCH · Camera", 20f)
        label("Keep your space in view. Only start in a place you are allowed to monitor.")
        url = field("https://your-server:8443", prefs.getString("url", "").orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        name = field("Camera name (e.g. s10-porch)", prefs.getString("name", "").orEmpty())
        password = field("Server password (not saved)", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        record = CheckBox(this).apply { text = "Also record silent video on this phone"; isChecked = prefs.getBoolean("record", false) }.also(column::addView)
        label("Recording limits (each file is short; old files are deleted automatically)")
        val limits = StorageLimits()
        segment = field("Minutes per file", prefs.getInt("seg", limits.segmentMinutes).toString(), number)
        cap = field("Max storage (MB)", prefs.getInt("cap", limits.maxTotalMb).toString(), number)
        days = field("Keep for (days)", prefs.getInt("days", limits.maxAgeDays).toString(), number)
        free = field("Pause when free space is below (MB)", prefs.getInt("free", limits.minFreeMb).toString(), number)
        status = label("Camera is off.")
        start = Button(this).apply { text = "Start monitoring"; setOnClickListener { begin() } }.also(column::addView)
        stop = Button(this).apply { text = "Stop monitoring"; setOnClickListener { CameraService.stop(this@MainActivity) } }.also(column::addView)
        Button(this).apply { text = "Recordings on this phone"; setOnClickListener { startActivity(Intent(this@MainActivity, RecordingsActivity::class.java)) } }.also(column::addView)
        Button(this).apply { text = "Open viewer in browser"; setOnClickListener { openViewer() } }.also(column::addView)
        setContentView(ScrollView(this).apply { addView(column) })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { ServiceState.state.collect(::render) }
        }
    }

    /** Shows what the service is actually doing, not what was requested. */
    private fun render(s: WatchState) {
        status.text = buildString {
            append(
                when (s.phase) {
                    Phase.Idle -> if (s.detail.isEmpty()) "Camera is off." else "Camera is off. ${s.detail}."
                    Phase.Starting -> "Starting: ${s.detail}"
                    Phase.Live -> "LIVE · ${s.framesSent} frames sent"
                    Phase.Reconnecting -> "Reconnecting: ${s.detail}\nThe camera keeps running; frames resume when the server is reachable."
                    Phase.Stopping -> "Stopping: ${s.detail}"
                    Phase.Failed -> "Stopped: ${s.detail}"
                },
            )
            s.recording?.let { append("\n$it") }
            s.warning?.let { append("\n⚠ $it") }
        }
        start.isEnabled = !s.busy
        stop.isEnabled = s.busy
        val editable = !s.busy
        listOf(url, name, password, record, segment, cap, days, free).forEach { it.isEnabled = editable }
    }

    private fun begin() {
        if (Endpoint.parse(url.text.toString()).isFailure) { status.text = "Use a valid HTTPS server address, like https://host:8443."; return }
        if (!CameraNames.isValid(name.text.toString())) { status.text = "Camera name: 1–32 letters, numbers, - or _."; return }
        if (password.text.length < MIN_PASSWORD_LENGTH) { status.text = "Enter the server password (at least $MIN_PASSWORD_LENGTH characters)."; return }
        val needed = buildList {
            if (!hasCamera()) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) launchService(false) else permissions.launch(needed.toTypedArray())
    }

    private fun hasCamera() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun launchService(notificationsHidden: Boolean) {
        val endpoint = Endpoint.parse(url.text.toString()).getOrElse { status.text = it.message; return }
        val limits = StorageLimits(intOf(segment, 5), intOf(cap, 2048), intOf(days, 7), intOf(free, 500)).coerced()
        prefs.edit().putString("url", endpoint.origin).putString("name", name.text.toString()).putBoolean("record", record.isChecked)
            .putInt("seg", limits.segmentMinutes).putInt("cap", limits.maxTotalMb).putInt("days", limits.maxAgeDays).putInt("free", limits.minFreeMb).apply()
        PendingStart.set(StartRequest(endpoint, name.text.toString(), password.text.toString(), record.isChecked, limits))
        password.text.clear()
        ServiceState.set(
            WatchState(
                Phase.Starting, "Waiting for the service",
                warning = if (notificationsHidden) "Notifications are off, so the Stop button will not show in the shade. Stop from this screen." else null,
            ),
        )
        ContextCompat.startForegroundService(this, Intent(this, CameraService::class.java))
    }

    private fun intOf(field: EditText, default: Int) = field.text.toString().trim().toIntOrNull() ?: default

    private fun openViewer() {
        val endpoint = Endpoint.parse(url.text.toString()).getOrNull() ?: run { status.text = "Enter a valid HTTPS server address first."; return }
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(endpoint.origin)))
    }
}
