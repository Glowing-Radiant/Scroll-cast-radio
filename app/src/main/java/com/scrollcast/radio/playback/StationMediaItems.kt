package com.scrollcast.radio.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.scrollcast.radio.data.Station

fun Station.toMediaItem(): MediaItem {
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
        .setMediaId(uuid)
        .setUri(streamUrl)
        .setMimeType(if (hls == 1) MimeTypes.APPLICATION_M3U8 else null)
        .setMediaMetadata(metadata)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(streamUrl)).build())
        .build()
}

/** Minimal station rebuilt from a media item, for items whose station is no longer cached. */
fun MediaItem.toFallbackStation(): Station = Station(
    uuid = mediaId,
    name = mediaMetadata.title?.toString().orEmpty(),
    url = requestMetadata.mediaUri?.toString() ?: localConfiguration?.uri?.toString().orEmpty(),
)
