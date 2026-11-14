package com.chitragupta.shop

import com.chitragupta.shop.audio.Wav
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The server (src/audio.js) rejects anything that isn't 16 kHz mono 16-bit PCM WAV, so pin our header to that. */
class WavTest {
    private fun sample(seconds: Double): ByteArray {
        val pcm = ByteArray((Wav.SAMPLE_RATE * 2 * seconds).toInt())
        for (i in 0 until pcm.size / 2) { val v = (kotlin.math.sin(i / 20.0) * 8000).toInt(); pcm[2 * i] = v.toByte(); pcm[2 * i + 1] = (v shr 8).toByte() }
        return Wav.wrap(pcm)
    }

    @Test fun headerMatchesWhatTheServerValidates() {
        val b = ByteBuffer.wrap(sample(1.0)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(b.array(), 0, 4)); assertEquals("WAVE", String(b.array(), 8, 4)); assertEquals("fmt ", String(b.array(), 12, 4))
        assertEquals(1, b.getShort(20).toInt())        // PCM
        assertEquals(1, b.getShort(22).toInt())        // mono
        assertEquals(16000, b.getInt(24))              // sample rate
        assertEquals(16, b.getShort(34).toInt())       // bits
        assertEquals("data", String(b.array(), 36, 4))
        assertEquals(32000, b.getInt(40))              // 1 s of data
        assertEquals(b.capacity() - 44, b.getInt(40))
    }

    /** Written out so a node script can run the server's real wavDuration() on it (see the PR description). */
    @Test fun writeSamplesForServerCheck() {
        val dir = File("build/wav-samples").apply { mkdirs() }
        File(dir, "one-second.wav").writeBytes(sample(1.0))
        File(dir, "too-short.wav").writeBytes(sample(0.1))
    }
}
