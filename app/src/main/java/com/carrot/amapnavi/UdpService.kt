package com.carrot.amapnavi

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.net.*

class UdpService(
    private val listenPort: Int,
    private val sendPort: Int,
    private val scope: CoroutineScope
) {

    private val TAG = "UdpService-$listenPort"

    // ========= 原有状态字段（全部保留） =========
    var recvCount = 0
        private set
    var sendCount = 0
        private set
    var udpActive = false
        private set
    var isPortInUse = false
        private set
    var sendEnable = false

    var onReceiveData: ((JSONObject, DatagramPacket) -> Unit)? = null
    var onUpdateStatus: (() -> Unit)? = null

    // ========= socket =========
    private var socket: DatagramSocket? = null
    private var remoteAddr: InetAddress? = null

    // ========= 发送通道（替代 queue + delay） =========
    private val sendChannel = Channel<SendItem>(Channel.UNLIMITED)

    private data class SendItem(
        val json: String,
        val host: String?,
        val port: Int?
    )

    // ========= 启动 =========
    fun start() {
        scope.launch {
            try {
                //socket = DatagramSocket(listenPort)
                socket = DatagramSocket(listenPort).apply { broadcast = true }  // 启动时加上
                udpActive = true
                isPortInUse = false
                notifyStatus()
            } catch (e: BindException) {
                isPortInUse = true
                notifyStatus()
                Log.e(TAG, "Port $listenPort already in use", e)
                return@launch
            } catch (e: Exception) {
                Log.e(TAG, "Create socket failed", e)
                return@launch
            }

            launch { receiveLoop() }
            launch { sendLoop() }
        }
    }

    // ========= 接收循环 =========
    private suspend fun receiveLoop() {
        val buffer = ByteArray(4096)
        while (currentCoroutineContext().isActive) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket?.receive(packet)

                remoteAddr = packet.address
                recvCount++
                if (recvCount >= 100) recvCount = 1

                onReceiveData?.invoke(
                    JSONObject(String(packet.data, 0, packet.length)),
                    packet
                )

                notifyStatus()
            } catch (e: Exception) {
                Log.e(TAG, "Receive error", e)
            }
        }
    }

    // ========= 发送循环 =========
    private suspend fun sendLoop() {
        for (item in sendChannel) {
            try {
                val ip = item.host ?: remoteAddr?.hostAddress ?: "255.255.255.255"
                val port = item.port ?: sendPort
                val data = item.json.toByteArray()

                socket?.send(
                    DatagramPacket(
                        data,
                        data.size,
                        InetAddress.getByName(ip),
                        port
                    )
                )

                sendCount++
                if (sendCount >= 100) sendCount = 1

                notifyStatus()
            } catch (e: Exception) {
                Log.e(TAG, "Send error", e)
            }
        }
    }

    // ========= 对外发送接口（等价） =========
    fun send(json: String, host: String? = null, port: Int? = null) {
        sendChannel.offer(SendItem(json, host, port))
    }

    // ========= 状态回调（不做节流） =========
    private fun notifyStatus() {
        scope.launch(Dispatchers.Main) {
            onUpdateStatus?.invoke()
        }
    }

    // ========= 停止 =========
    fun stop() {
        udpActive = false
        sendChannel.close()
        socket?.close()
        socket = null
        notifyStatus()
    }
}
