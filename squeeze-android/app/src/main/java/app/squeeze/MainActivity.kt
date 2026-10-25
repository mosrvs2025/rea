package app.squeeze

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import app.squeeze.ui.AppViewModel
import app.squeeze.ui.SqueezeRoot
import app.squeeze.ui.SqueezeTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val picker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.add(it) }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            SqueezeTheme {
                SqueezeRoot(
                    vm = vm,
                    onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                    onStart = ::startWithNotificationConsent,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Progress notifications need consent on Android 13+, but compression works fine without it. */
    private fun startWithNotificationConsent() {
        val needs = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needs) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
    }

    @Suppress("DEPRECATION")
    private fun handleShare(intent: Intent?) {
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
        if (uris.isNotEmpty()) vm.add(uris)
    }
}
