package com.scrollcast.radio.playback

import android.app.PendingIntent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.scrollcast.radio.AppGraph
import com.scrollcast.radio.appGraph
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Owns the player. The player's playlist *is* the feed: swiping, headset buttons and the
 * lock screen all move through the same list, and the service keeps it topped up.
 */
class PlaybackService : MediaSessionService() {

    private val scope = MainScope()
    private val graph: AppGraph get() = appGraph
    private var session: MediaSession? = null
    private var loadJob: Job? = null
    private var consecutiveErrors = 0
    private val clicked = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(AppGraph.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(PlayerEvents(player))

        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .apply { openApp?.let(::setSessionActivity) }
            .build()

        scope.launch { graph.settings.feed.drop(1).collect { resetFeed(player) } }
        scope.launch { graph.feed.moreRequests.collect { ensureAhead(player, force = true) } }
        ensureAhead(player)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Appends a batch when fewer than [PREFETCH_AHEAD] stations are queued after the current one. */
    private fun ensureAhead(player: Player, force: Boolean = false) {
        // The favorites feed is a fixed list; only the discovery feed grows.
        if (graph.queue.mode.value != QueueMode.Feed) return
        if (loadJob?.isActive == true) return
        val remaining = player.mediaItemCount - player.currentMediaItemIndex - 1
        if (!force && remaining >= PREFETCH_AHEAD) return
        loadJob = scope.launch {
            val batch = graph.feed.nextBatch()
            if (batch.isEmpty()) return@launch
            if (graph.queue.mode.value != QueueMode.Feed) {
                // Switched to favorites while loading: keep the batch for when the feed returns.
                graph.queue.parkedFeed = graph.queue.parkedFeed + batch.map { it.uuid }
                return@launch
            }
            val wasEmpty = player.mediaItemCount == 0
            player.addMediaItems(batch.map { it.toMediaItem() })
            if (wasEmpty) {
                player.prepare()
                player.play()
            }
        }
    }

    private fun resetFeed(player: Player) {
        loadJob?.cancel()
        graph.feed.resetServed()
        if (graph.queue.mode.value == QueueMode.Favorites) {
            // Leave the favorites playing; the feed rebuilds when the user goes back to it.
            graph.queue.parkedFeed = emptyList()
            graph.queue.parkedIndex = 0
            return
        }
        player.clearMediaItems()
        ensureAhead(player)
    }

    private inner class PlayerEvents(private val player: Player) : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = ensureAhead(player)

        override fun onTimelineChanged(timeline: Timeline, reason: Int) = ensureAhead(player)

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_READY) return
            consecutiveErrors = 0
            val id = player.currentMediaItem?.stationId ?: return
            if (clicked.add(id)) scope.launch { graph.feed.countClick(id) }
        }

        /** A dead stream is dropped from the feed and the next station starts. */
        override fun onPlayerError(error: PlaybackException) {
            consecutiveErrors++
            if (consecutiveErrors > MAX_AUTO_SKIPS || player.mediaItemCount <= 1) return
            player.removeMediaItem(player.currentMediaItemIndex)
            player.prepare()
            player.play()
        }
    }

    /** Controllers can't send stream URIs across the session, so rebuild items from the cache. */
    private inner class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { item ->
                graph.feed.cached(item.stationId)?.toMediaItem(item.queue)
                    ?: item.buildUpon().setUri(item.requestMetadata.mediaUri).build()
            }.toMutableList()
        )
    }

    private companion object {
        const val PREFETCH_AHEAD = 5
        const val MAX_AUTO_SKIPS = 5
    }
}
