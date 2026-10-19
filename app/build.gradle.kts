import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries)
}

// The version comes from the latest `v<versionName>-<versionCode>` tag: v1.2.3-1 builds as
// versionName 1.2.3 and versionCode 1. Commits past the tag keep its versionCode and get git
// describe's suffix in the name (1.2.3-4-gabcdef0, -dirty with uncommitted changes); no tag at
// all is 0.0.0 and 1. The in-app updater compares versionCodes, so bump it on every tag.
val dirtyFlag = if (providers.gradleProperty("maydo.noDirty").isPresent) null else "--dirty"

val gitDescribe: String = runCatching {
    providers
        .exec {
            commandLine(
                listOfNotNull("git", "describe", "--tags", "--match", "v[0-9]*-[0-9]*", dirtyFlag),
            )
            isIgnoreExitValue = true
        }
        .standardOutput
        .asText
        .get()
        .trim()
}
    .getOrDefault("")

val gitVersion = Regex("""^v(\d+\.\d+\.\d+)-(\d+)(.*)$""").find(gitDescribe)?.destructured

val gitVersionName: String = gitVersion?.let { (name, _, rest) -> name + rest } ?: "0.0.0"

val gitVersionCode: Int =
    gitVersion?.let { (_, code, _) ->
        // Android requires a versionCode of at least 1.
        code.toInt().also { require(it >= 1) { "versionCode must be 1 or more: $gitDescribe" } }
    } ?: 1

val ytdlpVersion = providers.gradleProperty("maydo.ytdlp.version").get()
val ytdlpSha256 = providers.gradleProperty("maydo.ytdlp.sha256").get()

/** Every ABI that both libmpv and youtubedl-android's Python ship for, with its clang target. */
val abiTargets =
    mapOf(
        "arm64-v8a" to "aarch64-linux-android",
        "armeabi-v7a" to "armv7a-linux-androideabi",
        "x86" to "i686-linux-android",
        "x86_64" to "x86_64-linux-android",
    )

android {
    namespace = "io.github.eggplants.maydo"
    compileSdk {
        version = release(37)
    }
    // Builds the yt-dlp launcher (native/ytdlp.c); the same NDK as F-Droid uses.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "io.github.eggplants.maydo"
        minSdk = 26
        targetSdk = 37
        versionCode = gitVersionCode
        versionName = gitVersionName

        buildConfigField("String", "YTDLP_VERSION", "\"$ytdlpVersion\"")
        // Where the app looks for its own updates.
        buildConfigField("String", "GITHUB_REPOSITORY", "\"eggplants/maydo\"")
    }

    // One APK per ABI, each a quarter of the universal one, which is built too. All of them
    // share the versionCode: the in-app updater picks the asset for the device's ABI by name.
    splits {
        abi {
            isEnable = true
            reset()
            include(*abiTargets.keys.toTypedArray())
            isUniversalApk = true
        }
    }

    // The release key comes from the environment (the release workflow decodes it from secrets).
    // Without it the release APK is left unsigned.
    val releaseKeystore = providers.environmentVariable("MAYDO_KEYSTORE_FILE").orNull
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("MAYDO_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("MAYDO_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("MAYDO_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // R8 shrinks and obfuscates; see src/main/keepRules for what libmpv calls by name.
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // Lists the languages with values-xx/ for the system's per-app language settings
        // (Android 13+); res/resources.properties names what plain values/ is in.
        generateLocaleConfig = true
    }
    packaging {
        jniLibs {
            // Python, QuickJS and the yt-dlp launcher run as executables out of
            // nativeLibraryDir, so they must be extracted.
            useLegacyPackaging = true
            // A zip named like a library so that it lands next to Python, not an ELF to strip.
            keepDebugSymbols += "**/libpython.zip.so"
        }
    }
}

/**
 * Builds `libytdlp.so`, the executable mpv's ytdl_hook runs as yt-dlp: it starts Python on the
 * yt-dlp zipapp. Named like a library so that the installer puts it in nativeLibraryDir, the one
 * place an app may run programs from.
 */
