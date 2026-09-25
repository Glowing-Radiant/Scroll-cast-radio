package com.scrollcast.radio.playback

import kotlinx.coroutines.flow.MutableStateFlow

/** Which list the player is playing through: the discovery feed or the favorites feed. */
enum class QueueMode { Feed, Favorites }

/**
 * Shared between the UI and the playback service. While favorites are playing, the discovery
 * feed is parked here (station ids and position) so switching back resumes where it was.
 */
class QueueState {
    val mode = MutableStateFlow(QueueMode.Feed)

    @Volatile var parkedFeed: List<String> = emptyList()
    @Volatile var parkedIndex: Int = 0

    /** True while something (the update screen) needs silence: nothing may start playing. */
    val hold = MutableStateFlow(false)
}
