package com.scrollcast.radio.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.scrollcast.radio.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A newer release published on GitHub. */
data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState
    /** The APK is handed to Android; the system installer asks the user to confirm. */
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Self-update from GitHub Releases: the release workflow attaches a signed APK to each
 * `vX.Y.Z` tag, and this checks the latest one and installs it through [PackageInstaller].
 */
class AppUpdater(
    private val context: Context,
    private val http: OkHttpClient,
    private val json: Json,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Debug builds have their own package and are updated from the computer, not GitHub. */
    val isEnabled: Boolean get() = !BuildConfig.DEBUG

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tag: String = "",
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(
        val name: String = "",
        val size: Long = 0,
        @SerialName("browser_download_url") val url: String = "",
    )

    suspend fun check() {
        val current = _state.value
        if (current is UpdateState.Checking || current is UpdateState.Downloading || current is UpdateState.Installing) return
        _state.value = UpdateState.Checking
        _state.value = try {
            val release = withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (response.code == 404) return@withContext null // no releases yet
                    if (!response.isSuccessful) throw IOException("GitHub returned ${response.code}")
                    json.decodeFromString(Release.serializer(), response.body.string())
                }
            }
            val apk = release?.assets?.firstOrNull { it.name.endsWith(".apk") }
            val version = release?.tag?.removePrefix("v")
            if (release == null || apk == null || version == null || release.draft || release.prerelease ||
                !Versions.isNewer(version, BuildConfig.VERSION_NAME)
            ) {
                UpdateState.UpToDate
            } else {
                UpdateState.Available(UpdateInfo(version, release.body.orEmpty().trim(), apk.url, apk.size))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            UpdateState.Failed("Couldn't check for updates. Check your connection.")
        }
    }

    fun dismiss() {
        if (_state.value !is UpdateState.Downloading) _state.value = UpdateState.Idle
    }

    /** Streams the APK straight into an install session, then asks Android to install it. */
    suspend fun downloadAndInstall(info: UpdateInfo) {
        _state.value = UpdateState.Downloading(info, 0)
        val installer = context.packageManager.packageInstaller
        var sessionId = -1
        try {
            withContext(Dispatchers.IO) {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(context.packageName) }
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    val request = Request.Builder().url(info.apkUrl).build()
                    http.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Download failed (${response.code})")
                        val total = response.body.contentLength().takeIf { it > 0 } ?: info.apkSize
                        session.openWrite("base.apk", 0, total).use { out ->
                            response.body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                var written = 0L
                                var lastPercent = -1
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    out.write(buffer, 0, read)
                                    written += read
                                    val percent = if (total > 0) (written * 100 / total).toInt() else 0
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        _state.value = UpdateState.Downloading(info, percent)
                                    }
                                }
                            }
                            session.fsync(out)
                        }
                    }
                    val callback = PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        Intent(context, UpdateResultReceiver::class.java),
                        // Mutable: the installer adds the result extras to this intent.
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    session.commit(callback.intentSender)
                }
            }
            _state.value = UpdateState.Installing
        } catch (e: Exception) {
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.value = UpdateState.Failed("The update couldn't be downloaded. Please try again.")
        }
    }

    internal fun onInstallFailed(message: String) {
        _state.value = UpdateState.Failed(message)
    }
}

object Versions {
    /** True if [candidate] (e.g. "0.3.0") is a higher version than [current] (e.g. "0.2.1"). */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(version: String): List<Int> =
        version.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
}
