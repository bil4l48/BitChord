package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Volume Boost is a gain stage in the audio path, so its two promises are what
 * need pinning down: that turning it off (or leaving it at 100%) changes
 * nothing, byte for byte, and that turning it on never pushes a sample past
 * where the arithmetic can represent it — a boost that ever output `+32768`
 * would alias, and that would be louder in the worst way.
 */
@OptIn(UnstableApi::class)
class VolumeBoostAudioProcessorTest {

    private fun configuredProcessor(): VolumeBoostAudioProcessor {
        val processor = VolumeBoostAudioProcessor()
        processor.onConfigure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        return processor
    }

    private fun bufferOf(vararg samples: Short): ByteBuffer {
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach(bytes::putShort)
        bytes.flip()
        return bytes
    }

    private fun outputs(processor: VolumeBoostAudioProcessor): List<Int> {
        val out = processor.getOutput()
        out.order(ByteOrder.nativeOrder())
        val values = mutableListOf<Int>()
        while (out.hasRemaining()) values.add(out.short.toInt())
        return values
    }

    @Test
    fun `boost off hands the buffer through unchanged`() {
        val processor = configuredProcessor()
        val input = bufferOf(1000, -2000, 30000, -30000)
        processor.queueInput(input)
        assertEquals(listOf(1000, -2000, 30000, -30000), outputs(processor))
    }

    @Test
    fun `100 percent exactly is also untouched`() {
        val processor = configuredProcessor().apply { setBoost(true, 100) }
        val input = bufferOf(1000, -2000, 30000, -30000)
        processor.queueInput(input)
        assertEquals(listOf(1000, -2000, 30000, -30000), outputs(processor))
    }

    @Test
    fun `quiet samples are scaled exactly linearly`() {
        val processor = configuredProcessor().apply { setBoost(true, 200) }
        processor.queueInput(bufferOf(10000, -10000))
        assertEquals(listOf(20000, -20000), outputs(processor))
    }

    @Test
    fun `loud samples are softly limited rather than hard clipped`() {
        // 30000 at 2x lands at 1.83x full scale: a hard clipper would sit on
        // +32767; the rational soft clipper pulls it asymptotically under that,
        // so the peak keeps some shape instead of squaring off.
        val processor = configuredProcessor().apply { setBoost(true, 200) }
        processor.queueInput(bufferOf(30000, -30000))

        val (positive, negative) = outputs(processor)
        assertTrue("peak was hard-clipped flat at 32767", positive < 32767)
        assertTrue("boosted peak lost nearly all its gain", positive > 32000)
        assertEquals("soft clip must stay symmetric", negative, -positive)
    }

    @Test
    fun `excessive level is clamped to the 500 percent ceiling`() {
        val processor = VolumeBoostAudioProcessor()
        processor.setBoost(true, 900)
        assertEquals(5f, processor.gain, 0f)
    }

    @Test
    fun `mono stream gets the same gain`() {
        val processor = VolumeBoostAudioProcessor()
        processor.onConfigure(AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_16BIT))
        processor.setBoost(true, 150)
        processor.queueInput(bufferOf(4000, -4000))
        assertEquals(listOf(6000, -6000), outputs(processor))
    }

    @Test
    fun `unsupported encoding bows out instead of throwing`() {
        val processor = VolumeBoostAudioProcessor()
        val format = processor.onConfigure(
            AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_24BIT),
        )
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, format)
    }
}