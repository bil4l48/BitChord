package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * In-app "Volume Boost": multiplies the decoded PCM by a fixed gain so the
 * app's own level can exceed the 100% the sink would otherwise be capped at,
 * leaving the Android system stream volume untouched.
 *
 * ## Why this exists
 *
 * There is no in-app gain stage in the pipeline — the volume row on the Now
 * Playing screen writes straight to `STREAM_MUSIC`, and `player.volume` is
 * reserved for [CrossfadeController]'s 0..1 automation. A hardware-limited
 * phone speaker can be quieter than the material it is playing, and the only
 * knobs the OS offers are "system volume" (already maxed in that case) and a
 * per-route loudness enhancer that is not under this app's control. Boosting
 * here, inside ExoPlayer's own processor chain, is the one place nothing else
 * in the stack can intercept or veto it.
 *
 * ## Headroom and clipping
 *
 * A boost above 100% over-drives signals that are already near full scale; that
 * is the point, and it cannot happen without some limiting. Rather than a hard
 * square clipper, samples past [KNEE] glide asymptotically toward 1.0 on a
 * slope that is continuous with the linear region — the classic rational soft
 * clipper. Below the knee the gain is perfectly linear, so quiet material is
 * boosted cleanly and only the peaks get gently reshaped. When the boost is
 * off, or set to exactly 100%, the buffer is copied through untouched and the
 * output is bit-for-bit identical to the unprocessed chain.
 *
 * ## Interaction with the rest of the chain
 *
 * 16-bit PCM only, and always active (rather than ON/OFF at configure time)
 * for that format, so toggling the setting takes effect on the very next
 * buffer instead of at the next track boundary — the media3 sink only re-runs
 * [onConfigure] on a format change or flush, so a processor that bowed out
 * there could not honour a mid-track flip. Same reason [TransitionFilterProcessor]
 * and [SpatialAudioProcessor] keep their flags `@Volatile` and check them per
 * buffer.
 *
 * Like [SpatialAudioProcessor], this is bypassed entirely when the sink is in
 * PCM_FLOAT mode, because media3's float path does not run the user processor
 * chain at all; that route is the opt-in USB-DAC format, where the hardware is
 * already the one setting the headroom.
 *
 * The gain is decoded from a percentage and clamped to [MAX_GAIN] (500%), so
 * a level beyond the slider's range — a restored backup, a hand-edited pref —
 * can never overdrive the arithmetic.
 */
@UnstableApi
class VolumeBoostAudioProcessor : BaseAudioProcessor() {

    /** Whether boosting is switched on. Volatile; read per buffer, see class doc. */
    @Volatile
    var enabled: Boolean = false

    /** Linear gain, always in [1.0, [MAX_GAIN]]. Volatile; read per buffer. */
    @Volatile
    var gain: Float = 1f
        set(value) {
            field = value.coerceIn(1f, MAX_GAIN)
        }

    /** Applies a rounded level, e.g. `setBoost(true, 200)` for 2.0x. */
    fun setBoost(enabled: Boolean, boostPercent: Int) {
        this.enabled = enabled
        gain = boostPercent / 100f
    }

    private var frameBytes = 0

    // Public rather than the base's protected so the processor can be
    // configured from tests as well as by the sink.
    public override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount < 1) {
            // Same contract as the other processors: bow out with NOT_SET rather
            // than throw, or the whole sink fails — see SpatialAudioProcessor's
            // class doc for the failure mode it cures.
            return AudioProcessor.AudioFormat.NOT_SET
        }
        frameBytes = 2 * inputAudioFormat.channelCount
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        if (frameBytes == 0) return
        val byteCount = inputBuffer.remaining()
        val frameCount = byteCount / frameBytes
        if (frameCount == 0) return
        val outputBuffer = replaceOutputBuffer(byteCount)

        val boosting = enabled && gain > 1f
        if (!boosting) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())
        val sampleGain = gain
        val samples = frameCount * (frameBytes / 2)
        val input = inputBuffer
        val output = outputBuffer
        repeat(samples) {
            output.putShort(softLimited(input.short * sampleGain))
        }
        outputBuffer.flip()
    }

    /** Samples up to [KNEE] (in normalized ±1.0) are scaled linearly; past it,
     *  they ease asymptotically toward full scale with a continuous slope. */
    private fun softLimited(normalizedBoosted: Float): Short {
        val x = normalizedBoosted / Short.MAX_VALUE
        val a = abs(x)
        val limited = if (a <= KNEE) {
            x
        } else {
            val t = (a - KNEE) / (1f - KNEE)
            val y = KNEE + (1f - KNEE) * t / (1f + t)
            if (x >= 0f) y else -y
        }
        return (limited * Short.MAX_VALUE)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }

    private companion object {
        /** 90% of full scale: below this, boosting is untouched linear gain. */
        const val KNEE = 0.9f

        /** 500%. A theoretical ceiling, not a safety valve — see class doc. */
        const val MAX_GAIN = 5f
    }
}