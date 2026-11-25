package com.carrot.amapnavi

import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.*
import java.util.concurrent.ConcurrentHashMap

object UdpDeviceManager {

    private const val LOCAL_PORT = 5510
    private const val REMOTE_PORT = 4210

    private val devices = ConcurrentHashMap<String, DeviceInfo>()

    private var socket: DatagramSocket? = null

    private var sendJob: Job? = null
    private var receiveJob: Job? = null
    private var refreshJob: Job? = null
    private var heartbeatJob: Job? = null

    private var index = 0

    // ---------- 对外接口 ----------
    fun getDevices(): Map<String, DeviceInfo> = devices

    fun start() {
        if (sendJob?.isActive == true || receiveJob?.isActive == true) {
            return
        }
        startSocket()
        startSendJob()       // 广播查询
        startReceiveJob()    // 接收
        startRefreshJob()    // 清理
        startHeartbeatJob()  // 新增：心跳包
    }

    fun stop() {
        sendJob?.cancel()
        receiveJob?.cancel()
        refreshJob?.cancel()
        heartbeatJob?.cancel()
        socket?.close()

        sendJob = null
        receiveJob = null
        refreshJob = null
        heartbeatJob = null
        socket = null
        devices.clear()
    }

    // ---------- 内部实现 ----------
    private fun startSocket() {
        if (socket == null || socket!!.isClosed) {
            socket = DatagramSocket(LOCAL_PORT)
            socket!!.broadcast = true
            socket!!.soTimeout = 500
        }
    }

    // 1) 每秒广播一次 getinfo
    private fun startSendJob() {
        sendJob = CoroutineScope(Dispatchers.IO).launch {
            val broadcastAddr = InetAddress.getByName("255.255.255.255")

            while (isActive) {
                try {
                    val localIp = getLocalIpAddress()

                    index++
                    val json = JSONObject().apply {
                        put("index", index)
                        put("cmd", "getinfo")
                        put("ip", localIp)
                        put("port", LOCAL_PORT)
                    }
                    val data = json.toString().toByteArray(Charsets.UTF_8)
                    val packet = DatagramPacket(data, data.size, broadcastAddr, REMOTE_PORT)
                    socket?.send(packet)
                } catch (_: Exception) {
                }
                delay(2000)
            }
        }
    }

    // 2) 新增：每 2 秒给所有设备发送心跳包
    private fun startHeartbeatJob() {
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val localIp = getLocalIpAddress()

                    val json = JSONObject().apply {
                        put("index", index)
                        put("cmd", "getinfo")   // 和广播一样
                        put("ip", localIp)
                        put("port", LOCAL_PORT)
                    }

                    val data = json.toString().toByteArray(Charsets.UTF_8)

                    devices.values.forEach { dev ->
                        try {
                            val addr = InetAddress.getByName(dev.ip)
                            val packet = DatagramPacket(data, data.size, addr, REMOTE_PORT)
                            socket?.send(packet)
                        } catch (_: Exception) {
                        }
                    }

                } catch (_: Exception) {}

                delay(2000)
            }
        }
    }

    // 3) 接收响应
    private fun startReceiveJob() {
        receiveJob = CoroutineScope(Dispatchers.IO).launch {
            val buf = ByteArray(4096)
            while (isActive) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket?.receive(packet)
                    val msg = String(packet.data, 0, packet.length)
                    val senderIp = packet.address.hostAddress
                    handleResponse(msg, senderIp, packet.port)
                } catch (_: SocketTimeoutException) {
                } catch (_: Exception) {
                }
            }
        }
    }

    // 解析设备信息
    private fun handleResponse(msg: String, senderIp: String, senderPort: Int) {
        try {
            val obj = JSONObject(msg)

            val registeredValue = if (obj.has("registered")) {
                if (obj.optBoolean("registered", false)) 1 else 0
            } else {
                -1
            }

            val info = DeviceInfo(
                device_id = obj.optString("device_id", ""),
                device = obj.optString("device", ""),
                version = obj.optString("version", ""),
                ip = senderIp,
                port = senderPort,
                board = obj.optInt("board", 0),
                registered = registeredValue,
                detect_side = obj.optInt("detect_side", 0),
                lastSeen = System.currentTimeMillis()
            )

            devices[senderIp] = info

        } catch (_: Exception) { }
    }

    // 4) 定时清理失效设备（5 秒未响应）
    private fun startRefreshJob() {
        refreshJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val iterator = devices.entries.iterator()
                var changed = false

                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (now - entry.value.lastSeen > 5000) {
                        iterator.remove()
                        changed = true
                    }
                }

                delay(1000)
            }
        }
    }

    // 获取本机 IP
    private fun getLocalIpAddress(): String {
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            for (intf in en) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (_: Exception) { }
        return "0.0.0.0"
    }
}
