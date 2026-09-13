package com.carrot.amapnavi

import org.json.JSONObject

/* =========================================================
 * 端口 4210 兼容层（原 Python auto_overtake_controller 的网络部分）
 *
 * openpilot(carrot) 侧把本 App 当作 "overtake" 设备：
 *   CP → App: 每 2s 广播 {ip, port:4211, device:"op"} 到广播地址:4210；
 *             对已注册客户端 10Hz 单播完整车辆状态(同样带 ip/port，device="op")
 *             到 app 的 4210 和 7705 两个端口
 *   App → CP: 收到上面这种"带 ip/port 的上报包"后，回一条
 *             {"resp":"overtake","device":"overtake","ip":<本机IP>,"port":4210,"timestamp":..}
 *             另外每 3s 广播心跳 {"resp":"heartbeat","device":"overtake",...} 到 4211
 *
 * Python 版本删除后，接收与响应改由 Kotlin 完成（见 KeepAliveService.opUdpService），
 * 使 CP 侧看到的行为与原来完全一致。
 *
 * 注意：4210 上收到的车辆状态与 7705(主通道) 是同一份数据，
 *       业务数据仍然只走 7705，这里只负责"握手/响应"，避免同一帧被处理两次。
 * ========================================================= */
object OvertakeCompat {

    /** 响应节流：CP 会 10Hz 单播，没必要每帧都回一条 */
    private const val MIN_RESP_INTERVAL_MS = 1000L

    @Volatile
    private var lastRespAt = 0L

    /** CP 最近一次上报的地址（日志/展示用，等价于 Python 版的 remote_ip/remote_port） */
    @Volatile
    var opIp: String = ""
        private set

    @Volatile
    var opPort: Int = 0
        private set

    /**
     * 判断收到的包是否需要回应，需要则返回响应报文，否则返回 null。
     * 不回应的情况：自己广播的车道数组(没有 ip/port)、对端的响应包、节流期内。
     */
    fun responseFor(json: JSONObject, localIp: String): String? {
        val ip = json.optString("ip", "")
        val port = json.optInt("port", 0)
        if (ip.isEmpty() || port <= 0) return null              // 例如 App 自己广播的车道数组
        if (json.optString("resp", "").isNotEmpty()) return null // 对端的响应包，不再回

        opIp = ip
        opPort = port

        val now = System.currentTimeMillis()
        if (now - lastRespAt < MIN_RESP_INTERVAL_MS) return null
        lastRespAt = now

        return JSONObject().apply {
            put("resp", "overtake")
            put("device", "overtake")
            put("ip", localIp)
            put("port", overtakeAppPort)
            put("timestamp", now)
        }.toString()
    }
}
