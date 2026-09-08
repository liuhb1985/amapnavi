package com.carrot.amapnavi

import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.Log

class JpegFrameFetcher(private val url: String) {

    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()

    fun fetchFrame(): ByteArray? {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("JpegFrameFetcher", "HTTP error: ${response.code}")
                    return null
                }
                response.body?.bytes()
            }
        } catch (e: Exception) {
            Log.e("JpegFrameFetcher", "fetchFrame error", e)
            null
        }
    }
}