package app.ultimate.insights

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import app.ultimate.BuildConfig
import app.ultimate.UltimateApp
import app.ultimate.checkup.engine.Collector
import app.ultimate.pluck.model.Replacements
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Gathers everything the report needs. Only launchable apps are listed, so background system parts stay out. */
object InsightsCollector {
    private const val DAY = 86_400_000L

    suspend fun collect(app: UltimateApp, includeApps: Boolean, notes: String): InsightsInput = withContext(Dispatchers.IO) {
        val stats = app.insights
        val tools = app.tools.map { t ->
            val lib = t.library.value
            ToolUse(t.id, t.title, stats.opens(t.id), stats.minutes(t.id), stats.lastAt(t.id), lib.size, lib.sumOf { it.bytes })
        }
        val usage = Collector(app).hasUsageAccess()
        InsightsInput(
            version = BuildConfig.VERSION_NAME, edition = BuildConfig.FLAVOR,
            device = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}", android = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            generatedAt = System.currentTimeMillis(), trackingSince = stats.since, tools = tools,
            apps = if (includeApps) otherApps(app, usage) else null, usageAccess = usage,
            crashes = stats.crashes(), notes = notes,
        )
    }

    private fun otherApps(context: Context, usage: Boolean): List<OtherApp> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val found = pm.queryIntentActivities(launcher, 0).mapNotNull { it.activityInfo?.applicationInfo }.distinctBy { it.packageName }
        val time = if (usage) runCatching {
            val usm = context.getSystemService(UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            val week = usm.queryAndAggregateUsageStats(now - 7 * DAY, now)
            val longer = usm.queryAndAggregateUsageStats(now - 60 * DAY, now)
            (week.keys + longer.keys).associateWith { (week[it]?.totalTimeInForeground ?: 0L) to (longer[it]?.lastTimeUsed ?: 0L) }
        }.getOrDefault(emptyMap()) else emptyMap()
        return found.filter { it.packageName !in Replacements.ours }.map { info ->
            val t = time[info.packageName]
            OtherApp(
                label = pm.getApplicationLabel(info).toString(), packageName = info.packageName, category = category(info),
                minutes7d = if (usage) (t?.first ?: 0L) / 60_000 else null, lastUsedAt = if (usage) t?.second ?: 0L else null,
                coveredBy = Replacements.forPackage(info.packageName)?.let { r -> app(context).tools.firstOrNull { it.id == r.toolId }?.title },
            )
        }
    }

    private fun app(c: Context) = c.applicationContext as UltimateApp

    private fun category(info: ApplicationInfo): String = when (info.category) {
        ApplicationInfo.CATEGORY_GAME -> "Game"
        ApplicationInfo.CATEGORY_AUDIO -> "Audio"
        ApplicationInfo.CATEGORY_VIDEO -> "Video"
        ApplicationInfo.CATEGORY_IMAGE -> "Photos"
        ApplicationInfo.CATEGORY_SOCIAL -> "Social"
        ApplicationInfo.CATEGORY_NEWS -> "News"
        ApplicationInfo.CATEGORY_MAPS -> "Maps"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
        ApplicationInfo.CATEGORY_ACCESSIBILITY -> "Accessibility"
        else -> if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) "System" else "Other"
    }
}
