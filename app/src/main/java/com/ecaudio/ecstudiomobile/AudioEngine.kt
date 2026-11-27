package com.ecaudio.ecstudiomobile

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MediaPlayer
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

class AudioEngine {
    private var recorder: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val recording = AtomicBoolean(false)
    private var player: MediaPlayer? = null
    @Volatile private var bytesWritten = 0L

    fun startRecording(file: File): Boolean {
        if (recording.get()) return false
        val sampleRate = 44100
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return false
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer * 2, 4096)
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return false
        }
        recorder = audioRecord
        bytesWritten = 0
        recording.set(true)
        audioRecord.startRecording()
        recordingThread = Thread {
            RandomAccessFile(file, "rw").use { out ->
                out.setLength(0)
                out.write(ByteArray(44))
                val buffer = ByteArray(8192)
                while (recording.get()) {
                    val count = audioRecord.read(buffer, 0, buffer.size)
                    if (count > 0) {
                        out.write(buffer, 0, count)
                        bytesWritten += count
                    }
                }
                writeWavHeader(out, bytesWritten, sampleRate, 1, 16)
            }
        }.apply { name = "ECStudio-AudioRecord"; start() }
        return true
    }

    fun stopRecording() {
        if (!recording.getAndSet(false)) return
        try { recorder?.stop() } catch (_: Exception) { }
        recordingThread?.join(1500)
        recorder?.release()
        recorder = null
        recordingThread = null
    }

    fun play(file: File, onComplete: () -> Unit = {}) {
        stopPlayback()
        if (!file.exists()) return
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { stopPlayback(); onComplete() }
            setOnErrorListener { _, _, _ -> stopPlayback(); onComplete(); true }
            prepare()
            start()
        }
    }

    fun stopPlayback() {
        try { player?.stop() } catch (_: Exception) { }
        player?.release()
        player = null
    }

    private fun writeWavHeader(out: RandomAccessFile, dataLength: Long, sampleRate: Int, channels: Int, bits: Int) {
        val byteRate = sampleRate * channels * bits / 8
        out.seek(0)
        out.writeBytes("RIFF")
        out.writeIntLE((dataLength + 36).toInt())
        out.writeBytes("WAVE")
        out.writeBytes("fmt ")
        out.writeIntLE(16)
        out.writeShortLE(1)
        out.writeShortLE(channels)
        out.writeIntLE(sampleRate)
        out.writeIntLE(byteRate)
        out.writeShortLE(channels * bits / 8)
        out.writeShortLE(bits)
        out.writeBytes("data")
        out.writeIntLE(dataLength.toInt())
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xff); write((value shr 8) and 0xff)
        write((value shr 16) and 0xff); write((value shr 24) and 0xff)
    }
    private fun RandomAccessFile.writeShortLE(value: Int) {
        write(value and 0xff); write((value shr 8) and 0xff)
    }
}
