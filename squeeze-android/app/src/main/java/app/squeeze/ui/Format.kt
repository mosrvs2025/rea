package app.squeeze.ui

import java.util.Locale

fun formatBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0).toDouble()
    return when {
        b >= 1e9 -> String.format(Locale.US, "%.2f GB", b / 1e9)
        b >= 1e8 -> String.format(Locale.US, "%.0f MB", b / 1e6)
        b >= 1e6 -> String.format(Locale.US, "%.1f MB", b / 1e6)
        b >= 1e3 -> String.format(Locale.US, "%.0f KB", b / 1e3)
        else -> "${b.toLong()} B"
    }
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
}

fun formatEta(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(1)
    return if (s >= 60) "${s / 60} min ${s % 60} s left" else "$s s left"
}

fun formatMbps(bps: Int): String = String.format(Locale.US, "%.1f Mbps", bps / 1e6)

fun percentSaved(before: Long, after: Long): Int = if (before <= 0) 0 else ((1 - after.toDouble() / before) * 100).toInt().coerceIn(0, 100)
