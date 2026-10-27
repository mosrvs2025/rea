package app.ultimate.watch

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.*
import android.text.InputType
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var url: EditText
    private lateinit var password: EditText
    private lateinit var name: EditText
    private lateinit var record: CheckBox
    private lateinit var status: TextView
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else status.text = "Camera permission is required."
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32,48,32,24) }
        fun label(value: String) { column.addView(TextView(this).apply { text=value; textSize=18f }) }
        label("ULTIMATE WATCH · Camera")
        label("Keep your space in view")
        url=EditText(this).apply { hint="https://your-server:8443"; inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }; column.addView(url)
        name=EditText(this).apply { hint="Camera name (e.g. s10-porch)" }; column.addView(name)
        password=EditText(this).apply { hint="Server password"; inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }; column.addView(password)
        record=CheckBox(this).apply { text="Also record silent video on this phone" }; column.addView(record)
        status=TextView(this).apply { text="Camera is off. Start only in a space you are authorized to monitor." }; column.addView(status)
        column.addView(Button(this).apply { text="Start monitoring"; setOnClickListener { permission.launch(Manifest.permission.CAMERA) } })
        column.addView(Button(this).apply { text="Stop monitoring"; setOnClickListener { stopService(Intent(this@MainActivity,CameraService::class.java)); status.text="Stop requested." } })
        column.addView(Button(this).apply { text="Open viewer"; setOnClickListener { try { val u=java.net.URI(url.text.toString()); require(u.scheme=="https" && u.host!=null); startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(u.toString()))) } catch (_:Exception) { status.text="Enter a valid HTTPS server URL." } } })
        label("Recordings: Android/data/app.ultimate.watch/files/Movies. Experimental: validate on each phone before relying on monitoring.")
        setContentView(column)
    }
    private fun launchCamera() {
        val endpoint=url.text.toString().trim().trimEnd('/')
        try { val u=java.net.URI(endpoint); require(u.scheme=="https" && u.host!=null && u.rawUserInfo==null && u.rawQuery==null && u.rawFragment==null && (u.path.isNullOrEmpty() || u.path=="/")) } catch (_:Exception) { status.text="Use a valid HTTPS server origin."; return }
        if (!name.text.toString().matches(Regex("[A-Za-z0-9_-]{1,32}")) || password.text.length<16) { status.text="Use a unique camera name and the server password (16+ characters)."; return }
        val intent=Intent(this,CameraService::class.java).putExtra("url",endpoint).putExtra("name",name.text.toString()).putExtra("password",password.text.toString()).putExtra("record",record.isChecked)
        ContextCompat.startForegroundService(this,intent)
        password.text.clear()
        status.text="Monitoring requested. Check the persistent notification for status."
    }
}
