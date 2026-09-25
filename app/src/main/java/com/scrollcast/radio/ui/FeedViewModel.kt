package com.scrollcast.radio.ui

import android.app.Application
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.scrollcast.radio.BuildConfig
import com.scrollcast.radio.appGraph
import com.scrollcast.radio.data.CatalogEntry
import com.scrollcast.radio.data.FeedPrefs
import com.scrollcast.radio.data.ShareLinks
import com.scrollcast.radio.data.Station
import com.scrollcast.radio.playback.PlaybackService
import com.scrollcast.radio.playback.toFallbackStation
import com.scrollcast.radio.playback.toMediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlayerUiState(
    val stations: List<Station> = emptyList(),
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val hasError: Boolean = false,
) {
    val current: Station? get() = stations.getOrNull(currentIndex)
}

/** A message for the screen reader; [id] changes so repeated text is still spoken. */
data class Announcement(val text: String = "", val id: Long = 0)

class FeedViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app.appGraph
    private var controller: MediaController? = null
    private val pendingActions = mutableListOf<(MediaController) -> Unit>()

    private val _player = MutableStateFlow(PlayerUiState())
    val player: StateFlow<PlayerUiState> = _player.asStateFlow()

    private val _announcement = MutableStateFlow(Announcement())
    val announcement: StateFlow<Announcement> = _announcement.asStateFlow()

    val favorites: StateFlow<List<Station>> = graph.favorites.stations
    val favoriteIds: StateFlow<Set<String>> = graph.favorites.stations
        .map { list -> list.mapTo(HashSet()) { it.uuid } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val feedLoading: StateFlow<Boolean> = graph.feed.loading
    val feedError: StateFlow<String?> = graph.feed.error

    /** Settings being edited; they only reach the feed when the settings screen closes. */
    private val _draft = MutableStateFlow(graph.settings.feed.value)
    val draft: StateFlow<FeedPrefs> = _draft.asStateFlow()

    /** Detected country code and device language name, shown as the "Automatic" choices. */
    val detectedCountry: String? = graph.region.countryCode()
    private val _detectedLanguage = MutableStateFlow<String?>(null)
    val detectedLanguage: StateFlow<String?> = _detectedLanguage.asStateFlow()

    private val _options = MutableStateFlow<Map<OptionKind, OptionsState>>(emptyMap())
    val options: StateFlow<Map<OptionKind, OptionsState>> = _options.asStateFlow()

    private var announcedId: String? = null
    private var skippedDeadStation = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)

        override fun onPlayerError(error: PlaybackException) {
            skippedDeadStation = true
            val c = controller ?: return
            if (c.mediaItemCount <= 1) announce("This station isn't responding. Swipe for another.")
        }
    }

    init {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            sync(c)
            pendingActions.forEach { it(c) }
            pendingActions.clear()
        }, ContextCompat.getMainExecutor(app))
    }

    override fun onCleared() {
        controller?.run {
            removeListener(listener)
            release()
        }
        controller = null
    }

    private fun withController(action: (MediaController) -> Unit) {
        controller?.let(action) ?: pendingActions.add(action)
    }

    private fun sync(p: Player) {
        val stations = (0 until p.mediaItemCount).map { i ->
            val item = p.getMediaItemAt(i)
            graph.feed.cached(item.mediaId) ?: item.toFallbackStation()
        }
        val state = PlayerUiState(
            stations = stations,
            currentIndex = p.currentMediaItemIndex.coerceIn(0, (stations.size - 1).coerceAtLeast(0)),
            isPlaying = p.isPlaying,
            isBuffering = p.playbackState == Player.STATE_BUFFERING,
            hasError = p.playerError != null,
        )
        _player.value = state

        val current = state.current ?: return
        if (current.uuid != announcedId) {
            announcedId = current.uuid
            val prefix = if (skippedDeadStation) "Previous station didn't respond. " else ""
            skippedDeadStation = false
            announce(prefix + current.spokenSummary)
        }
    }

    private fun announce(text: String) {
        _announcement.value = Announcement(text, _announcement.value.id + 1)
    }

    private fun MediaController.resume() {
        if (playbackState == Player.STATE_IDLE || playerError != null) prepare()
        play()
    }

    // --- Feed navigation -------------------------------------------------------------------

    /** Called when the pager comes to rest on [page] after a swipe. */
    fun onPageSettled(page: Int) = withController { c ->
        if (page in 0 until c.mediaItemCount && page != c.currentMediaItemIndex) {
            c.seekToDefaultPosition(page)
            c.resume()
        }
    }

    fun next() = withController { c ->
        if (c.hasNextMediaItem()) {
            c.seekToNextMediaItem()
            c.resume()
        } else {
            announce("Loading more stations.")
            graph.feed.requestMore()
        }
    }

    fun previous() = withController { c ->
        if (c.hasPreviousMediaItem()) {
            c.seekToPreviousMediaItem()
            c.resume()
        } else {
            announce("This is the first station.")
        }
    }

    fun togglePlay() = withController { c ->
        if (c.isPlaying) c.pause() else c.resume()
    }

    fun retry() = graph.feed.requestMore()

    /** Puts [station] right after the current one and starts it (favorites, shared links). */
    fun play(station: Station) = withController { c ->
        graph.feed.remember(station)
        val existing = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == station.uuid }
        val index = existing ?: run {
            val at = if (c.mediaItemCount == 0) 0 else c.currentMediaItemIndex + 1
            c.addMediaItem(at, station.toMediaItem())
            at
        }
        c.seekToDefaultPosition(index)
        c.resume()
    }

    fun openSharedStation(uuid: String) {
        viewModelScope.launch {
            val station = graph.feed.stationById(uuid)
            if (station == null) announce("Couldn't find the shared station.") else play(station)
        }
    }

    // --- Favorites, sharing, filters ---------------------------------------------------------

    fun toggleFavorite(station: Station) {
        val added = graph.favorites.toggle(station)
        announce(if (added) "Added ${station.displayName} to favorites." else "Removed ${station.displayName} from favorites.")
    }

    fun removeFavorite(station: Station) {
        graph.favorites.remove(station.uuid)
        announce("Removed ${station.displayName} from favorites.")
    }

    fun appShareText(station: Station): String = ShareLinks.appShareText(BuildConfig.SHARE_BASE_URL, station)

    fun directShareText(station: Station): String = ShareLinks.directShareText(station)

    fun startEditingSettings() {
        _draft.value = graph.settings.feed.value
        viewModelScope.launch {
            _detectedLanguage.value = graph.catalog.languageForIso(graph.region.languageCode())
        }
    }

    fun editDraft(change: (FeedPrefs) -> FeedPrefs) {
        _draft.value = change(_draft.value)
    }

    /** Saves the draft; the playback service rebuilds the feed when settings change. */
    fun commitSettings() {
        val next = _draft.value
        if (next == graph.settings.feed.value) return
        announcedId = null
        graph.settings.setFeed(next)
        announce("Settings saved. Loading a new feed.")
    }

    fun loadOptions(kind: OptionKind) {
        val current = _options.value[kind]
        if (current is OptionsState.Loaded || current is OptionsState.Loading) return
        _options.value += kind to OptionsState.Loading
        viewModelScope.launch {
            val result = runCatching {
                when (kind) {
                    OptionKind.Region -> graph.catalog.countries()
                    OptionKind.Language -> graph.catalog.languages()
                    OptionKind.Genre -> graph.catalog.genres()
                }
            }
            _options.value += kind to result.fold({ OptionsState.Loaded(it) }, { OptionsState.Failed })
        }
    }

    fun retryOptions(kind: OptionKind) {
        _options.value -= kind
        loadOptions(kind)
    }
}

enum class OptionKind { Region, Language, Genre }

sealed interface OptionsState {
    data object Loading : OptionsState
    data object Failed : OptionsState
    data class Loaded(val entries: List<CatalogEntry>) : OptionsState
}
