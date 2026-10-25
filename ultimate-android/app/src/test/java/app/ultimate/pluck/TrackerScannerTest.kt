package app.ultimate.pluck

import app.ultimate.pluck.engine.TrackerScanner
import app.ultimate.pluck.model.AppSource
import app.ultimate.pluck.model.arrange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerScannerTest {
    private fun dex(vararg descriptors: String) = descriptors.joinToString("\u0000") { "L$it;" }.toByteArray(Charsets.US_ASCII)

    @Test fun findsKnownSdksByClassPrefix() {
        val found = TrackerScanner.findIn(dex("com/applovin/sdk/AppLovinSdk", "com/google/firebase/crashlytics/FirebaseCrashlytics", "com/example/Main"))
        assertEquals(setOf("AppLovin", "Firebase Crashlytics"), found.map { it.name }.toSet())
    }

    @Test fun ignoresLookalikePrefixes() {
        assertTrue(TrackerScanner.findIn(dex("com/applovinfake/Thing", "org/example/applovin/Thing")).isEmpty())
    }

    @Test fun searchFindsNeedleAtEdges() {
        val needle = "abc".toByteArray()
        assertEquals(0, TrackerScanner.indexOf("abcxyz".toByteArray(), needle))
        assertEquals(3, TrackerScanner.indexOf("xyzabc".toByteArray(), needle))
        assertEquals(-1, TrackerScanner.indexOf("ababab".toByteArray(), needle))
        assertEquals(-1, TrackerScanner.indexOf("ab".toByteArray(), needle))
    }

    @Test fun classifiesInstallSources() {
        assertEquals(AppSource.PlayStore, AppSource.of("com.android.vending", false))
        assertEquals(AppSource.Sideloaded, AppSource.of(null, false))
        assertEquals(AppSource.Sideloaded, AppSource.of("com.google.android.packageinstaller", false))
        assertEquals(AppSource.System, AppSource.of(null, true))
        assertEquals(AppSource.OtherStore, AppSource.of("org.fdroid.fdroid", false))
    }
}

class ListOptionsTest {
    private fun app(name: String, bytes: Long = 10_000_000, system: Boolean = false, installer: String? = "com.android.vending", target: Int = 34, dangerous: Int = 0, splits: Int = 1) =
        app.ultimate.pluck.model.AppItem(
            packageName = "pkg.$name", label = name, versionName = "1", versionCode = 1, isSystem = system, enabled = true,
            apkPaths = List(splits) { "/x/$it.apk" }, apkBytes = bytes, installTime = 0, updateTime = 0, installer = installer,
            minSdk = 26, targetSdk = target, permissionCount = dangerous, dangerousCount = dangerous,
        )

    private val apps = listOf(
        app("Zed", bytes = 80_000_000, dangerous = 2),
        app("alpha", bytes = 5_000_000, installer = null, target = 28),
        app("Mid", bytes = 20_000_000, dangerous = 9, splits = 3),
        app("System", system = true),
    )
    private val trackers = mapOf(
        "pkg.Zed" to app.ultimate.pluck.model.TrackerFacts(listOf("AppLovin", "Firebase Analytics", "Sentry"), adSdks = 1),
        "pkg.Mid" to app.ultimate.pluck.model.TrackerFacts(listOf("Sentry"), adSdks = 0),
    )
    private fun names(o: app.ultimate.pluck.model.ListOptions, q: String = "") = apps.arrange(q, o, trackers).map { it.label }

    @Test fun defaultsToUserAppsByName() {
        assertEquals(listOf("alpha", "Mid", "Zed"), names(app.ultimate.pluck.model.ListOptions()))
    }

    @Test fun sortsByTrackersThenUnscannedLast() {
        assertEquals(listOf("Zed", "Mid", "alpha"), names(app.ultimate.pluck.model.ListOptions(sort = app.ultimate.pluck.model.Sort.Trackers)))
    }

    @Test fun reverseFlipsOrder() {
        assertEquals(listOf("Zed", "Mid", "alpha"), names(app.ultimate.pluck.model.ListOptions(reversed = true)))
    }

    @Test fun quickFiltersCombine() {
        val q = app.ultimate.pluck.model.QuickFilter.entries
        assertEquals(listOf("Zed"), names(app.ultimate.pluck.model.ListOptions(quick = setOf(q.first { it.name == "HasAds" }))))
        assertEquals(listOf("alpha", "Mid"), names(app.ultimate.pluck.model.ListOptions(quick = setOf(q.first { it.name == "Under30" }))))
        assertEquals(listOf("alpha"), names(app.ultimate.pluck.model.ListOptions(quick = setOf(q.first { it.name == "Under30" }, q.first { it.name == "Sideloaded" }))))
        assertEquals(listOf("Mid"), names(app.ultimate.pluck.model.ListOptions(quick = setOf(q.first { it.name == "Split" }))))
    }

    @Test fun oldestTargetFirst() {
        assertEquals("alpha", names(app.ultimate.pluck.model.ListOptions(sort = app.ultimate.pluck.model.Sort.TargetSdk)).first())
    }

    @Test fun compactEstimateMatchesMeasuredApks() {
        // Apk Extractor 6.0.2: 21.6 MB stored, 2.7 MB already compressed on disk; real zip -9 result was 11.4 MB.
        val est = app.ultimate.pluck.model.estimateCompact(21_617_546, 2_701_860)
        assertTrue("est $est", est in 10_500_000..12_500_000)
    }
}
