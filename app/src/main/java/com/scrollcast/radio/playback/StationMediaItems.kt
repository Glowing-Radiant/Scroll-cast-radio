package com.scrollcast.radio.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.scrollcast.radio.data.Station

/** Favorites-queue items carry this prefix so the UI can tell which feed the player holds. */
private const val FAVORITES_PREFIX = "fav:"

fun Station.toMediaItem(queue: QueueMode = QueueMode.Feed): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(displayName)
        .setStation(displayName)
        .setArtist(listOf(placeLine, genreLine).filter { it.isNotEmpty() }.joinToString(" — "))
        .setArtworkUri(favicon.takeIf { it.startsWith("http") }?.let(Uri::parse))
        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .build()
    return MediaItem.Builder()
        .setMediaId(if (queue == QueueMode.Favorites) FAVORITES_PREFIX + uuid else uuid)
        .setUri(streamUrl)
        .setMimeType(if (hls == 1) MimeTypes.APPLICATION_M3U8 else null)
        .setMediaMetadata(metadata)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(streamUrl)).build())
        .build()
}

/** The Radio Browser station uuid behind this item. */
val MediaItem.stationId: String get() = mediaId.removePrefix(FAVORITES_PREFIX)

val MediaItem.queue: QueueMode
    get() = if (mediaId.startsWith(FAVORITES_PREFIX)) QueueMode.Favorites else QueueMode.Feed

/** Minimal station rebuilt from a media item, for items whose station is no longer cached. */
fun MediaItem.toFallbackStation(): Station = Station(
    uuid = stationId,
    name = mediaMetadata.title?.toString().orEmpty(),
    url = requestMetadata.mediaUri?.toString() ?: localConfiguration?.uri?.toString().orEmpty(),
)
