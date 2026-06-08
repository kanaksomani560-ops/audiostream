package com.audiostream

import android.Manifest
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private var running = false
    private var micRunning = false
    private val PORT = 5005
    private val MIC_PORT = 5006

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val ipInput = findViewById<EditText>(R.id.ipInput)
        val connectBtn = findViewById<Button>(R.id.connectBtn)
        val micBtn = findViewById<Button>(R.id.micBtn)
        val statusText = findViewById<TextView>(R.id.statusText)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        connectBtn.setOnClickListener {
            if (!running) {
                val ip = ipInput.text.toString().trim()
                if (ip.isEmpty()) { statusText.text = "Enter IP address"; return@setOnClickListener }
                running = true
                connectBtn.text = "Disconnect"
                statusText.text = "Connecting..."
                startAudioStream(ip, statusText, connectBtn)
            } else {
                running = false
                connectBtn.text = "Connect"
                statusText.text = "Disconnected"
            }
        }

        micBtn.setOnClickListener {
            if (!micRunning) {
                val ip = ipInput.text.toString().trim()
                if (ip.isEmpty()) { statusText.text = "Enter IP address first"; return@setOnClickListener }
                micRunning = true
                micBtn.text = "Mic ON 🔴"
                startMicStream(ip, statusText, micBtn)
            } else {
                micRunning = false
                micBtn.text = "Use as Mic"
                statusText.text = "Mic stopped"
            }
        }
    }

    private fun startAudioStream(ip: String, statusText: TextView, connectBtn: Button) {
        thread {
            try {
                val socket = java.net.Socket(ip, PORT)
                val input = socket.getInputStream()

                val header = StringBuilder()
                var b: Int
                while (input.read().also { b = it } != -1) {
                    val c = b.toChar()
                    if (c == '\n') break
                    header.append(c)
                }
                val (rateStr, chStr) = header.toString().split(",")
                val sampleRate = rateStr.toInt()
                val channels = if (chStr.toInt() == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO

                val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channels, AudioFormat.ENCODING_PCM_16BIT)
                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(channels).build())
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack.play()
                runOnUiThread { statusText.text = "Streaming from $ip" }

                val buffer = ByteArray(512)
                while (running) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead > 0) audioTrack.write(buffer, 0, bytesRead)
                }

                socket.close()
                audioTrack.stop()
                audioTrack.release()
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Error: ${e.message}"
                    connectBtn.text = "Connect"
                    running = false
                }
            }
        }
    }

    private fun startMicStream(ip: String, statusText: TextView, micBtn: Button) {
        thread {
            try {
                val socket = java.net.Socket(ip, MIC_PORT)
                val output = socket.getOutputStream()

                val sampleRate = 44100
                val bufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                audioRecord.startRecording()
                runOnUiThread { statusText.text = "Mic streaming to PC!" }

                val buffer = ByteArray(bufferSize)
                while (micRunning) {
                    val bytesRead = audioRecord.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) output.write(buffer, 0, bytesRead)
                }

                audioRecord.stop()
                audioRecord.release()
                socket.close()
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Mic error: ${e.message}"
                    micBtn.text = "Use as Mic"
                    micRunning = false
                }
            }
        }
    }
}