abstract class BuildYtdlpLauncher : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: RegularFileProperty

    @get:Internal abstract val ndkDirectory: DirectoryProperty

    @get:Input abstract val ndkVersion: Property<String>

    @get:Input abstract val targets: MapProperty<String, String>

    @get:Input abstract val minSdk: Property<Int>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun build() {
        val os = System.getProperty("os.name").lowercase()
        val host =
            when {
                "windows" in os -> "windows-x86_64"
                "mac" in os -> "darwin-x86_64"
                else -> "linux-x86_64"
            }
        val clang =
            ndkDirectory
                .file(
                    "toolchains/llvm/prebuilt/$host/bin/clang" +
                        if ("windows" in os) {
                            ".exe"
                        } else {
                            ""
                        },
                )
                .get()
                .asFile
        if (!clang.canExecute()) {
            throw GradleException(
                "No clang at $clang: install the NDK, sdkmanager \"ndk;${ndkVersion.get()}\"",
            )
        }
        for ((abi, triple) in targets.get()) {
            val out = outputDir.file("$abi/libytdlp.so").get().asFile
            out.parentFile.mkdirs()
            exec.exec {
                commandLine(
                    clang.absolutePath,
                    "--target=$triple${minSdk.get()}",
                    "-O2",
                    "-Wall",
                    "-Werror",
                    "-fPIE",
                    "-pie",
                    "-s",
                    // 16 KB page devices refuse anything aligned to less.
                    "-Wl,-z,max-page-size=16384",
                    "-o",
                    out.absolutePath,
                    source.get().asFile.absolutePath,
                )
            }
        }
    }
}

/** Puts the yt-dlp zipapp of [version] into the assets, checked against its SHA-256 [digest]. */
abstract class DownloadYtdlp : DefaultTask() {
    @get:Input abstract val version: Property<String>

    @get:Input abstract val digest: Property<String>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        val url = "https://github.com/yt-dlp/yt-dlp/releases/download/${version.get()}/yt-dlp"
        val out = outputDir.file("yt-dlp").get().asFile
        val sha = MessageDigest.getInstance("SHA-256")
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw GradleException("$url: HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        sha.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val actual = sha.digest().joinToString("") { "%02x".format(it) }
        if (actual != digest.get()) {
            out.delete()
            throw GradleException("$url: SHA-256 is $actual, not ${digest.get()}")
        }
    }
}

// The license list of the Maven dependencies, for the about screen. Offline, so that the build
// reads nothing but the POMs and gives the same list every time.
aboutLibraries {
    offlineMode = true
}

androidComponents {
    val ndkDirectory = sdkComponents.ndkDirectory
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val launcher =
            tasks.register<BuildYtdlpLauncher>("build${name}YtdlpLauncher") {
                source.set(rootProject.file("native/ytdlp.c"))
                this.ndkDirectory.set(ndkDirectory)
                ndkVersion.set(android.ndkVersion)
                targets.set(abiTargets)
                minSdk.set(variant.minSdk.apiLevel)
            }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            launcher,
            BuildYtdlpLauncher::outputDir,
        )
        val ytdlp =
            tasks.register<DownloadYtdlp>("download${name}Ytdlp") {
                version.set(ytdlpVersion)
                digest.set(ytdlpSha256)
            }
        variant.sources.assets?.addGeneratedSourceDirectory(ytdlp, DownloadYtdlp::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    // libmpv's MPVLib uses coroutines without declaring them in its POM.
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.commons.compress)
    // libmpv with its JNI wrapper (is.xyz.mpv), the same build mpvEx uses.
    implementation(libs.mpv.android)
    // Only the prebuilt binaries: Python (libpython.so and libpython.zip.so) and QuickJS
    // (libqjs.so), which solves YouTube's JavaScript challenges. Maydo runs them itself.
    implementation(libs.youtubedl.android.library) { isTransitive = false }
    // The license screen for the Maven dependencies.
    implementation(libs.aboutlibraries.compose.m3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
