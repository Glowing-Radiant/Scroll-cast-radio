package com.scrollcast.radio

import android.app.Application
import android.content.Context
import com.scrollcast.radio.data.Catalog
import com.scrollcast.radio.data.FavoritesStore
import com.scrollcast.radio.data.FeedRepository
import com.scrollcast.radio.data.RadioBrowserClient
import com.scrollcast.radio.data.RegionDetector
import com.scrollcast.radio.data.SettingsStore
import com.scrollcast.radio.playback.AudioEffects
import com.scrollcast.radio.playback.QueueState
import com.scrollcast.radio.update.AppUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ScrollCastApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}

val Context.appGraph: AppGraph get() = (applicationContext as ScrollCastApp).graph

/** Process-wide singletons shared by the UI and the playback service. */
class AppGraph(context: Context) {
    private val scope = CoroutineScope(SupervisorJob())

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()

    private val api = RadioBrowserClient(http, json)
    val settings = SettingsStore(context)
    val region = RegionDetector(context)
    val catalog = Catalog(api)
    val feed = FeedRepository(api, catalog, settings, region)
    val favorites = FavoritesStore(context, json, scope)
    val updater = AppUpdater(context, http, json)
    val queue = QueueState()
    val audioEffects = AudioEffects(settings, scope)

    companion object {
        val USER_AGENT = "ScrollCastRadio/${BuildConfig.VERSION_NAME}"
    }
}
