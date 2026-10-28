package app.ultimate

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.ultimate.core.UltimateTheme
import app.ultimate.home.UltimateRoot

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_TOOL = "app.ultimate.TOOL" }

    private val ultimate get() = application as UltimateApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent { UltimateTheme { UltimateRoot(ultimate) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleShare(intent: Intent?) {
        // A finished-work notification names its tool, so tapping it lands there instead of on Home.
        intent?.getStringExtra(EXTRA_TOOL)?.let { id -> ultimate.tools.firstOrNull { it.id == id }?.let { ultimate.shell.open(it) }; return }
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM),
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) ultimate.shell.offerShared(uris, intent?.type)
    }
}
