package io.github.eggplants.maydo.core

import android.util.Log
import io.github.eggplants.maydo.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a check for, or an install of, an update is. */
sealed interface UpdateStep {
    data object Idle : UpdateStep

    data object Checking : UpdateStep

    /** [done] and [total] in bytes. */
    data class Downloading(val done: Long, val total: Long) : UpdateStep {
        val fraction: Float
            get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    /** The system's install dialog is up, or Android is installing. */
    data object Installing : UpdateStep

    data class Failed(val message: String) : UpdateStep
}

data class UpdateState(
    /** The newest yt-dlp release, once looked up. */
    val ytdlpLatest: Release? = null,
    val ytdlpStep: UpdateStep = UpdateStep.Idle,
    /** A release of the app newer than this one, once looked up. */
    val app: AppUpdate? = null,
    /** Whether the app's releases have been looked at, so that null [app] means up to date. */
    val appChecked: Boolean = false,
    val appStep: UpdateStep = UpdateStep.Idle,
    /** The app may not install updates until the user allows it. */
    val appNeedsPermission: Boolean = false,
)

/**
 * Updates of yt-dlp and of the app itself, shared by the home screen (which mentions them) and the
 * settings (which do them). Lives as long as the process, so that a download goes on when the
 * screen that started it is left.
 */
class UpdateManager(
    private val runtime: YtdlpRuntime,
    private val appUpdater: AppUpdater,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var ytdlpJob: Job? = null
    private var appJob: Job? = null

    /** Whether [Release] is newer than the yt-dlp in use. */
    fun isNewerYtdlp(release: Release?): Boolean {
        val installed = runtime.installedVersion() ?: return release != null
        return release != null && Versions.compareYtdlp(release.tagName, installed) > 0
    }

    /** Looks up the newest releases of both, or of yt-dlp alone without the in-app updater. */
    fun checkAll() {
        checkYtdlp()
        if (BuildConfig.SELF_UPDATE) checkApp()
    }

    fun checkYtdlp() {
        if (ytdlpJob?.isActive == true) return
        ytdlpJob = scope.launch {
            _state.update { it.copy(ytdlpStep = UpdateStep.Checking) }
            val step =
                runStep("checking yt-dlp") {
                    val release = GitHub.latestRelease(YTDLP_REPOSITORY)
                    _state.update { it.copy(ytdlpLatest = release) }
                }
            _state.update { it.copy(ytdlpStep = step) }
        }
    }

    fun updateYtdlp() {
        if (ytdlpJob?.isActive == true) return
        ytdlpJob = scope.launch {
            _state.update { it.copy(ytdlpStep = UpdateStep.Checking) }
            val step =
                runStep("updating yt-dlp") {
                    val release = _state.value.ytdlpLatest ?: GitHub.latestRelease(YTDLP_REPOSITORY)
                    _state.update { it.copy(ytdlpLatest = release) }
                    runtime.update(release) { done, total ->
                        _state.update {
                            it.copy(ytdlpStep = UpdateStep.Downloading(done, total))
                        }
                    }
                }
            _state.update { it.copy(ytdlpStep = step) }
        }
    }

    fun checkApp() {
        if (appJob?.isActive == true) return
        appJob = scope.launch {
            _state.update { it.copy(appStep = UpdateStep.Checking) }
            val step =
                runStep("checking the app") {
                    val update = appUpdater.check()
                    _state.update { it.copy(app = update, appChecked = true) }
                }
            _state.update { it.copy(appStep = step) }
        }
    }

    fun installApp() {
        val update = _state.value.app ?: return
        if (appJob?.isActive == true) return
        appJob = scope.launch {
            _state.update {
                it.copy(appStep = UpdateStep.Downloading(0, update.apk.size))
            }
            var result: InstallResult? = null
            val step =
                runStep("installing the app") {
                    result =
                        appUpdater.install(update) { done, total ->
                            _state.update {
                                it.copy(
                                    appStep =
                                        if (done < total) {
                                            UpdateStep.Downloading(done, total)
                                        } else {
                                            UpdateStep.Installing
                                        },
                                )
                            }
                        }
                }
            _state.update {
                when (val r = result) {
                    is InstallResult.Failure -> it.copy(appStep = UpdateStep.Failed(r.message))
                    InstallResult.NeedsPermission ->
                        it.copy(appStep = UpdateStep.Idle, appNeedsPermission = true)
                    else -> it.copy(appStep = step, appNeedsPermission = false)
                }
            }
        }
    }

    /** The user has been to the system settings; whether they allowed installs is asked again. */
    fun permissionHandled() {
        _state.update { it.copy(appNeedsPermission = false) }
    }

    private suspend fun runStep(what: String, block: suspend () -> Unit): UpdateStep =
        try {
            block()
            UpdateStep.Idle
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed", e)
            UpdateStep.Failed(e.message ?: e.toString())
        }

    private companion object {
        const val TAG = "UpdateManager"
        const val YTDLP_REPOSITORY = "yt-dlp/yt-dlp"
    }
}
