package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/**
 * Applies the normalization gain ([Loudness], shared with the desktop) through its limiter, so a
 * raised song is never clipped.
 */
@UnstableApi
class VolumeNormalizationAudioProcessor : BaseAudioProcessor() {
    private var encoding = C.ENCODING_INVALID
    private var bytesPerSample = 0
    private var channels = 0
    private var limiter: Loudness.Limiter? = null
    private var frame = DoubleArray(0)

    @Volatile
    var enabled = false
        set(value) {
            if (field != value) {
                field = value
                Timber.tag(TAG).d("Normalization processor enabled: $value")
            }
        }

    @Volatile
    private var targetGainMb = 0

    @Volatile
    private var targetLinear = 1.0

    @Synchronized
    fun setTargetGain(gainMb: Int) {
        if (targetGainMb != gainMb) {
            targetGainMb = gainMb
            targetLinear = Loudness.linear(gainMb / MB_PER_DB)
            limiter?.targetGain = targetLinear
            Timber.tag(TAG).d("Target gain set to $gainMb mB (Linear multiplier: $targetLinear)")
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        encoding = inputAudioFormat.encoding
        bytesPerSample =
            when (encoding) {
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_24BIT -> 3
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
                else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            }
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        frame = DoubleArray(channels)
        limiter = Loudness.Limiter(inputAudioFormat.sampleRate).apply { targetGain = targetLinear }

        Timber.tag(TAG).d(
            "Configured: sampleRate=${inputAudioFormat.sampleRate}, channels=${inputAudioFormat.channelCount}, encoding=$encoding",
        )
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val stage = limiter
        val frameBytes = bytesPerSample * channels
        val output = replaceOutputBuffer(inputBuffer.remaining())
        if (!enabled || stage == null) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)
        while (inputBuffer.remaining() >= frameBytes) {
            for (c in 0 until channels) frame[c] = read(inputBuffer)
            stage.process(frame, channels)
            for (c in 0 until channels) write(output, frame[c])
        }
        // A partial frame cannot happen with PCM, but would pass through untouched.
        if (inputBuffer.hasRemaining()) output.put(inputBuffer)
        output.flip()
    }

    /** One sample at full scale 1.0. */
    private fun read(buffer: ByteBuffer): Double =
        when (encoding) {
            C.ENCODING_PCM_16BIT -> buffer.getShort() / FULL_16
            C.ENCODING_PCM_24BIT -> {
                val b0 = buffer.get().toInt() and 0xFF
                val b1 = buffer.get().toInt() and 0xFF
                val b2 = buffer.get().toInt()
                ((b2 shl 16) or (b1 shl 8) or b0) / FULL_24
            }
            C.ENCODING_PCM_32BIT -> buffer.getInt() / FULL_32
            else -> buffer.getFloat().toDouble()
        }

    private fun write(buffer: ByteBuffer, value: Double) {
        when (encoding) {
            C.ENCODING_PCM_16BIT ->
                buffer.putShort((value * FULL_16).roundToLong().coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt().toShort())
            C.ENCODING_PCM_24BIT -> {
                val sample = (value * FULL_24).roundToLong().coerceIn(MIN_24, MAX_24).toInt()
                buffer.put((sample and 0xFF).toByte())
                buffer.put(((sample shr 8) and 0xFF).toByte())
                buffer.put(((sample shr 16) and 0xFF).toByte())
            }
            C.ENCODING_PCM_32BIT ->
                buffer.putInt((value * FULL_32).roundToLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
            else -> buffer.putFloat(value.toFloat())
        }
    }

    override fun onFlush() {
        limiter?.snap()
    }

    override fun onReset() {
        encoding = C.ENCODING_INVALID
        bytesPerSample = 0
        channels = 0
        limiter = null
    }

    private companion object {
        const val TAG = "VolumeNormalizationProcessor"
        const val MB_PER_DB = 100.0
        const val FULL_16 = 32768.0
        const val FULL_24 = 8388608.0
        const val FULL_32 = 2147483648.0
        const val MIN_24 = -8388608L
        const val MAX_24 = 8388607L
    }
}
