package com.scrollcast.radio.playback

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Plays TV channels and other video streams for their sound only, with as little data as the
 * stream allows.
 *
 * Every station starts with video tracks switched off, so streams that carry their sound
 * separately (most DASH, some HLS) download audio alone. Many HLS channels instead pack the
 * sound inside each video variant; there the player can't pick a variant with video switched
 * off and takes the first one listed, often full HD. When a station turns out to be like that,
 * video is allowed at the lowest quality for that station. Nothing is ever decoded or drawn:
 * the only video renderer is [DiscardingVideoRenderer], which drops the frames.
 */
internal class AudioOnly(private val player: ExoPlayer) : AnalyticsListener, Player.Listener {
    private val soundOnly: TrackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        .setTrackTypeDisabled(C.TRACK_TYPE_IMAGE, true)
        .build()

    /**
     * Video allowed, but only the smallest variant: no variant meets these limits, and the
     * default track selector then falls back to the lowest one.
     */
    private val lowestVideo: TrackSelectionParameters = soundOnly.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
        .setMaxVideoSize(1, 1)
        .setMaxVideoBitrate(1)
        .build()

    fun attach() {
        player.trackSelectionParameters = soundOnly
        player.addListener(this)
        player.addAnalyticsListener(this)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (player.trackSelectionParameters != soundOnly) player.trackSelectionParameters = soundOnly
    }

    override fun onDownstreamFormatChanged(eventTime: AnalyticsListener.EventTime, mediaLoadData: MediaLoadData) {
        // Muxed variants load as one "default" track that carries video as well as audio.
        if (mediaLoadData.trackType != C.TRACK_TYPE_DEFAULT) return
        val format = mediaLoadData.trackFormat ?: return
        val hasVideo = format.height > 0 || MimeTypes.getVideoMediaMimeType(format.codecs) != null
        if (hasVideo && player.trackSelectionParameters == soundOnly) player.trackSelectionParameters = lowestVideo
    }

    /** The app never shows video, so the only video renderer is one that throws frames away. */
    class RenderersFactory(context: Context) : DefaultRenderersFactory(context) {
        override fun buildVideoRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            eventHandler: Handler,
            eventListener: VideoRendererEventListener,
            allowedVideoJoiningTimeMs: Long,
            out: ArrayList<Renderer>,
        ) {
            out.add(DiscardingVideoRenderer())
        }
    }

    /**
     * Accepts any video track and drops its frames unread, so no decoder is started. Without it
     * the stock renderer decodes into a hidden placeholder surface, spending battery on nothing.
     */
    private class DiscardingVideoRenderer : BaseRenderer(C.TRACK_TYPE_VIDEO) {
        private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED)
        private var ended = false

        override fun getName() = "DiscardingVideoRenderer"

        override fun supportsFormat(format: Format): Int = RendererCapabilities.create(
            if (MimeTypes.isVideo(format.sampleMimeType)) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE,
        )

        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            // Take everything that has arrived; nothing is ever shown, so there is no need to wait.
            while (!ended) {
                buffer.clear()
                when (readSource(formatHolder, buffer, SampleStream.FLAG_OMIT_SAMPLE_DATA)) {
                    C.RESULT_FORMAT_READ -> Unit
                    C.RESULT_BUFFER_READ -> if (buffer.isEndOfStream) ended = true
                    else -> return
                }
            }
        }

        override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamWasReset: Boolean) {
            ended = false
        }

        override fun isReady() = true

        override fun isEnded() = ended
    }
}
