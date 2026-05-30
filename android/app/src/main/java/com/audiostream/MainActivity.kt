package com.audiostream

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private var running = false
    private val PORT = 5005

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val ipInput = findViewById<EditText>(R.id.ipInput)
        val connectBtn = findViewById<Button>(R.id.connectBtn)
        val statusText = findViewById<TextView>(R.id.statusText)

        connectBtn.setOnClickListener {
            if (!running) {
                val ip = ipInput.text.toString().trim()
                if (ip.isEmpty()) {
                    statusText.text = "Please enter PC IP address"
                    return@setOnClickListener
                }
                running = true
                connectBtn.text = "Disconnect"
                statusText.text = "Connecting to $ip..."
                startStreaming(ip, statusText, connectBtn)
            } else {
                running = false
                connectBtn.text = "Connect"
                statusText.text = "Disconnected"
            }
        }
    }

    private fun startStreaming(ip: String, statusText: TextView, connectBtn: Button) {
        thread {
            try {
                val socket = java.net.Socket(ip, PORT)
                val input = socket.getInputStream()

                // Read rate and channels from server
                val headerBytes = StringBuilder()
                var b: Int
                while (input.read().also { b = it } != -1) {
                    val c = b.toChar()
                    if (c == '\n') break
                    headerBytes.append(c)
                }
                val (rateStr, chStr) = headerBytes.toString().split(",")
                val sampleRate = rateStr.toInt()
                val channels = if (chStr.toInt() == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO

                val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channels, AudioFormat.ENCODING_PCM_16BIT)
                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(channels)
                        .build())
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack.play()
                runOnUiThread { statusText.text = "Streaming from $ip :)" }

                val buffer = ByteArray(4096)
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
}
