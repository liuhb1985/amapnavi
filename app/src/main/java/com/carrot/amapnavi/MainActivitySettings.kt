package com.carrot.amapnavi

import android.content.Context
import android.util.Log
import android.widget.LinearLayout
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/* =========================================================
 * 参数设置 / 环境初始化模块
 *   - 用户设置加载（loadUserSettings）
 *   - Python 环境初始化（PythonEnvInit）
 *   - 语音轮询（pollSound / startSoundPolling）
 * 说明：本文件中的函数均为 MainActivity 的扩展函数。
 * ========================================================= */

//加载参数
fun MainActivity.loadUserSettings() {
    val prefs = getSharedPreferences("user_settings", Context.MODE_PRIVATE)
    val editor = prefs.edit()

    // 1. 导航红灯辅助停车
    navi_traffic_assist = if (prefs.contains("navi_traffic_assist")) {
        prefs.getBoolean("navi_traffic_assist", false)
    } else {
        editor.putBoolean("navi_traffic_assist", false)
        false
    }
    cp_traffic_assist = if (prefs.contains("cp_traffic_assist")) {
        prefs.getBoolean("cp_traffic_assist", false)
    } else {
        editor.putBoolean("cp_traffic_assist", false)
        false
    }
    redlight_traffic_assist = if (prefs.contains("redlight_traffic_assist")) {
        prefs.getBoolean("redlight_traffic_assist", false)
    } else {
        editor.putBoolean("redlight_traffic_assist", false)
        false
    }
    tunnel_dec_speed = if (prefs.contains("tunnel_dec_speed")) {
        prefs.getBoolean("tunnel_dec_speed", true)
    } else {
        editor.putBoolean("tunnel_dec_speed", true)
        false
    }
    app_speak = if (prefs.contains("app_speak")) {
        prefs.getBoolean("app_speak", false)
    } else {
        editor.putBoolean("app_speak", false)
        false
    }
    app_log = if (prefs.contains("app_log")) {
        prefs.getBoolean("app_log", false)
    } else {
        editor.putBoolean("app_log", false)
        false
    }
    app_navi = if (prefs.contains("app_navi")) {
        prefs.getBoolean("app_navi", false)
    } else {
        editor.putBoolean("app_navi", false)
        false
    }
    app_road_navi = if (prefs.contains("app_road_navi")) {
        prefs.getBoolean("app_road_navi", false)
    } else {
        editor.putBoolean("app_road_navi", false)
        false
    }
    navi_speak = if (prefs.contains("navi_speak")) {
        prefs.getBoolean("navi_speak", true)
    } else {
        editor.putBoolean("navi_speak", true)
        true
    }
    navi_speak_first = if (prefs.contains("navi_speak_first")) {
        prefs.getBoolean("navi_speak_first", true)
    } else {
        editor.putBoolean("navi_speak_first", true)
        true
    }
    navi_blind_dec_speed = if (prefs.contains("navi_blind_dec_speed")) {
        prefs.getBoolean("navi_blind_dec_speed", false)
    } else {
        editor.putBoolean("navi_blind_dec_speed", false)
        false
    }
    lane_prob_enable = if (prefs.contains("lane_prob_enable")) {
        prefs.getBoolean("lane_prob_enable", false)
    } else {
        editor.putBoolean("lane_prob_enable", false)
        false
    }
    suggest_lane_en = if (prefs.contains("suggest_lane_en")) {
        prefs.getBoolean("suggest_lane_en", false)
    } else {
        editor.putBoolean("suggest_lane_en", false)
        false
    }
    dis_lane_en = if (prefs.contains("dis_lane_en")) {
        prefs.getBoolean("dis_lane_en", false)
    } else {
        editor.putBoolean("dis_lane_en", false)
        false
    }

    keep_left_en = if (prefs.contains("keep_left_en")) {
        prefs.getBoolean("keep_left_en", false)
    } else {
        editor.putBoolean("keep_left_en", false)
        false
    }
    keep_right_en = if (prefs.contains("keep_right_en")) {
        prefs.getBoolean("keep_right_en", false)
    } else {
        editor.putBoolean("keep_right_en", false)
        false
    }
    navi_min_speed = if (prefs.contains("navi_min_speed")) {
        prefs.getInt("navi_min_speed", 20)
    } else {
        editor.putInt("navi_min_speed", 20)
        20
    }

    road_median = if (prefs.contains("road_median")) {
        prefs.getBoolean("road_median", true)
    } else {
        editor.putBoolean("road_median", true)
        true
    }
    road_right_first = if (prefs.contains("road_right_first")) {
        prefs.getBoolean("road_right_first", true)
    } else {
        editor.putBoolean("road_right_first", true)
        true
    }
    vision_keep_right = if (prefs.contains("vision_keep_right")) {
        prefs.getBoolean("vision_keep_right", false)
    } else {
        editor.putBoolean("vision_keep_right", false)
        false
    }
    nav_turn = if (prefs.contains("nav_turn")) {
        prefs.getBoolean("nav_turn", false)
    } else {
        editor.putBoolean("nav_turn", false)
        false
    }
    nav_mode = if (prefs.contains("nav_mode")) {
        prefs.getInt("nav_mode", 2)
    } else {
        editor.putInt("nav_mode", 2)
        2
    }
    smooth_speed_limit = if (prefs.contains("smooth_speed_limit")) {
        prefs.getBoolean("smooth_speed_limit", false)
    } else {
        editor.putBoolean("smooth_speed_limit", false)
        false
    }
    lane_overtake = if (prefs.contains("lane_overtake")) {
        prefs.getBoolean("lane_overtake", false)
    } else {
        editor.putBoolean("lane_overtake", false)
        false
    }
    lane_show = if (prefs.contains("lane_show")) {
        prefs.getBoolean("lane_show", false)
    } else {
        editor.putBoolean("lane_show", false)
        false
    }
    video_show = if (prefs.contains("video_show")) {
        prefs.getBoolean("video_show", false)
    } else {
        editor.putBoolean("video_show", false)
        false
    }
    video_show_fast = if (prefs.contains("video_show_fast")) {
        prefs.getBoolean("video_show_fast", false)
    } else {
        editor.putBoolean("video_show_fast", false)
        false
    }
    lane_detect = if (prefs.contains("lane_detect")) {
        prefs.getBoolean("lane_detect", true)
    } else {
        editor.putBoolean("lane_detect", true)
        true
    }
    lane_color = if (prefs.contains("lane_color")) {
        prefs.getBoolean("lane_color", false)
    } else {
        editor.putBoolean("lane_color", false)
        false
    }
    // 2. 红灯减速最低速度
    red_light_min_speed_limit = if (prefs.contains("red_light_min_speed_limit")) {
        prefs.getInt("red_light_min_speed_limit", 1)
    } else {
        editor.putInt("red_light_min_speed_limit", 1)
        1
    }
    cp_min_speed_limit = if (prefs.contains("cp_min_speed_limit")) {
        prefs.getInt("cp_min_speed_limit", 20)
    } else {
        editor.putInt("cp_min_speed_limit", 20)
        20
    }

    road_speed_limit_percent = if (prefs.contains("road_speed_limit_percent")) {
        prefs.getInt("road_speed_limit_percent", 0)
    } else {
        editor.putInt("road_speed_limit_percent", 0)
        0
    }
    road_speed_limit_kph = if (prefs.contains("road_speed_limit_kph")) {
        prefs.getInt("road_speed_limit_kph", 0)
    } else {
        editor.putInt("road_speed_limit_kph", 0)
        0
    }

    // 3. 高速偏移
    offset_highway = if (prefs.contains("offset_highway")) {
        prefs.getInt("offset_highway", 20)
    } else {
        editor.putInt("offset_highway", 20)
        20
    }
    // 4. 公路偏移
    offset_road = if (prefs.contains("offset_road")) {
        prefs.getInt("offset_road", 15)
    } else {
        editor.putInt("offset_road", 15)
        15
    }
    //5. 最小限速
    min_speed_limit = if (prefs.contains("min_speed_limit")) {
        prefs.getInt("min_speed_limit", 30)
    } else {
        editor.putInt("min_speed_limit", 30)
        30
    }
    max_speed_limit = if (prefs.contains("max_speed_limit")) {
        prefs.getInt("max_speed_limit", 130)
    } else {
        editor.putInt("max_speed_limit", 130)
        130
    }
    //6. 导航红灯辅助降速时间
    navi_traffic_assist_time = if (prefs.contains("navi_traffic_assist_time")) {
        prefs.getInt("navi_traffic_assist_time", 10)
    } else {
        editor.putInt("navi_traffic_assist_time", 10)
        10
    }

    station_dist_offset = if (prefs.contains("station_dist_offset")) {
        prefs.getInt("station_dist_offset", 0)
    } else {
        editor.putInt("station_dist_offset", 0)
        0
    }
    station_speed = if (prefs.contains("station_speed")) {
        prefs.getInt("station_speed", 15)
    } else {
        editor.putInt("station_speed", 15)
        15
    }
    station_keep_time = if (prefs.contains("station_keep_time")) {
        prefs.getInt("station_keep_time", 0)
    } else {
        editor.putInt("station_keep_time", 0)
        0
    }
    lane_detect_interval = if (prefs.contains("lane_detect_interval")) {
        prefs.getInt("lane_detect_interval", 500)
    } else {
        editor.putInt("lane_detect_interval", 500)
        500
    }

    atc_dist_h = if (prefs.contains("atc_dist_h")) {
        prefs.getInt("atc_dist_h", 2000)
    } else {
        editor.putInt("atc_dist_h", 2000)
        2000
    }
    atc_dist_l = if (prefs.contains("atc_dist_l")) {
        prefs.getInt("atc_dist_l", 500)
    } else {
        editor.putInt("atc_dist_l", 500)
        500
    }
    fork_dist_h = if (prefs.contains("fork_dist_h")) {
        prefs.getInt("fork_dist_h", 80)
    } else {
        editor.putInt("fork_dist_h", 80)
        80
    }
    fork_dist_l = if (prefs.contains("fork_dist_l")) {
        prefs.getInt("fork_dist_l", 30)
    } else {
        editor.putInt("fork_dist_l", 30)
        30
    }

    top_pad_dp = if (prefs.contains("top_pad_dp")) {
        prefs.getInt("top_pad_dp", 5)
    } else {
        editor.putInt("top_pad_dp", 5)
        5
    }

    overtake_mode = if (prefs.contains("overtake_mode")) {
        prefs.getInt("overtake_mode", 2)
    } else {
        editor.putInt("overtake_mode", 2)
        2
    }
    vision_lane_mode = if (prefs.contains("vision_lane_mode")) {
        prefs.getInt("vision_lane_mode", 1)
    } else {
        editor.putInt("vision_lane_mode", 1)
        1
    }
    app_overtake = if (prefs.contains("app_overtake")) {
        prefs.getBoolean("app_overtake", false)
    } else {
        editor.putBoolean("app_overtake", false)
        false
    }
    app_road_overtake = if (prefs.contains("app_road_overtake")) {
        prefs.getBoolean("app_road_overtake", false)
    } else {
        editor.putBoolean("app_road_overtake", false)
        false
    }

    // 提交默认值（如果有写入的话）
    editor.apply()

    if(show_param_log) {
        show_param_log = false
        val info = "加载设置--->APP辅助停车: ${if (navi_traffic_assist) "开启" else "关闭"}，" +
                "视觉红灯信号辅助: ${if (cp_traffic_assist) "开启" else "关闭"}，" +
                "导航红灯信号辅助: ${if (redlight_traffic_assist) "开启" else "关闭"}，" +
                "隧道减速: ${if (tunnel_dec_speed) "开启" else "关闭"}，" +
                "APP播报: ${if (app_speak) "开启" else "关闭"}，" +
                "最低限速=$min_speed_limit km/h, " +
                "收费站提前减速距离=$station_dist_offset 米, " +
                "收费站限速=$station_speed km/h, " +
                "过收费站时间=$station_keep_time 秒, " +
                "视觉红灯减速最低速度=$cp_min_speed_limit km/h, " +
                "导航红灯减速最低速度=$red_light_min_speed_limit km/h, " +
                "高速偏移=$offset_highway km/h，公路偏移=$offset_road km/h, 最低限速=$min_speed_limit km/h"
        if (!bShowRawLog) {
            clearRawLog()
        }
        setRawLogVisible(true)
        updateRawLog(info, true)

        if (!bShowRawLog) {
            // 取消上一次再重新计时
            uiHandler.removeCallbacks(hideRawLogRunnable)
            uiHandler.postDelayed(hideRawLogRunnable, 5000)
        }
    }

    val container = findViewById<LinearLayout>(R.id.containerLinearLayout)
    setTopPaddingDp(container, top_pad_dp)

    //参数应用
    PythonEnvInit()

    //val py = Python.getInstance()
    //val module = py.getModule("auto_overtake_controller")
    //module.callAttr("set_app_navi", app_navi || app_road_navi || app_overtake || app_road_overtake)
}

