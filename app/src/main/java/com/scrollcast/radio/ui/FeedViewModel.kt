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
import androidx.media3.common.C
import com.scrollcast.radio.playback.PlaybackService
import com.scrollcast.radio.playback.QueueMode
import com.scrollcast.radio.playback.queue
import com.scrollcast.radio.playback.stationId
import com.scrollcast.radio.playback.toFallbackStation
import com.scrollcast.radio.playback.toMediaItem
import com.scrollcast.radio.update.UpdateInfo
import com.scrollcast.radio.update.UpdateState
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
    /** Which feed the player currently holds. */
    val queue: QueueMode = QueueMode.Feed,
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

    /** The typed mood currently shaping the feed, if any. */
    val mood: StateFlow<String?> = graph.settings.feed
        .map { it.mood }
        .stateIn(viewModelScope, SharingStarted.Eagerly, graph.settings.feed.value.mood)

    val update: StateFlow<UpdateState> = graph.updater.state
    val updatesEnabled: Boolean = graph.updater.isEnabled
    val versionName: String = BuildConfig.VERSION_NAME

    /** The update prompt was closed with "Later"; don't reopen it this session. */
    private val _updatePromptDismissed = MutableStateFlow(false)
    val updatePromptDismissed: StateFlow<Boolean> = _updatePromptDismissed.asStateFlow()

    /** Which feed the player holds; the Favorites tab switches it. */
    val queueMode: StateFlow<QueueMode> = graph.queue.mode

    private var announcedId: String? = null
    /** Spoken before the next station's summary, e.g. "Favorites." after switching tabs. */
    private var announcePrefix = ""

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)

        override fun onPlayerError(error: PlaybackException) {
            announcePrefix = "Previous station didn't respond. "
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

        if (updatesEnabled) viewModelScope.launch {
            graph.updater.check()
            (graph.updater.state.value as? UpdateState.Available)?.let {
                announce("An update to version ${it.info.version} is available.")
            }
        }
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
            graph.feed.cached(item.stationId) ?: item.toFallbackStation()
        }
        val state = PlayerUiState(
            stations = stations,
            currentIndex = p.currentMediaItemIndex.coerceIn(0, (stations.size - 1).coerceAtLeast(0)),
            isPlaying = p.isPlaying,
            isBuffering = p.playbackState == Player.STATE_BUFFERING,
            hasError = p.playerError != null,
            queue = p.currentMediaItem?.queue ?: QueueMode.Feed,
        )
        _player.value = state

        val current = state.current ?: return
        if (current.uuid != announcedId) {
            announcedId = current.uuid
            announce(announcePrefix + current.spokenSummary)
            announcePrefix = ""
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
        } else if (graph.queue.mode.value == QueueMode.Favorites) {
            announce("This is the last favorite.")
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
        val existing = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).stationId == station.uuid }
        val index = existing ?: run {
            val at = if (c.mediaItemCount == 0) 0 else c.currentMediaItemIndex + 1
            c.addMediaItem(at, station.toMediaItem(graph.queue.mode.value))
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

    // --- Feed / Favorites switching -----------------------------------------------------------

    /**
     * Makes the favorites the player's playlist, parking the discovery feed. Swipes, headset
     * and lock-screen buttons then move through favorites. Starts on the playing station if
     * it's a favorite.
     */
    fun showFavorites() = withController { c ->
        val queue = graph.queue
        val favorites = graph.favorites.stations.value
        if (queue.mode.value == QueueMode.Favorites || favorites.isEmpty()) return@withController
        queue.parkedFeed = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).stationId }
        queue.parkedIndex = c.currentMediaItemIndex
        queue.mode.value = QueueMode.Favorites

        favorites.forEach(graph.feed::remember)
        val currentId = c.currentMediaItem?.stationId
        val start = favorites.indexOfFirst { it.uuid == currentId }.coerceAtLeast(0)
        val keepPlaying = c.playWhenReady || c.mediaItemCount == 0
        announcedId = null
        announcePrefix = "Favorites, ${favorites.size} ${if (favorites.size == 1) "station" else "stations"}. "
        c.setMediaItems(favorites.map { it.toMediaItem(QueueMode.Favorites) }, start, C.TIME_UNSET)
        c.prepare()
        c.playWhenReady = keepPlaying
    }

    /** Returns the player to the discovery feed, where it was left. */
    fun showFeed() = withController { c ->
        val queue = graph.queue
        if (queue.mode.value == QueueMode.Feed) return@withController
        queue.mode.value = QueueMode.Feed
        val items = queue.parkedFeed.mapNotNull { id -> graph.feed.cached(id)?.toMediaItem() }
        val keepPlaying = c.playWhenReady
        announcedId = null
        announcePrefix = "Feed. "
        if (items.isEmpty()) {
            c.clearMediaItems() // the service notices the empty feed and loads a fresh one
        } else {
            c.setMediaItems(items, queue.parkedIndex.coerceIn(0, items.size - 1), C.TIME_UNSET)
            c.prepare()
            c.playWhenReady = keepPlaying
        }
        queue.parkedFeed = emptyList()
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

    // --- Mood ---------------------------------------------------------------------------------

    /** Rebuilds the feed around a typed mood; blank or null clears it. */
    fun setMood(text: String?) {
        val mood = text?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }
        val current = graph.settings.feed.value
        if (mood == current.mood) {
            graph.feed.requestMore()
            return
        }
        announcedId = null
        graph.settings.setFeed(current.copy(mood = mood))
        _draft.value = _draft.value.copy(mood = mood)
        announce(if (mood == null) "Mood cleared. Back to your usual feed." else "Finding stations for $mood.")
    }

    // --- Updates ------------------------------------------------------------------------------

    fun checkForUpdates() {
        _updatePromptDismissed.value = false
        viewModelScope.launch {
            graph.updater.check()
            when (val s = graph.updater.state.value) {
                is UpdateState.Available -> announce("Version ${s.info.version} is available.")
                UpdateState.UpToDate -> announce("You have the latest version.")
                is UpdateState.Failed -> announce(s.message)
                else -> Unit
            }
        }
    }

    fun installUpdate(info: UpdateInfo) {
        announce("Downloading the update.")
        viewModelScope.launch {
            graph.updater.downloadAndInstall(info)
            (graph.updater.state.value as? UpdateState.Failed)?.let { announce(it.message) }
        }
    }

    fun dismissUpdate() {
        _updatePromptDismissed.value = true
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
