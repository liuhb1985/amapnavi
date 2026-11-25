package com.carrot.amapnavi

import android.content.Context
import android.media.MediaPlayer
import android.util.Log

object SoundPlayer {
    private val playerMap = HashMap<String, MediaPlayer>()

    /**
     * 播放 raw 下的音频文件（文件名不含后缀）
     * 例如 engage.wav  => 调用 play("engage")
     */
    fun play(context: Context, name: String) {
        try {
            val resId = context.resources.getIdentifier(name, "raw", context.packageName)
            if (resId == 0) {
                Log.e("SoundPlayer", "Sound not found in raw: $name")
                return
            }

            // 如果已有播放器，先释放
            if (playerMap.containsKey(name)) {
                playerMap[name]?.release()
                playerMap.remove(name)
            }

            // 创建播放器
            val mp = MediaPlayer.create(context, resId)
            if (mp == null) {
                Log.e("SoundPlayer", "MediaPlayer.create failed for: $name")
                return
            }

            playerMap[name] = mp

            mp.setOnCompletionListener {
                it.release()
                playerMap.remove(name)
            }

            mp.start()

        } catch (e: Exception) {
            Log.e("SoundPlayer", "Play error for $name: ${e.message}")
        }
    }

    /** 在退出 App 时释放所有播放器 **/
    fun releaseAll() {
        for (mp in playerMap.values) {
            try {
                mp.release()
            } catch (_: Exception) {}
        }
        playerMap.clear()
    }
}
