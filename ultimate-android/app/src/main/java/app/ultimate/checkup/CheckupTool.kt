package app.ultimate.checkup

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.runtime.Composable
import app.ultimate.checkup.engine.CheckupManager
import app.ultimate.checkup.model.Checkup
import app.ultimate.checkup.ui.CheckupScreen
import app.ultimate.core.LibraryItem
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import app.ultimate.core.relativeTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Scans the phone's security, privacy and apps, on the phone, and explains what it finds. */
class CheckupTool(private val manager: CheckupManager) : Tool {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override val id = "checkup"
    override val title = "Phone checkup"
    override val tagline = "Security, privacy and what your apps can reach"
    override val icon = Icons.Rounded.HealthAndSafety
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = manager.last.map { last ->
        last?.let { (score, at) -> "$score/100 · ${Checkup.verdict(score).substringBefore(',')} · ${relativeTime(at)}" } ?: "See what's really on your phone"
    }.stateIn(scope, SharingStarted.Eagerly, "See what's really on your phone")
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow<List<LibraryItem>>(emptyList()).asStateFlow()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = true
    override suspend fun refresh() = Unit
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = CheckupScreen(manager, onExit)
}
