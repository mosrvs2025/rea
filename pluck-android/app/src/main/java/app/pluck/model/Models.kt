package app.pluck.model

/** One installed app as the package manager reports it. [apkPaths] lists the base APK first, then any split APKs. */
data class AppItem(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val isSystem: Boolean,
    val enabled: Boolean,
    val apkPaths: List<String>,
    val apkBytes: Long,
    val installTime: Long,
    val updateTime: Long,
    val installer: String?,
    val minSdk: Int,
    val targetSdk: Int,
    val permissionCount: Int,
    val dangerousCount: Int,
) {
    val isSplit: Boolean get() = apkPaths.size > 1
    val source: AppSource get() = AppSource.of(installer, isSystem)
}

enum class AppSource(val label: String) {
    System("System"), PlayStore("Play Store"), OtherStore("Other store"), Sideloaded("Sideloaded");

    companion object {
        private val stores = setOf(
            "com.sec.android.app.samsungapps", "com.amazon.venezia", "org.fdroid.fdroid", "com.huawei.appmarket",
            "com.xiaomi.mipicks", "com.heytap.market", "com.aurora.store", "com.apkpure.aegon",
        )
        private val installers = setOf("com.google.android.packageinstaller", "com.android.packageinstaller", "com.google.android.apps.nbu.files")

        fun of(installer: String?, isSystem: Boolean): AppSource = when {
            installer == "com.android.vending" -> PlayStore
            installer in stores -> OtherStore
            installer == null && isSystem -> System
            installer == null || installer in installers -> Sideloaded
            isSystem -> System
            else -> OtherStore
        }
    }
}

enum class Filter(val label: String) { User("User"), System("System"), All("All") }

enum class Sort(val label: String) { Name("Name"), Size("Size"), Updated("Recently updated"), Installed("Recently installed") }

data class Permission(val name: String, val granted: Boolean, val dangerous: Boolean) {
    val short: String get() = name.substringAfterLast('.')
}

data class ApkFile(val name: String, val bytes: Long)

/** The slower-to-compute facts shown on the detail screen. Null counts mean the package manager refused to list them. */
data class AppDetail(
    val item: AppItem,
    val signatures: List<String>,
    val permissions: List<Permission>,
    val activities: Int?,
    val services: Int?,
    val receivers: Int?,
    val providers: Int?,
    val abis: List<String>,
    val apks: List<ApkFile>,
    val uid: Int,
    val enabled: Boolean,
)

fun AppItem.matches(query: String): Boolean {
    val q = query.trim()
    return q.isEmpty() || label.contains(q, ignoreCase = true) || packageName.contains(q, ignoreCase = true)
}

fun List<AppItem>.sortedBy(sort: Sort): List<AppItem> = when (sort) {
    Sort.Name -> sortedBy { it.label.lowercase() }
    Sort.Size -> sortedByDescending { it.apkBytes }
    Sort.Updated -> sortedByDescending { it.updateTime }
    Sort.Installed -> sortedByDescending { it.installTime }
}

fun Filter.accepts(item: AppItem): Boolean = when (this) {
    Filter.User -> !item.isSystem
    Filter.System -> item.isSystem
    Filter.All -> true
}
