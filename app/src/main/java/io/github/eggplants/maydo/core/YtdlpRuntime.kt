package io.github.eggplants.maydo.core

import android.content.Context
import android.system.Os
import android.util.Log
import io.github.eggplants.maydo.BuildConfig
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipFile

sealed interface RuntimeState {
    data object Preparing : RuntimeState

    data class Ready(val ytdlpVersion: String) : RuntimeState

    data class Failed(val message: String) : RuntimeState
}

/**
 * What runs yt-dlp: youtubedl-android's Python and QuickJS, the yt-dlp zipapp from
 * https://github.com/yt-dlp/yt-dlp/releases, and `libytdlp.so` (native/ytdlp.c), which starts the
 * one on the other and is what ytdl_hook (`assets/ytdl_hook.lua`) asks the app to run.
 *
 * The programs run out of nativeLibraryDir, the only place an app may start them from. Python's
 * libraries and standard library come zipped in `libpython.zip.so` and are unpacked once per APK.
 * The zipapp is in the APK's assets and is copied out on the first start, unless a newer one has
 * been downloaded since.
 */
class YtdlpRuntime(private val context: Context) {
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)

    /** The executable for ytdl_hook's `ytdl_path`. */
    val launcher = File(nativeDir, "libytdlp.so")

    /** QuickJS, for yt-dlp's `--js-runtimes quickjs:<path>`. */
    val quickJs = File(nativeDir, "libqjs.so")

    private val python = File(nativeDir, "libpython.so")
    private val pythonRoot = File(context.noBackupFilesDir, "python")
    private val usr = File(pythonRoot, "usr")
    private val ytdlpDir = File(context.filesDir, "yt-dlp")
    private val script = File(ytdlpDir, "yt-dlp")
    private val versionFile = File(ytdlpDir, "version")

    /** The CA bundle Python comes with; mpv's own TLS uses it as well. */
    val caFile = File(usr, "etc/tls/cert.pem")

    /** Maydo's copy of mpv's ytdl_hook.lua, which leaves running yt-dlp to the app. */
    val ytdlHook = File(context.noBackupFilesDir, HOOK)

    /** `$XDG_CONFIG_HOME`: yt-dlp reads `yt-dlp/config` here, mpv `mpv/mpv.conf`. */
    val configDir = File(context.filesDir, "config")

    private val mutex = Mutex()
    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Preparing)
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    /**
     * Gets everything ready, once per process; later calls return at once. False when it failed,
     * with the reason in [state].
     */
    suspend fun prepare(): Boolean = mutex.withLock {
        if (_state.value is RuntimeState.Ready) return true
        _state.value = RuntimeState.Preparing
        withContext(Dispatchers.IO) {
            runCatching {
                unpackPython()
                installBundledYtdlp()
                configDir.mkdirs()
                // Again every start: it comes with the APK.
                context.assets.open(HOOK).use { input ->
                    ytdlHook.outputStream().use { input.copyTo(it) }
                }
                installedVersion() ?: BuildConfig.YTDLP_VERSION
            }
                .onSuccess { _state.value = RuntimeState.Ready(it) }
                .onFailure {
                    Log.e(TAG, "preparing yt-dlp failed", it)
                    _state.value = RuntimeState.Failed(it.message ?: it.toString())
                }
                .isSuccess
        }
    }

    /** The version of the yt-dlp in use, as yt-dlp reported it when it was put in place. */
    fun installedVersion(): String? =
        versionFile.takeIf { it.isFile && script.isFile }?.readText()?.trim()?.ifEmpty { null }

    /**
     * Replaces yt-dlp with [release]'s zipapp. It is checked against GitHub's digest and has to run
     * before it is put in place. Returns the version it reports.
     */
    suspend fun update(release: Release, onProgress: (Long, Long) -> Unit): String {
        val asset =
            release.assets.firstOrNull { it.name == ASSET }
                ?: throw IOException("${release.tagName} has no $ASSET")
        check(prepare()) { (state.value as? RuntimeState.Failed)?.message.orEmpty() }
        val part = File(ytdlpDir, "$ASSET.part")
        try {
            part.outputStream().use { GitHub.download(asset, it, onProgress) }
            val version = withContext(Dispatchers.IO) { run(part, "--version").trim() }
            if (version.isEmpty() || version.lines().size != 1) {
                throw IOException("${release.tagName} does not run: $version")
            }
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    if (!part.renameTo(script)) throw IOException("cannot replace $script")
                    versionFile.writeText(version)
                }
                _state.value = RuntimeState.Ready(version)
            }
            return version
        } finally {
            part.delete()
        }
    }

    /** Runs [zipapp] with [args] and returns what it printed; throws when it fails. */
    private fun run(zipapp: File, vararg args: String): String {
        val process =
            ProcessBuilder(listOf(python.path, zipapp.path) + args)
                .redirectErrorStream(true)
                .apply { environment().putAll(environment(zipapp)) }
                .start()
        val output = process.inputStream.use { it.readBytes().decodeToString() }
        if (process.waitFor() != 0) throw IOException(output.trim())
        return output
    }

    /** The environment to start [launcher] in. */
    fun launcherEnvironment(): Map<String, String> = environment(script)

    /**
     * What Python and yt-dlp need to run: the two paths native/ytdlp.c reads, and where Python's
     * libraries, certificates, home and temporary files are, as youtubedl-android sets them.
     */
    private fun environment(zipapp: File): Map<String, String> =
        mapOf(
            "MAYDO_PYTHON" to python.path,
            "MAYDO_YTDLP" to zipapp.path,
            "PYTHONHOME" to usr.path,
            "LD_LIBRARY_PATH" to File(usr, "lib").path,
            "SSL_CERT_FILE" to caFile.path,
            "PYTHONUTF8" to "1",
            "PYTHONIOENCODING" to "utf-8",
            "HOME" to context.filesDir.path,
            "XDG_CONFIG_HOME" to configDir.path,
            "XDG_CACHE_HOME" to context.cacheDir.path,
            "TMPDIR" to context.cacheDir.path,
        )

    /** Unpacks `libpython.zip.so` again whenever the app has been installed or updated. */
    private fun unpackPython() {
        val zip = File(nativeDir, "libpython.zip.so")
        if (!zip.isFile || !python.isFile) throw IOException("Python is missing from the APK")
        val stamp = File(pythonRoot, ".stamp")
        val want =
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (stamp.isFile && stamp.readText() == want) return
        pythonRoot.deleteRecursively()
        unzip(zip, pythonRoot) { name -> SKIPPED.none { name.startsWith(it) } }
        stamp.writeText(want)
    }

    /** Copies the APK's yt-dlp out unless the one in use is as new. */
    private fun installBundledYtdlp() {
        val installed = installedVersion()
        if (installed != null && Versions.compareYtdlp(installed, BuildConfig.YTDLP_VERSION) >= 0) {
            return
        }
        ytdlpDir.mkdirs()
        val part = File(ytdlpDir, "$ASSET.part")
        context.assets.open(ASSET).use { input -> part.outputStream().use { input.copyTo(it) } }
        if (!part.renameTo(script)) throw IOException("cannot replace $script")
        versionFile.writeText(BuildConfig.YTDLP_VERSION)
    }

    private fun unzip(zip: File, target: File, wanted: (String) -> Boolean) {
        val root = target.canonicalPath + File.separator
        ZipFile.builder().setFile(zip).get().use { archive ->
            for (entry in archive.entries) {
                if (!wanted(entry.name)) continue
                val out = File(target, entry.name)
                require(out.canonicalPath.startsWith(root)) {
                    "zip entry outside target: ${entry.name}"
                }
                when {
                    entry.isDirectory -> out.mkdirs()

                    entry.isUnixSymlink -> {
                        out.parentFile?.mkdirs()
                        val link =
                            archive.getInputStream(entry).use { it.readBytes().decodeToString() }
                        Os.symlink(link, out.absolutePath)
                    }

                    else -> {
                        out.parentFile?.mkdirs()
                        archive.getInputStream(entry).use { input ->
                            out.outputStream().use { input.copyTo(it) }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "YtdlpRuntime"

        /** The zipapp's name among yt-dlp's release assets, and in the APK's assets. */
        const val ASSET = "yt-dlp"

        const val HOOK = "ytdl_hook.lua"

        /** What yt-dlp never uses: QuickJS's static library, 2to3 and Python's own tests. */
        val SKIPPED =
            listOf("usr/lib/quickjs/", "usr/lib/python3.12/lib2to3/", "usr/lib/python3.12/test/")
    }
}
