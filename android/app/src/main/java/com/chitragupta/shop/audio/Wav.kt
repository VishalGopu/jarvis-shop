package com.chitragupta.shop.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** The server expects 16 kHz mono 16-bit PCM in a WAV container, base64-encoded (same as the web app). */
object Wav {
    const val SAMPLE_RATE = 16000

    fun wrap(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(SAMPLE_RATE); h.putInt(SAMPLE_RATE * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcm.size)
        out.write(h.array()); out.write(pcm)
        return out.toByteArray()
    }

    fun toBase64(pcm: ByteArray): String = Base64.getEncoder().encodeToString(wrap(pcm))
}
