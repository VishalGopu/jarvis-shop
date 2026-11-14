package com.chitragupta.shop.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** Push-to-talk style recorder. start() begins capture, stop() returns base64 WAV (or null if too short). */
class Recorder {
    private var job: Job? = null
    private var record: AudioRecord? = null
    private val buffer = ByteArrayOutputStream()
    @Volatile private var recording = false
    /** 0f..1f live level for the waveform. */
    @Volatile var level = 0f; private set

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO first
    fun start(scope: CoroutineScope): Boolean {
        if (recording) return true
        val min = AudioRecord.getMinBufferSize(Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Wav.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        buffer.reset(); record = rec; recording = true; rec.startRecording()
        job = scope.launch(Dispatchers.IO) {
            val chunk = ByteArray(min)
            while (recording) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) {
                    buffer.write(chunk, 0, n)
                    var peak = 0
                    for (i in 0 until n - 1 step 2) peak = maxOf(peak, abs(((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xff)).toShort().toInt()))
                    level = (peak / 12000f).coerceIn(0f, 1f)
                }
            }
        }
        return true
    }

    /** Stops and returns base64 WAV, or null when under ~0.4 s (an accidental tap). */
    suspend fun stop(): String? {
        if (!recording) return null
        recording = false
        job?.join()
        record?.runCatching { stop(); release() }
        record = null; level = 0f
        val pcm = buffer.toByteArray()
        return if (pcm.size < Wav.SAMPLE_RATE * 2 * 0.4) null else Wav.toBase64(pcm)
    }

    val isRecording get() = recording
}
