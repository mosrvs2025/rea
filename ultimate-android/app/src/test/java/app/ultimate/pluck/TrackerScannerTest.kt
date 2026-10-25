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

class ReplacementsTest {
    private fun app(pkg: String, name: String = pkg) = app.ultimate.pluck.model.AppItem(
        packageName = pkg, label = name, versionName = "1", versionCode = 1, isSystem = false, enabled = true, apkPaths = listOf("/x.apk"),
        apkBytes = 10_000_000, installTime = 0, updateTime = 0, installer = null, minSdk = 26, targetSdk = 34, permissionCount = 0, dangerousCount = 0,
    )

    private val original = app("com.toralabs.apkextractor", "Apk Extractor")
    private val unrelated = app("com.example.other")

    @Test fun toolBuiltIntoThisAppIsAvailable() {
        val av = app.ultimate.pluck.model.Replacements.availability(setOf("squeeze", "audio", "pluck"), setOf("app.ultimate"))
        assertEquals(setOf("squeeze", "audio", "pluck"), av.keys)
        assertTrue(av.getValue("squeeze").endsWith("in this app"))
    }

    @Test fun standaloneEditionSeesOtherToolsOnlyWhenInstalled() {
        val none = app.ultimate.pluck.model.Replacements.availability(setOf("pluck"), setOf("app.pluck"))
        assertEquals(setOf("pluck"), none.keys)
        val withSqueeze = app.ultimate.pluck.model.Replacements.availability(setOf("pluck"), setOf("app.pluck", "app.squeeze"))
        assertEquals(setOf("pluck", "squeeze"), withSqueeze.keys)
        val viaSuite = app.ultimate.pluck.model.Replacements.availability(setOf("pluck"), setOf("app.pluck", "app.ultimate"))
        assertEquals(setOf("pluck", "squeeze", "audio"), viaSuite.keys)
    }

    @Test fun findsKnownAppsOnlyWhenTheirReplacementIsUsable() {
        val av = mapOf("pluck" to "Pluck in this app")
        val found = app.ultimate.pluck.model.Replacements.find(listOf(original, unrelated), av, emptyMap(), emptySet())
        assertEquals(listOf("com.toralabs.apkextractor"), found.map { it.app.packageName })
        assertTrue(app.ultimate.pluck.model.Replacements.find(listOf(original), emptyMap(), emptyMap(), emptySet()).isEmpty())
    }

    @Test fun partialReplacementsAreOnlySafeOnceAccepted() {
        val av = mapOf("pluck" to "Pluck in this app")
        val before = app.ultimate.pluck.model.Replacements.find(listOf(original), av, emptyMap(), emptySet()).single()
        assertTrue(!before.safe)
        assertTrue(before.replacement.missing.isNotEmpty())
        val after = app.ultimate.pluck.model.Replacements.find(listOf(original), av, emptyMap(), setOf(original.packageName)).single()
        assertTrue(after.safe)
    }

    @Test fun handMarkedAppsCountAsSafeAndOurOwnAppsAreNeverOffered() {
        val mine = app("com.example.mine", "Mine")
        val custom = mapOf(
            mine.packageName to app.ultimate.pluck.model.Replacement(mine.packageName, "Mine", "squeeze", app.ultimate.pluck.model.Coverage.Full),
            "app.ultimate" to app.ultimate.pluck.model.Replacement("app.ultimate", "Ultimate", "squeeze", app.ultimate.pluck.model.Coverage.Full),
        )
        val av = mapOf("squeeze" to "Squeeze in this app")
        val found = app.ultimate.pluck.model.Replacements.find(listOf(mine, app("app.ultimate")), av, custom, emptySet())
        assertEquals(listOf("com.example.mine"), found.map { it.app.packageName })
        assertTrue(found.single().safe)
    }

    @Test fun replacedQuickFilterShowsOnlyThoseApps() {
        val shown = listOf(original, unrelated).arrange("", app.ultimate.pluck.model.ListOptions(quick = setOf(app.ultimate.pluck.model.QuickFilter.Replaced)), emptyMap(), setOf(original.packageName))
        assertEquals(listOf("Apk Extractor"), shown.map { it.label })
    }

    @Test fun everyKnownEntryPointsAtARealToolAndNeverAtOurOwnPackage() {
        app.ultimate.pluck.model.Replacements.known.forEach {
            assertTrue(it.toolId in setOf("squeeze", "audio", "pluck"))
            assertTrue(it.originalPackage !in app.ultimate.pluck.model.Replacements.ours)
        }
    }
}