fun MainActivity.pollSound() {
    try {
        if(!appSpeakFile.isNullOrEmpty()){
            if(navi_speak) {
                SoundPlayer.play(this, appSpeakFile)
            }
            appSpeakFile = ""
            return
        }

        //Log.d("SoundPoll", "pollSound running")
        //val py = Python.getInstance()
        //val module = py.getModule("sound_center")

        if(isSoundCenterReady()) {
            val result = sound_centerModule.callAttr("get_requested_sound")

            if (result == null) {
                //Log.d("SoundPoll", "get_requested_sound() result is null")
                return  // 安全退出
            }

            val sound = result.toString()
            //Log.d("SoundPoll", "sound: ${sound}")
            //Log.d("SoundPoll", "sound raw bytes = " + sound.toByteArray().joinToString(","))

            if (sound.isNotEmpty() && app_speak) {
                SoundPlayer.play(this, sound)
            }
        }

    } catch (e: Exception) {
        Log.e("SoundPoll", "pollSound error: ${e.message}")
    }
}

fun MainActivity.startSoundPolling() {
    if (pollRunnable != null) return // 避免重复启动
    Log.d("SoundPoll", "startSoundPolling")
    pollRunnable = object : Runnable {
        override fun run() {
            pollSound()
            sound_handler.postDelayed(this, pollIntervalMs)
        }
    }

    sound_handler.post(pollRunnable!!)
}

fun MainActivity.PythonEnvInit(){
    if (!Python.isStarted()) {
        //Python.start(AndroidPlatform(this))
        Python.start(AndroidPlatform(this))
    }

    if (!isPyReady()) {
        py = Python.getInstance()
        overtake_controllerModule = py.getModule("auto_overtake_controller")
        overtakeModule = py.getModule("auto_overtake")
        sound_centerModule = py.getModule("sound_center")
        configModule = py.getModule("config")
    }
}
