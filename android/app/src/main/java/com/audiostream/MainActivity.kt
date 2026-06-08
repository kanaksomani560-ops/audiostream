package com.audiostream

import android.Manifest
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.os.PowerManager
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
import java.util.TreeMap
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private var running = false
    private var micRunning = false
    private val TCP_PORT = 5005
    private val MIC_PORT = 5006
    private val UDP_PORT = 5007
    private val JITTER_BUFFER_SIZE = 8
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Acquire WakeLock - keeps CPU running when screen is off
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AudioStream::WakeLock"
        )
        wakeLock?.acquire()

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

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.release()
    }

    private fun startUDPStream(ip: String, statusText: TextView, connectBtn: Button) {
        thread {
            try {
                val tcp = Socket(ip, TCP_PORT)
                val input = tcp.getInputStream()

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

                tcp.getOutputStream().write("$UDP_PORT\n".toByteArray())
                tcp.getOutputStream().flush()

                val minBuf = AudioTrack.getMinBufferSize(sampleRate, channels, AudioFormat.ENCODING_PCM_16BIT)
                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(channels).build())
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()

                audioTrack.play()
                runOnUiThread { statusText.text = "Streaming (UDP) from $ip!" }

                val jitterBuffer = TreeMap<Int, ByteArray>()
                val playQueue = LinkedBlockingQueue<ByteArray>(32)
                var expectedSeq = -1
                var buffering = true

                thread {
                    val udpSock = DatagramSocket(UDP_PORT)
                    udpSock.receiveBufferSize = 65536
                    val buf = ByteArray(65536)
                    val packet = DatagramPacket(buf, buf.size)

                    while (running) {
                        try {
                            udpSock.receive(packet)
                            if (packet.length > 4) {
                                val seq = ((packet.data[0].toInt() and 0xFF) shl 24) or
                                          ((packet.data[1].toInt() and 0xFF) shl 16) or
                                          ((packet.data[2].toInt() and 0xFF) shl 8) or
                                          (packet.data[3].toInt() and 0xFF)
                                val audioData = packet.data.copyOfRange(4, packet.length)

                                synchronized(jitterBuffer) {
                                    jitterBuffer[seq] = audioData
                                    if (buffering && jitterBuffer.size >= JITTER_BUFFER_SIZE) {
                                        buffering = false
                                    }
                                    if (!buffering) {
                                        if (expectedSeq == -1) expectedSeq = jitterBuffer.firstKey()
                                        while (jitterBuffer.isNotEmpty()) {
                                            val data = jitterBuffer.remove(expectedSeq)
                                            if (data != null) {
                                                playQueue.offer(data)
                                            } else {
                                                playQueue.offer(ByteArray(audioData.size))
                                            }
                                            expectedSeq = (expectedSeq + 1) % 65536
                                            if (jitterBuffer.isEmpty()) break
                                            if (expectedSeq == jitterBuffer.firstKey()) break
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            break
                        }
                    }
                    udpSock.close()
                }

                while (running) {
                    val data = playQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (data != null) {
                        audioTrack.write(data, 0, data.size)
                    }
                }

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
                    MediaRecorder.AudioSource.MIC, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
                )
                audioRecord.startRecording()
                runOnUiThread { statusText.text = "Mic streaming!" }
                val buffer = ByteArray(bufferSize)
                while (micRunning) {
                    val bytesRead = audioRecord.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        udpSock.send(DatagramPacket(buffer, bytesRead, serverAddr, MIC_PORT))
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
