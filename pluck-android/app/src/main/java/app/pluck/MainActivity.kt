package app.pluck

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import app.pluck.model.AppItem
import app.pluck.ui.AppViewModel
import app.pluck.ui.PluckRoot
import app.pluck.ui.PluckTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var pending: (() -> Unit)? = null

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.invoke()
        pending = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PluckTheme { PluckRoot(vm, onExtract = ::extractWithNotificationConsent) }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.reload() // an app may have been installed or uninstalled while we were away
    }

    /** Progress notifications need consent on Android 13+, but extraction works fine without it. */
    private fun extractWithNotificationConsent(items: List<AppItem>, share: Boolean) {
        val run = { vm.extract(items, share) }
        val needs = Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needs) {
            pending = run
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            run()
        }
    }
}
