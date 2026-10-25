package app.ultimate.pluck.model

/** How completely one of our tools stands in for an installed app. */
enum class Coverage { Full, Mostly }

/**
 * An installed app that one of our tools can stand in for.
 * [missing] lists what the original does that our tool does not, as found in the original's own menus and strings
 * (not by running it), so it is a prompt to double-check rather than a guarantee.
 */
data class Replacement(
    val originalPackage: String,
    val originalName: String,
    val toolId: String,
    val coverage: Coverage,
    val missing: List<String> = emptyList(),
    val note: String? = null,
)

/** A replaceable app that is installed, with where its replacement is available. [accepted] means the user said they do not need the missing features. */
data class Cleanup(val app: AppItem, val replacement: Replacement, val via: String, val accepted: Boolean) {
    /** Safe to uninstall without losing a feature the user wants. */
    val safe: Boolean get() = replacement.coverage == Coverage.Full || accepted
}

/**
 * The apps we know how to replace. When a new APK is reverse-engineered into a tool, add one entry here.
 * Keep [Replacement.missing] honest: anything the original offers that the tool does not.
 */
object Replacements {
    val known: List<Replacement> = listOf(
        Replacement(
            "com.compress.video.compressor.size.reducer", "Compress Video Size", "squeeze", Coverage.Mostly,
            missing = listOf("Crop video", "Change video speed", "Add music to a video", "Convert to another video format", "Pick an exact bitrate or resolution"),
        ),
        Replacement(
            "mp3videoconverter.videotomp3.videotomp3converter", "Video to MP3 Converter", "audio", Coverage.Mostly,
            missing = listOf("Audio cutter for existing audio files", "Audio merger", "Video merger", "Change audio speed", "Convert audio files between formats"),
        ),
        Replacement(
            "com.toralabs.apkextractor", "Apk Extractor", "pluck", Coverage.Mostly,
            missing = listOf("Custom file-name format for saved APKs"),
            note = "It also has an Install option; its purpose was not confirmed.",
        ),
    )

    private val byPackage = known.associateBy { it.originalPackage }

    /** Our own editions must never be offered for cleanup, even if someone marks them by hand. */
    val ours = setOf("app.ultimate", "app.ultimate.plus", "app.squeeze", "app.ultimate.audio", "app.pluck", "app.ultimate.traffic")

    fun forPackage(pkg: String): Replacement? = byPackage[pkg]

    private val names = mapOf("squeeze" to "Squeeze", "audio" to "Video to audio", "pluck" to "Pluck")
    private val homes = mapOf(
        "squeeze" to listOf("app.squeeze", "app.ultimate", "app.ultimate.plus"),
        "audio" to listOf("app.ultimate.audio", "app.ultimate", "app.ultimate.plus"),
        "pluck" to listOf("app.pluck", "app.ultimate", "app.ultimate.plus"),
    )

    fun toolName(id: String) = names[id] ?: id

    /**
     * Which tools can actually be used right now, and where: a tool built into this app, or an installed edition that has it.
     * A replacement that is not installed anywhere is not offered, since uninstalling the original would leave nothing.
     */
    fun availability(ownTools: Set<String>, installed: Set<String>): Map<String, String> =
        names.keys.mapNotNull { id ->
            when {
                id in ownTools -> id to "${names.getValue(id)} in this app"
                else -> homes[id].orEmpty().firstOrNull { it in installed }?.let { id to "${names.getValue(id)} ($it)" }
            }
        }.toMap()

    fun find(apps: List<AppItem>, available: Map<String, String>, custom: Map<String, Replacement>, accepted: Set<String>): List<Cleanup> =
        apps.mapNotNull { a ->
            if (a.packageName in ours) return@mapNotNull null
            val r = custom[a.packageName] ?: byPackage[a.packageName] ?: return@mapNotNull null
            val via = available[r.toolId] ?: return@mapNotNull null
            Cleanup(a, r, via, a.packageName in accepted)
        }
}
