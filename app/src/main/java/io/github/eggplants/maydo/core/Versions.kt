package io.github.eggplants.maydo.core

/** Version strings and release assets, kept free of Android APIs so that they can be tested. */
object Versions {
    /**
     * Orders yt-dlp's date versions: `2026.08.19` < `2026.08.19.1` (a second release that day) <
     * `2026.08.20`. A part that is not a number counts as 0.
     */
    fun compareYtdlp(a: String, b: String): Int {
        val x = a.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private val APP_TAG = Regex("""^v(\d+\.\d+\.\d+)-(\d+)$""")

    /** `v1.2.3-4` as versionName `1.2.3` and versionCode 4; null for any other tag. */
    fun appTag(tag: String): Pair<String, Int>? =
        APP_TAG.matchEntire(tag.trim())?.destructured?.let { (name, code) ->
            code.toIntOrNull()?.let { name to it }
        }

    /**
     * The release's APK for a device with [abis] (most preferred first): the one built for the
     * first ABI that has one, else the universal one, else whatever APK there is.
     */
    fun pickApk(names: List<String>, abis: List<String>): String? {
        val apks = names.filter { it.endsWith(".apk", ignoreCase = true) }
        return abis.firstNotNullOfOrNull { abi -> apks.firstOrNull { it.endsWith("-$abi.apk") } }
            ?: apks.firstOrNull { it.endsWith("-universal.apk") }
            ?: apks.firstOrNull()
    }
}
