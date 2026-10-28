package app.ultimate.core

import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** What handing a file to a tool means, in words. */
private val verbs = mapOf("studio" to "Edit in Studio", "squeeze" to "Compress", "audio" to "Extract audio")

/** "Send to…" buttons for every other tool that can open [uri], so tools flow into each other without the share sheet. */
@Composable
fun SendTo(uri: Uri, mime: String, from: String, onSent: () -> Unit = {}) {
    val nav = LocalShellNav.current ?: return
    val targets = nav.tools.filter { t -> t.id != from && t.acceptedMimePrefixes.any { mime.startsWith(it) } }
    if (targets.isEmpty()) return
    Column {
        Text("Continue with", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            targets.forEach { t ->
                FilledTonalButton({ onSent(); nav.send(t, listOf(uri)) }, shape = RoundedCornerShape(14.dp)) {
                    Icon(t.icon, null); Spacer(Modifier.width(6.dp)); Text(verbs[t.id] ?: "Open in ${t.title}")
                }
            }
        }
    }
}
