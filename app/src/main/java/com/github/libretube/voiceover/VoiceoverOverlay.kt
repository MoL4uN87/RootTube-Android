package com.github.libretube.voiceover

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Plays a Russian speech track over the EXISTING RootTube Media3 player.
 *
 * The video source, metadata, background media session, seek/skip controls, and
 * playback state all remain owned by RootTube. Adapted sync concept from
 * VOT Player (MIT, Copyright 2026 Alexandr Motologa).
 */
internal class VoiceoverOverlay(
    context: Context,
    private val main: ExoPlayer,
    scope: CoroutineScope
) {
    private val contextForSettings = context.applicationContext
    private val originalVolume = main.volume
    private val dub = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            false
        )
        volume = 1f
    }
    private var closed = false

    private val syncJob: Job = scope.launch(Dispatchers.Main) {
        while (isActive && !closed) {
            if (main.isPlaying && main.playbackState == Player.STATE_READY) {
                if (dub.playbackState == Player.STATE_READY) {
                    val delta = main.currentPosition - dub.currentPosition
                    val speed = main.playbackParameters.speed
                    when {
                        abs(delta) > 1200L -> {
                            dub.seekTo(main.currentPosition)
                            if (dub.playbackParameters.speed != speed) {
                                dub.playbackParameters = PlaybackParameters(speed)
                            }
                        }
                        abs(delta) > 220L -> {
                            val adjustment = if (delta > 0) 1.04f else 0.96f
                            val corrected = (speed * adjustment).coerceIn(0.25f, 2f)
                            if (dub.playbackParameters.speed != corrected) {
                                dub.playbackParameters = PlaybackParameters(corrected)
                            }
                        }
                        dub.playbackParameters.speed != speed ->
                            dub.playbackParameters = PlaybackParameters(speed)
                    }
                    if (!dub.isPlaying) dub.play()
                }
            } else if (dub.isPlaying) {
                dub.pause()
            }
            delay(250L)
        }
    }

    fun play(url: String) {
        if (closed) return
        dub.setMediaItem(MediaItem.fromUri(url))
        dub.prepare()
        dub.seekTo(main.currentPosition)
        setOriginalGain(VoiceoverVolumeSettings.original(contextForSettings))
        setTranslatedGain(VoiceoverVolumeSettings.translated(contextForSettings))
    }

    /**
     * Original sound is attenuated only while the translated track is attached.
     * A gain of zero fully mutes the source, without affecting the dub.
     */
    fun setOriginalGain(gain: Float) {
        if (closed) return
        if (!gain.isFinite()) return
        main.volume = originalVolume * gain.coerceIn(0f, 1f)
    }

    fun setTranslatedGain(gain: Float) {
        if (closed) return
        if (!gain.isFinite()) return
        dub.volume = gain.coerceIn(0f, 1f)
    }

    fun release() {
        if (closed) return
        closed = true
        syncJob.cancel()
        main.volume = originalVolume
        dub.stop()
        dub.release()
    }
}
