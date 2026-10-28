package app.ultimate.send

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.core.LibraryItem
import app.ultimate.core.ShellActions
import app.ultimate.core.Tool
import app.ultimate.core.ToolBackButton
import app.ultimate.core.ToolLaunch
import app.ultimate.core.copyText
import app.ultimate.core.formatBytes
import app.ultimate.core.relativeTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/** Move files between this phone and any computer on the same Wi-Fi, through the computer's browser. */
class SendTool(private val server: SendServer) : Tool {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    override val id = "send"
    override val title = "Send to computer"
    override val tagline = "Files to and from any computer on your Wi-Fi"
    override val icon = Icons.Rounded.Computer
    override val acceptedMimePrefixes = listOf("video/", "image/", "audio/", "application/", "text/")
    override val headline: StateFlow<String> = server.state.map { if (it.running) "On · open the address on your computer" else "No cable, no cloud" }
        .stateIn(scope, SharingStarted.Eagerly, "No cable, no cloud")
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow<List<LibraryItem>>(emptyList()).asStateFlow()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = true
    override suspend fun refresh() = Unit
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = SendScreen(server, onExit, launch)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendScreen(server: SendServer, onExit: () -> Unit, launch: ToolLaunch) {
    BackHandler(onBack = onExit)
    val state by server.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { server.offer(it) }
    LaunchedEffect(launch) { if (!launch.consumed) { launch.consumed = true; if (launch.uris.isNotEmpty()) { server.offer(launch.uris); server.start() } } }
    // The server lives only while this screen is open, so nothing listens on the network unnoticed.
    DisposableEffect(Unit) { onDispose { server.stop() } }
    val pulse by rememberInfiniteTransition(label = "on").animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot")

    Scaffold(topBar = { TopAppBar(title = { Text("Send to computer") }, navigationIcon = { ToolBackButton(onExit) }, actions = { ShellActions() }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = if (state.running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AnimatedContent(state.address, label = "address") { address ->
                            if (address == null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Rounded.Computer, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                                Text("Your computer's browser becomes the cable", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Both need to be on the same Wi-Fi. Nothing goes through the internet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(server::start, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text("Turn on") }
                            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF2E9E5B).copy(alpha = pulse)))
                                    Spacer(Modifier.width(8.dp)); Text("On. Type this into your computer's browser:", style = MaterialTheme.typography.bodyMedium)
                                }
                                Text(address.removePrefix("http://"), Modifier.clickable { copyText(context, "Address", address) }, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                Text("Tap it to copy. The code at the end keeps others on your Wi-Fi out. Leaving this screen turns it off.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                TextButton(server::stop) { Text("Turn off") }
                            }
                        }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Offered to the computer", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedButton({ picker.launch(arrayOf("*/*")) }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(4.dp)); Text("Add files") }
                }
            }
            if (state.offered.isEmpty()) item { Text("Nothing yet. Add files, then download them from the browser.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.offered, key = { it.uri.toString() }) { f ->
                Row(Modifier.fillMaxWidth().animateItem().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(f.name, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(formatBytes(f.bytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton({ server.unoffer(f.uri) }) { Icon(Icons.Rounded.Close, "Stop offering") }
                }
            }
            if (state.received.isNotEmpty()) {
                item { Text("Received from the computer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                items(state.received, key = { it.at.toString() + it.name }) { r ->
                    Row(Modifier.animateItem(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp))
                        Column { Text(r.name, fontWeight = FontWeight.Medium); Text("${formatBytes(r.bytes)} · ${relativeTime(r.at)} · Downloads/Ultimate", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
}
