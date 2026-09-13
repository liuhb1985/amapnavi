package com.carrot.amapnavi

// -----------------------------
// 全局对象
// -----------------------------
object Global {
    //lateinit var deviceId: String
    var deviceId: String = "0000000000"
}

@Volatile
var wakelock_enable = 0

var navDataCount = 0
var udpStateShow = ""

var bShowRawLog = false

// -----------------------------
// UDP 通讯全局变量
// -----------------------------
var carrotManIp: String? = null
var carrotManPort: Int? = null
var carrotManValid = false
var carrotManSrvTime = 0L

var laneSrvIp: String? = null
var laneSrvPort: Int? = null
var laneSrvValid = false
var laneSrvTimeout = false

var naviSrvIp: String? = null
var naviSrvPort: Int? = null

val appServerHost = "nav.gitop.vip"
val appServerPort = 6666

/*
lateinit var cmUdpService: UdpService
lateinit var laneUdpService: UdpService
lateinit var naviUdpService: UdpService

 */

var cmUdpServiceSendEnable = false
var laneUdpServiceSendEnable = false
var naviUdpServiceSendEnable = false

@Volatile
private var lastUiUpdateTime = 0L

private const val UI_UPDATE_INTERVAL_MS = 200L
