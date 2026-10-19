package io.github.eggplants.maydo.core

import io.github.eggplants.maydo.BuildConfig
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Release(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    val assets: List<ReleaseAsset> = emptyList(),
)

@Serializable
data class ReleaseAsset(
    val name: String,
    val size: Long,
    @SerialName("browser_download_url") val downloadUrl: String,
    /** `sha256:<hex>`, which GitHub computes for every asset uploaded since mid-2025. */
    val digest: String? = null,
) {
    val sha256: String?
        get() = digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
}

class DigestMismatchException(name: String) : IOException("$name: SHA-256 mismatch")

/** GitHub's releases API, without a token: 60 requests an hour per address is plenty here. */
object GitHub {
    private val json = Json { ignoreUnknownKeys = true }

    private const val USER_AGENT = "Maydo/${BuildConfig.VERSION_NAME}"

    /**
     * The newest release of [repository] (`owner/name`) that is neither a draft nor a prerelease.
     */
    suspend fun latestRelease(repository: String): Release =
        withContext(Dispatchers.IO) {
            val connection =
                open("https://api.github.com/repos/$repository/releases/latest").apply {
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
            try {
                connection.ensureOk()
                json.decodeFromString(
                    Release.serializer(),
                    connection.inputStream.use { it.readBytes().decodeToString() },
                )
            } finally {
                connection.disconnect()
            }
        }

    /**
     * Streams [asset] into [output] and checks it against the digest GitHub gives for it, if any.
     * Throws [DigestMismatchException] after the last byte when it differs, so that the caller
     * throws away what it wrote. [onProgress] gets the bytes written so far and the total.
     */
    suspend fun download(
        asset: ReleaseAsset,
        output: OutputStream,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ) =
        withContext(Dispatchers.IO) {
            val connection = open(asset.downloadUrl)
            try {
                connection.ensureOk()
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: asset.size
                val sha = MessageDigest.getInstance("SHA-256")
                var written = 0L
                onProgress(0, total)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        sha.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                        written += n
                        onProgress(written, total)
                    }
                }
                val expected = asset.sha256
                if (expected != null && !sha.digest().toHex().equals(expected, ignoreCase = true)) {
                    throw DigestMismatchException(asset.name)
                }
            } finally {
                connection.disconnect()
            }
        }

    private fun open(url: String): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            // github.com redirects asset downloads to its storage host, both https.
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }

    private fun HttpURLConnection.ensureOk() {
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw IOException("$url: HTTP $responseCode $responseMessage")
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
