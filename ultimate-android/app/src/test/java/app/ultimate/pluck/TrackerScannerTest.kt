package app.ultimate.pluck

import app.ultimate.pluck.engine.TrackerScanner
import app.ultimate.pluck.model.AppSource
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
