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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private var running = false
    private var micRunning = false
    private val TCP_PORT = 5005
    private val MIC_PORT = 5006
    private val UDP_PORT = 5007  // phone listens on this for audio

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
                if (ip.isEmpty()) { statusText.text = "Enter IP!"; return@setOnClickListener }
                running = true
                connectBtn.text = "Disconnect"
                statusText.text = "Connecting..."
                startUDPStream(ip, statusText, connectBtn)
            } else {
                running = false
                connectBtn.text = "Connect"
                statusText.text = "Disconnected"
            }
        }

        micBtn.setOnClickListener {
            if (!micRunning) {
                val ip = ipInput.text.toString().trim()
                if (ip.isEmpty()) { statusText.text = "Enter IP first!"; return@setOnClickListener }
                micRunning = true
                micBtn.text = "Mic ON 🔴"
                startMicUDP(ip, statusText, micBtn)
            } else {
                micRunning = false
                micBtn.text = "Use as Mic"
                statusText.text = "Mic stopped"
            }
        }
    }

    private fun startUDPStream(ip: String, statusText: TextView, connectBtn: Button) {
        thread {
            try {
                // TCP handshake to get audio config and tell server our UDP port
                val tcp = Socket(ip, TCP_PORT)
                val input = tcp.getInputStream()

                // Read config
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

                // Tell server our UDP port
                tcp.getOutputStream().write("$UDP_PORT\n".toByteArray())
                tcp.getOutputStream().flush()

                // Setup AudioTrack with minimum buffer
                val minBuf = AudioTrack.getMinBufferSize(sampleRate, channels, AudioFormat.ENCODING_PCM_16BIT)
                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(channels).build())
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()

                audioTrack.play()
                runOnUiThread { statusText.text = "Streaming (UDP) from $ip!" }

                // Listen for UDP audio packets
                val udpSock = DatagramSocket(UDP_PORT)
                udpSock.receiveBufferSize = 4096
                val buf = ByteArray(65536)
                val packet = DatagramPacket(buf, buf.size)

                while (running) {
                    udpSock.receive(packet)
                    // Skip 4-byte sequence number, play audio data
                    if (packet.length > 4) {
                        audioTrack.write(packet.data, 4, packet.length - 4)
                    }
                }

                udpSock.close()
                audioTrack.stop()
                audioTrack.release()
                tcp.close()

            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Error: ${e.message}"
                    connectBtn.text = "Connect"
                    running = false
                }
            }
        }
    }

    private fun startMicUDP(ip: String, statusText: TextView, micBtn: Button) {
        thread {
            try {
                val serverAddr = InetAddress.getByName(ip)
                val udpSock = DatagramSocket()

                val sampleRate = 44100
                val bufferSize = AudioRecord.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )

                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                audioRecord.startRecording()
                runOnUiThread { statusText.text = "Mic streaming (UDP)!" }

                val buffer = ByteArray(bufferSize)
                while (micRunning) {
                    val bytesRead = audioRecord.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        val packet = DatagramPacket(buffer, bytesRead, serverAddr, MIC_PORT)
                        udpSock.send(packet)
                    }
                }

                audioRecord.stop()
                audioRecord.release()
                udpSock.close()
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
