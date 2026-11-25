package com.carrot.amapnavi

data class DeviceInfo(
    val device_id: String,
    val device: String,
    val version: String,
    val ip: String,
    val port: Int,
    val board: Int,
    val registered: Int,
    val detect_side: Int,
    var lastSeen: Long = System.currentTimeMillis()
)
