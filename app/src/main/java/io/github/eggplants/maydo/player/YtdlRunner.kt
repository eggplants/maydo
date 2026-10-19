package io.github.eggplants.maydo.player

import android.util.Log
import `is`.xyz.mpv.MPVLib
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What `assets/ytdl_hook.lua` asks to run: yt-dlp's command line, launcher first. */
@Serializable data class YtdlRequest(val id: String, val args: List<String>)

/** The answer, in the form of mpv's subprocess command, which ytdl_hook reads. */
@Serializable
data class YtdlResult(
    val status: Int,
    val stdout: String = "",
    val stderr: String = "",
    @SerialName("error_string") val errorString: String = "",
    @SerialName("killed_by_us") val killedByUs: Boolean = false,
)

/**
 * Runs yt-dlp for ytdl_hook, which cannot: mpv's subprocess command breaks inside an Android app
 * (see the head of `assets/ytdl_hook.lua`). The hook sets `user-data/maydo/ytdl-request`, and the
 * result goes back as the script message `maydo-ytdl-result`.
 */
class YtdlRunner(
    private val launcher: File,
    private val environment: Map<String, String>,
    private val scope: CoroutineScope,
) {
    private val running = ConcurrentHashMap<String, Process>()
    private val killed = ConcurrentHashMap.newKeySet<String>()

    /**
     * Takes the value of `user-data/maydo/ytdl-request`, which mpv gives as a JSON string: the
     * property holds a node, and a node read as a string comes out as JSON.
     */
    fun request(value: String) {
        val request =
            runCatching {
                val json =
                    if (value.startsWith('"')) JSON.decodeFromString<String>(value) else value
                JSON.decodeFromString(YtdlRequest.serializer(), json)
            }
                .getOrNull() ?: return
        scope.launch(Dispatchers.IO) {
            val result = run(request)
            val answer = JSON.encodeToString(YtdlResult.serializer(), result)
            runCatching { MPVLib.command("script-message", MESSAGE, request.id, answer) }
        }
    }

    /** Stops every yt-dlp still running, e.g. when another file is opened. */
    fun cancelAll() {
        for ((id, process) in running) {
            killed += id
            process.destroy()
        }
    }

    private fun run(request: YtdlRequest): YtdlResult {
        // Only ever the launcher: the request comes through a property anything in mpv can set.
        if (request.args.firstOrNull() != launcher.path) {
            Log.w(TAG, "refused to run ${request.args.firstOrNull()}")
            return YtdlResult(status = -3, errorString = "init")
        }
        val process =
            try {
                ProcessBuilder(request.args)
                    .apply { environment().putAll(this@YtdlRunner.environment) }
                    .start()
            } catch (e: Exception) {
                Log.w(TAG, "yt-dlp did not start", e)
                return YtdlResult(status = -3, errorString = "init", stderr = e.toString())
            }
        running[request.id] = process
        try {
            process.outputStream.close()
            // Both at once, so that neither pipe fills up while the other is read.
            var stderr = ""
            val errReader =
                thread(name = "yt-dlp stderr") {
                    stderr = process.errorStream.use { it.readBytes().decodeToString() }
                }
            val stdout = process.inputStream.use { it.readBytes().decodeToString() }
            errReader.join()
            val status = process.waitFor()
            val wasKilled = killed.remove(request.id)
            return YtdlResult(
                status = status,
                stdout = stdout,
                stderr = stderr,
                errorString = if (wasKilled) "killed" else "",
                killedByUs = wasKilled,
            )
        } finally {
            running.remove(request.id)
        }
    }

    companion object {
        const val REQUEST_PROPERTY = "user-data/maydo/ytdl-request"
        private const val MESSAGE = "maydo-ytdl-result"
        private const val TAG = "YtdlRunner"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
