package app.pluck.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF4A47C9), onPrimary = Color.White,
    primaryContainer = Color(0xFFE1DFFF), onPrimaryContainer = Color(0xFF0C0A68),
    tertiary = Color(0xFF8A5100), tertiaryContainer = Color(0xFFFFDDB8),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFC1C1FF), onPrimary = Color(0xFF1B1799),
    primaryContainer = Color(0xFF3330B0), onPrimaryContainer = Color(0xFFE1DFFF),
    tertiary = Color(0xFFFFB86A), tertiaryContainer = Color(0xFF693C00),
)

@Composable
fun PluckTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
