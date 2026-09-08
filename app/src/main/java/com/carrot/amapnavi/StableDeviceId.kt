package com.carrot.amapnavi

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs

object StableDeviceId {

    fun generate(context: Context): String {
        val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
        ) ?: "unknown"

        val hardwareSignature = listOf(
                Build.BOARD,
                Build.BRAND,
                Build.DEVICE,
                Build.HARDWARE,
                Build.MANUFACTURER,
                Build.MODEL,
                Build.PRODUCT,
                Build.VERSION.SDK_INT.toString()
        ).joinToString("-")

        val salt = appServerHost   // 如果是全局常量 OK
        val raw = "$androidId-$hardwareSignature-$salt"

        val digest = MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray())

        val value = abs(ByteBuffer.wrap(digest).long)
        val id10 = value % 10_000_000_000L

        return String.format(Locale.US, "%010d", id10)
    }
}
