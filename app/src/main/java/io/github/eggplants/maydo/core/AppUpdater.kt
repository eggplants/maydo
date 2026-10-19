package io.github.eggplants.maydo.core

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.eggplants.maydo.BuildConfig
import java.net.HttpURLConnection
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** A newer release of the app and the APK in it for this device. */
data class AppUpdate(
    val release: Release,
    val apk: ReleaseAsset,
    val versionName: String,
    val versionCode: Int,
)

sealed interface InstallResult {
    /** Rarely seen: Android stops the app to replace it. */
    data object Success : InstallResult

    /** The user said no to the system's dialog. */
    data object Cancelled : InstallResult

    /** Maydo may not install apps yet; the user has to allow it in the system settings. */
    data object NeedsPermission : InstallResult

    data class Failure(val message: String) : InstallResult
}

/**
 * Updates the app from its GitHub releases (`v<versionName>-<versionCode>` tags).
 *
 * The APK goes straight from the download into a [PackageInstaller] session, never into a file: the
 * session is where Android keeps it until it installs. Committing hands over to the system, which
 * asks the user and then replaces the app, stopping it on the way.
 */
class AppUpdater(private val context: Context) {
    /** The newest release if it is newer than this build, else null; null with none at all. */
    suspend fun check(): AppUpdate? {
        val release =
            try {
                GitHub.latestRelease(BuildConfig.GITHUB_REPOSITORY)
            } catch (e: HttpStatusException) {
                if (e.code == HttpURLConnection.HTTP_NOT_FOUND) return null
                throw e
            }
        val (name, code) = Versions.appTag(release.tagName) ?: return null
        if (code <= BuildConfig.VERSION_CODE) return null
        val apkName =
            Versions.pickApk(release.assets.map { it.name }, Build.SUPPORTED_ABIS.toList())
                ?: return null
        return AppUpdate(release, release.assets.first { it.name == apkName }, name, code)
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /**
     * Streams [update]'s APK into a session and commits it. Suspends until Android has an answer;
     * [onProgress] gets the bytes downloaded so far and the total.
     */
    suspend fun install(update: AppUpdate, onProgress: (Long, Long) -> Unit): InstallResult {
        if (!canInstall()) return InstallResult.NeedsPermission
        val installer = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(update.apk.size)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
                }
            }
        val sessionId = installer.createSession(params)
        try {
            withContext(Dispatchers.IO) {
                installer.openSession(sessionId).use { session ->
                    session.openWrite("base.apk", 0, update.apk.size).use { out ->
                        GitHub.download(update.apk, out, onProgress)
                        session.fsync(out)
                    }
                }
            }
        } catch (e: Throwable) {
            // Cancellation too: a session left behind holds the APK until the system expires it.
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
        return commit(installer, sessionId)
    }

    private suspend fun commit(installer: PackageInstaller, sessionId: Int): InstallResult =
        suspendCancellableCoroutine { cont ->
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.getIntExtra(EXTRA_SESSION_ID, -1) != sessionId) return
                        val status =
                            intent.getIntExtra(
                                PackageInstaller.EXTRA_STATUS,
                                PackageInstaller.STATUS_FAILURE,
                            )
                        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        Log.d(TAG, "session $sessionId: status $status, $message")
                        val result =
                            when (status) {
                                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                    // The system's dialog; the result comes in another broadcast.
                                    IntentCompat.getParcelableExtra(
                                            intent,
                                            Intent.EXTRA_INTENT,
                                            Intent::class.java,
                                        )
                                        ?.let {
                                            context.startActivity(
                                                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        }
                                    return
                                }

                                PackageInstaller.STATUS_SUCCESS -> InstallResult.Success

                                PackageInstaller.STATUS_FAILURE_ABORTED -> InstallResult.Cancelled

                                else -> InstallResult.Failure(message ?: "status $status")
                            }
                        runCatching { this@AppUpdater.context.unregisterReceiver(this) }
                        if (cont.isActive) cont.resume(result)
                    }
                }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(ACTION_INSTALL_STATUS),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            cont.invokeOnCancellation {
                runCatching { context.unregisterReceiver(receiver) }
                runCatching { installer.abandonSession(sessionId) }
            }
            val status =
                PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(ACTION_INSTALL_STATUS)
                        .setPackage(context.packageName)
                        .putExtra(EXTRA_SESSION_ID, sessionId),
                    // Mutable: the system adds the status to it.
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
            try {
                installer.openSession(sessionId).use { it.commit(status.intentSender) }
            } catch (e: Exception) {
                runCatching { context.unregisterReceiver(receiver) }
                runCatching { installer.abandonSession(sessionId) }
                cont.resume(InstallResult.Failure(e.message ?: e.toString()))
            }
        }

    private companion object {
        const val TAG = "AppUpdater"
        const val ACTION_INSTALL_STATUS = "${BuildConfig.APPLICATION_ID}.INSTALL_STATUS"
        const val EXTRA_SESSION_ID = "session_id"
    }
}
