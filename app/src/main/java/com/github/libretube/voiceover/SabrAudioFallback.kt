package com.github.libretube.voiceover

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.github.libretube.api.obj.Streams
import com.github.libretube.player.manifest.Representation
import com.github.libretube.player.manifest.SabrManifest
import com.github.libretube.player.parser.PlaybackRequest
import com.github.libretube.player.parser.SabrClient
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Fetches an original audio track using the same YouTube SABR stream mechanism as
 * RootTube's own player. Direct googlevideo.com stream URLs sometimes redirect
 * or stall even while SABR playback succeeds.
 *
 * Only complete tracks are returned. Partial streams must never be uploaded
 * to the VOT service as if they were full files.
 */
@OptIn(UnstableApi::class)
internal object SabrAudioFallback {
    private const val TAG = "RootTube-VOT"
    private const val MAX_SEGMENTS = 3000
    private const val TIME_BUDGET_MS = 90_000L

    suspend fun fetch(
        videoId: String,
        streams: Streams,
        preferredAudioUrl: String?,
        maxBytes: Int
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (streams.serverAbrStreamingUrl.isNullOrBlank() ||
            streams.videoPlaybackUstreamerConfig.isNullOrBlank()) {
            Log.i(TAG, "sourceAudio SABR unavailable: no streaming config")
            return@withContext null
        }

        val selected = streams.audioStreams
            .filter { track ->
                track.itag != null && track.lastModified != null &&
                    track.contentLength > 0 && track.contentLength <= maxBytes &&
                    track.mimeType == "audio/webm" &&
                    (track.codec?.contains("opus", ignoreCase = true) == true)
            }
            .sortedWith(
                compareBy(
                    { it.url != preferredAudioUrl },
                    { !it.audioTrackLocale.orEmpty().startsWith("en", ignoreCase = true) },
                    { it.contentLength }
                )
            )
            .firstOrNull()

        if (selected == null) {
            Log.i(TAG, "sourceAudio SABR unavailable: no eligible WebM/Opus track")
            return@withContext null
        }

        try {
            val manifest = SabrManifest(videoId, streams)
            val client = SabrClient(manifest)
            val representation = Representation(selected)
            val format = representation.formatId()
            client.selectFormat(representation)
            Log.i(
                TAG,
                "sourceAudio SABR started itag=${selected.itag} bytes=${selected.contentLength}"
            )

            val begin = SystemClock.elapsedRealtime()
            val output = ByteArrayOutputStream()
            fun append(data: List<ByteArray>): Boolean {
                for (fragment in data) {
                    if (fragment.size > maxBytes - output.size()) return false
                    output.write(fragment)
                }
                return true
            }

            val init = client.getNextSegment(
                PlaybackRequest.initRequest(format, 0L, 1.0f)
            ) ?: error("SABR returned no init segment")

            if (!append(init.data)) error("SABR audio exceeds $maxBytes bytes")
            val lastSegment = client.getEndSegmentNumber(format)
                ?: error("SABR did not return the segment count")
            if (lastSegment !in 1L..MAX_SEGMENTS.toLong()) {
                error("SABR segment count invalid: $lastSegment")
            }

            var playbackMs = 0L
            val buffered = ArrayList<Long>()
            for (sequence in 1L..lastSegment) {
                currentCoroutineContext().ensureActive()
                if (SystemClock.elapsedRealtime() - begin > TIME_BUDGET_MS) {
                    error("SABR audio exceeded time budget")
                }

                // Mirror the player's buffered-segment queue. With an empty
                // queue on every request, SABR can keep offering the same
                // initial fragments instead of advancing to the next segment.
                var segment: com.github.libretube.player.parser.Segment? = null
                for (retry in 0..3) {
                    currentCoroutineContext().ensureActive()
                    if (SystemClock.elapsedRealtime() - begin > TIME_BUDGET_MS) {
                        error("SABR audio exceeded time budget")
                    }
                    segment = client.getNextSegment(
                        PlaybackRequest(
                            format = format,
                            playerPosition = playbackMs,
                            playbackSpeed = 1.0f,
                            segment = sequence,
                            segmentStartTimeMs = playbackMs,
                            bufferedSegments = buffered
                        )
                    )
                    if (segment != null) break
                    Log.i(
                        TAG,
                        "sourceAudio SABR segment=$sequence not ready; retry=${retry + 1}"
                    )
                }

                val chunk = segment ?: error("SABR missing segment $sequence after retries")
                if (chunk.sequenceNumber != sequence) {
                    error("SABR segment mismatch: $sequence != ${chunk.sequenceNumber}")
                }
                if (!append(chunk.data)) error("SABR audio exceeds $maxBytes bytes")
                buffered.add(sequence)
                playbackMs = (chunk.header.startMs + chunk.duration)
                    .coerceAtLeast(playbackMs + 1L)
                if (sequence == 1L || sequence % 10L == 0L || sequence == lastSegment) {
                    Log.i(
                        TAG,
                        "sourceAudio SABR progress segment=$sequence/$lastSegment bytes=${output.size()}"
                    )
                }
            }

            val completed = output.toByteArray()
            if (completed.size < 4 ||
                (completed[0].toInt() and 255) != 0x1A ||
                (completed[1].toInt() and 255) != 0x45 ||
                (completed[2].toInt() and 255) != 0xDF ||
                (completed[3].toInt() and 255) != 0xA3) {
                error("SABR audio does not start with a WebM header")
            }
            Log.i(
                TAG,
                "sourceAudio SABR complete bytes=${completed.size} segments=$lastSegment"
            )
            completed
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w(
                TAG,
                "sourceAudio SABR failed: ${error.javaClass.simpleName} " +
                    "${error.message?.take(110)}"
            )
            null
        }
    }
}
