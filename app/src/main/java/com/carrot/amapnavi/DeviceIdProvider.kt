package com.carrot.amapnavi

import android.content.Context

@Volatile
private var cachedDeviceId: String? = null

fun getDeviceId(context: Context): String {
    return cachedDeviceId ?: synchronized(StableDeviceId::class) {
        cachedDeviceId ?: StableDeviceId.generate(context.applicationContext).also {
            cachedDeviceId = it
        }
    }
}
