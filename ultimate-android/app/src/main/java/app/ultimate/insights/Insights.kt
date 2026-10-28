package app.ultimate.insights

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How one tool has been used on this phone. */
data class ToolUse(val id: String, val title: String, val opens: Int, val minutes: Long, val lastAt: Long, val results: Int, val resultBytes: Long)

/** Another app on the phone, for spotting what Ultimate could absorb next. */
data class OtherApp(val label: String, val packageName: String, val category: String, val minutes7d: Long?, val lastUsedAt: Long?, val coveredBy: String?)

/** A crash Ultimate caught, kept on the phone so it can be reported. */
data class Crash(val at: Long, val version: String, val type: String, val message: String?, val frames: List<String>)

data class InsightsInput(
    val version: String,
    val edition: String,
    val device: String,
    val android: String,
    val generatedAt: Long,
    val tools: List<ToolUse>,
    val apps: List<OtherApp>?,
    val usageAccess: Boolean,
    val crashes: List<Crash>,
    val notes: String,
)

/** Turns what the phone knows about Ultimate's use into Markdown an AI (or a person) can plan the next version from. */
object InsightsReport {
    const val PROMPT = "This is a usage report from my Ultimate Android app (a personal, ad-free, offline-first super app). " +
        "Using it, suggest which new tools to build into Ultimate next (especially ones that would let me uninstall the apps listed), " +
        "what to improve in the tools I use most, and fixes for any crashes. Rank by impact."

    fun markdown(i: InsightsInput): String = buildString {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        appendLine("# Ultimate usage report")
        appendLine()
        appendLine("- Version: ${i.version} (${i.edition})")
        appendLine("- Device: ${i.device}, Android ${i.android}")
        appendLine("- Generated: ${day.format(Date(i.generatedAt))}")
        appendLine()
        appendLine("## Tools")
        appendLine()
        appendLine("| Tool | Opens | Minutes | Last used | Results |")
        appendLine("|---|---:|---:|---|---:|")
        i.tools.sortedWith(compareByDescending<ToolUse> { it.minutes }.thenByDescending { it.opens }).forEach { t ->
            appendLine("| ${cell(t.title)} | ${t.opens} | ${t.minutes} | ${if (t.lastAt > 0) day.format(Date(t.lastAt)) else "never"} | ${t.results} |")
        }
        val unused = i.tools.filter { it.opens == 0 }
        if (unused.isNotEmpty()) appendLine("\nNever opened: ${unused.joinToString { it.title }}.")
        appendLine()
        if (i.notes.isNotBlank()) {
            appendLine("## My notes")
            appendLine()
            appendLine(i.notes.trim())
            appendLine()
        }
        appendLine("## Crashes")
        appendLine()
        if (i.crashes.isEmpty()) appendLine("None recorded.")
        i.crashes.sortedByDescending { it.at }.forEach { c ->
            appendLine("- ${day.format(Date(c.at))} · ${c.version} · `${c.type}`${c.message?.let { ": ${cell(it.take(200))}" } ?: ""}")
            c.frames.forEach { appendLine("  - `$it`") }
        }
        appendLine()
        if (i.apps != null) {
            appendLine("## My other apps")
            appendLine()
            if (!i.usageAccess) appendLine("Usage access is off, so time spent is unknown.\n")
            val (covered, rest) = i.apps.partition { it.coveredBy != null }
            if (covered.isNotEmpty()) {
                appendLine("Already covered by an Ultimate tool:")
                covered.forEach { appendLine("- ${it.label} → ${it.coveredBy}${it.minutes7d?.let { m -> " (${m} min this week)" } ?: ""}") }
                appendLine()
            }
            appendLine("| App | Category | Min / 7 days | Last used |")
            appendLine("|---|---|---:|---|")
            rest.sortedWith(compareByDescending<OtherApp> { it.minutes7d ?: -1 }.thenBy { it.label.lowercase() }).forEach { a ->
                val last = a.lastUsedAt?.let { if (it > 0) day.format(Date(it)) else "not in 60 days" } ?: "unknown"
                appendLine("| ${cell(a.label)} | ${a.category} | ${a.minutes7d ?: "?"} | $last |")
            }
        }
    }

    private fun cell(s: String) = s.replace("|", "/").replace("\n", " ")
}
