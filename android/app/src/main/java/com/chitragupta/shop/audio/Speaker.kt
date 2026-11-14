package com.chitragupta.shop.audio

import android.content.Context
import android.media.MediaPlayer
import android.util.Base64
import java.io.File

/** Plays the base64 WAV the server sends back (Sarvam TTS). No-ops when the server has no TTS. */
class Speaker(private val context: Context) {
    private var player: MediaPlayer? = null

    fun play(base64: String?) {
        if (base64.isNullOrBlank()) return
        stop()
        runCatching {
            val file = File(context.cacheDir, "tts.wav").apply { writeBytes(Base64.decode(base64, Base64.DEFAULT)) }
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { it.release(); if (player === it) player = null }
                prepare(); start()
            }
        }
    }

    fun stop() { player?.runCatching { stop(); release() }; player = null }
}
