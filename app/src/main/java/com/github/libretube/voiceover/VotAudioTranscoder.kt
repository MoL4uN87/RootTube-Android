package com.github.libretube.voiceover

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Converts a fully downloaded AAC/M4A source into WebM/Opus using Android's
 * native decoders/encoders. Never runs on URLs or on already compatible WebM.
 *
 * Supports 48 kHz mono/stereo input (the common YouTube audio rate).
 * Unsupported sample rates/codecs simply return null; do not send a corrupt
 * or mislabeled audio file to VOT.
 */
internal object VotAudioTranscoder {
    private const val TAG = "RootTube-VOT"
    private const val OPUS = "audio/opus"
    private const val TIMEOUT_US = 10_000L

    fun convertToWebmOpus(input: ByteArray, cacheDir: File, maxBytes: Int): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || input.isEmpty()) return null

        val sourceFile = File.createTempFile("roottube-vot-source-", ".bin", cacheDir)
        val outputFile = File.createTempFile("roottube-vot-opus-", ".webm", cacheDir)
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            sourceFile.writeBytes(input)
            extractor.setDataSource(sourceFile.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val sourceFormat = extractor.getTrackFormat(trackIndex)
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val rate = sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (rate != 48_000 || channels !in 1..2) {
                Log.i(TAG, "transcode unsupported: sampleRate=$rate channels=$channels")
                return null
            }
            Log.i(TAG, "transcode source mime=$mime sampleRate=$rate channels=$channels")
            extractor.selectTrack(trackIndex)

            decoder = MediaCodec.createDecoderByType(mime)
            encoder = MediaCodec.createEncoderByType(OPUS)
            val opusFormat = MediaFormat.createAudioFormat(OPUS, rate, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 1) 48_000 else 64_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            }
            decoder.configure(sourceFormat, null, null, 0)
            encoder.configure(opusFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            decoder.start()
            encoder.start()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)

            val decodedInfo = MediaCodec.BufferInfo()
            val encodedInfo = MediaCodec.BufferInfo()
            var decoderInputEos = false
            var decoderOutputEos = false
            var encoderInputEos = false
            var encoderOutputEos = false
            var pendingPcm: ByteArray? = null
            var pendingOffset = 0
            var pendingTimeUs = 0L
            var track = -1
            var idleIterations = 0

            while (!encoderOutputEos) {
                var progressed = false

                if (!decoderInputEos) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)
                            ?: error("Decoder input buffer missing")
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            decoderInputEos = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        progressed = true
                    }
                }

                if (!decoderOutputEos && pendingPcm == null) {
                    val index = decoder.dequeueOutputBuffer(decodedInfo, TIMEOUT_US)
                    if (index >= 0) {
                        val decoded = decoder.getOutputBuffer(index)
                        if (decodedInfo.size > 0 && decoded != null) {
                            decoded.position(decodedInfo.offset)
                            decoded.limit(decodedInfo.offset + decodedInfo.size)
                            pendingPcm = ByteArray(decodedInfo.size).also { decoded.get(it) }
                            pendingOffset = 0
                            pendingTimeUs = decodedInfo.presentationTimeUs
                        }
                        decoderOutputEos = decodedInfo.flags and
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(index, false)
                        progressed = true
                    } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val actualFormat = decoder.outputFormat
                        val actualRate = actualFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val actualChannels = actualFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (actualRate != rate || actualChannels != channels) {
                            error("Decoder changed PCM layout: $actualRate/$actualChannels")
                        }
                        progressed = true
                    }
                }

                val pending = pendingPcm
                if (pending != null || (decoderOutputEos && !encoderInputEos)) {
                    val index = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = encoder.getInputBuffer(index)
                            ?: error("Encoder input buffer missing")
                        buffer.clear()
                        if (pending != null) {
                            val length = minOf(buffer.remaining(), pending.size - pendingOffset)
                            if (length <= 0) error("Encoder input capacity is zero")
                            buffer.put(pending, pendingOffset, length)
                            val bytesPerFrame = channels * 2
                            val timeUs = pendingTimeUs +
                                (pendingOffset / bytesPerFrame) * 1_000_000L / rate
                            encoder.queueInputBuffer(index, 0, length, timeUs, 0)
                            pendingOffset += length
                            if (pendingOffset == pending.size) pendingPcm = null
                        } else {
                            encoder.queueInputBuffer(
                                index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            encoderInputEos = true
                        }
                        progressed = true
                    }
                }

                // Always drain the encoder, even if its input is momentarily full.
                val encodedIndex = encoder.dequeueOutputBuffer(encodedInfo, TIMEOUT_US)
                if (encodedIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(!muxerStarted) { "Unexpected second audio output format" }
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                    progressed = true
                } else if (encodedIndex >= 0) {
                    val encoded = encoder.getOutputBuffer(encodedIndex)
                    if (encodedInfo.size > 0 &&
                        encodedInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        check(muxerStarted && track >= 0 && encoded != null) {
                            "Encoder output has no WebM track"
                        }
                        muxer.writeSampleData(track, encoded, encodedInfo)
                        if (outputFile.length() > maxBytes) {
                            error("Transcoded WebM exceeds upload size limit")
                        }
                    }
                    encoderOutputEos = encodedInfo.flags and
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(encodedIndex, false)
                    progressed = true
                }

                if (progressed) idleIterations = 0 else idleIterations++
                check(idleIterations < 150) { "Audio encoder stalled" }
            }

            muxer.stop()
            muxerStarted = false
            val bytes = outputFile.readBytes()
            if (bytes.isEmpty() || bytes.size > maxBytes) return null
            Log.i(TAG, "transcode complete: originalBytes=${input.size} webmBytes=${bytes.size}")
            return bytes
        } catch (error: Exception) {
            Log.w(TAG, "transcode failed: ${error.javaClass.simpleName}: ${error.message?.take(100)}")
            return null
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { extractor.release() }
            sourceFile.delete()
            outputFile.delete()
        }
    }
}
