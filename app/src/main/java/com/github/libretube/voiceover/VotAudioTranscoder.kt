package com.github.libretube.voiceover

import android.media.AudioFormat
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

            val activeDecoder = MediaCodec.createDecoderByType(mime)
            decoder = activeDecoder
            val activeEncoder = MediaCodec.createEncoderByType(OPUS)
            encoder = activeEncoder
            val opusFormat = MediaFormat.createAudioFormat(OPUS, rate, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 1) 48_000 else 64_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            }
            activeDecoder.configure(sourceFormat, null, null, 0)
            activeEncoder.configure(opusFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            activeDecoder.start()
            activeEncoder.start()
            val outputMuxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            )
            muxer = outputMuxer

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
                    val index = activeDecoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = activeDecoder.getInputBuffer(index)
                            ?: error("Decoder input buffer missing")
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            activeDecoder.queueInputBuffer(
                                index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            decoderInputEos = true
                        } else {
                            activeDecoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        progressed = true
                    }
                }

                if (!decoderOutputEos && pendingPcm == null) {
                    val index = activeDecoder.dequeueOutputBuffer(decodedInfo, TIMEOUT_US)
                    if (index >= 0) {
                        val decoded = activeDecoder.getOutputBuffer(index)
                        if (decodedInfo.size > 0 && decoded != null) {
                            decoded.position(decodedInfo.offset)
                            decoded.limit(decodedInfo.offset + decodedInfo.size)
                            pendingPcm = ByteArray(decodedInfo.size).also { decoded.get(it) }
                            pendingOffset = 0
                            pendingTimeUs = decodedInfo.presentationTimeUs
                        }
                        decoderOutputEos = decodedInfo.flags and
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        activeDecoder.releaseOutputBuffer(index, false)
                        progressed = true
                    } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val actualFormat = activeDecoder.outputFormat
                        val actualRate = actualFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val actualChannels = actualFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (actualRate != rate || actualChannels != channels) {
                            error("Decoder changed PCM layout: $actualRate/$actualChannels")
                        }
                        if (actualFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            actualFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) !=
                            AudioFormat.ENCODING_PCM_16BIT) {
                            error("Decoder produced non-16-bit PCM")
                        }
                        progressed = true
                    }
                }

                val pending = pendingPcm
                if (pending != null || (decoderOutputEos && !encoderInputEos)) {
                    val index = activeEncoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = activeEncoder.getInputBuffer(index)
                            ?: error("Encoder input buffer missing")
                        buffer.clear()
                        if (pending != null) {
                            val length = minOf(buffer.remaining(), pending.size - pendingOffset)
                            if (length <= 0) error("Encoder input capacity is zero")
                            buffer.put(pending, pendingOffset, length)
                            val bytesPerFrame = channels * 2
                            val timeUs = pendingTimeUs +
                                (pendingOffset / bytesPerFrame) * 1_000_000L / rate
                            activeEncoder.queueInputBuffer(index, 0, length, timeUs, 0)
                            pendingOffset += length
                            if (pendingOffset == pending.size) pendingPcm = null
                        } else {
                            activeEncoder.queueInputBuffer(
                                index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            encoderInputEos = true
                        }
                        progressed = true
                    }
                }

                // Always drain the encoder, even if its input is momentarily full.
                val encodedIndex = activeEncoder.dequeueOutputBuffer(encodedInfo, TIMEOUT_US)
                if (encodedIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(!muxerStarted) { "Unexpected second audio output format" }
                    track = outputMuxer.addTrack(activeEncoder.outputFormat)
                    outputMuxer.start()
                    muxerStarted = true
                    progressed = true
                } else if (encodedIndex >= 0) {
                    val encoded = activeEncoder.getOutputBuffer(encodedIndex)
                    if (encodedInfo.size > 0 &&
                        encodedInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        check(muxerStarted && track >= 0 && encoded != null) {
                            "Encoder output has no WebM track"
                        }
                        outputMuxer.writeSampleData(track, encoded, encodedInfo)
                        if (outputFile.length() > maxBytes) {
                            error("Transcoded WebM exceeds upload size limit")
                        }
                    }
                    encoderOutputEos = encodedInfo.flags and
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    activeEncoder.releaseOutputBuffer(encodedIndex, false)
                    progressed = true
                }

                if (progressed) idleIterations = 0 else idleIterations++
                check(idleIterations < 150) { "Audio encoder stalled" }
            }

            outputMuxer.stop()
            muxerStarted = false
            val bytes = outputFile.readBytes()
            if (bytes.size < 4 || bytes.size > maxBytes ||
                (bytes[0].toInt() and 255) != 0x1A ||
                (bytes[1].toInt() and 255) != 0x45 ||
                (bytes[2].toInt() and 255) != 0xDF ||
                (bytes[3].toInt() and 255) != 0xA3) {
                return null
            }
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
