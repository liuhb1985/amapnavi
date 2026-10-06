package com.carrot.amapnavi

import android.content.Context
import android.util.Log
import android.widget.LinearLayout

/* =========================================================
 * 参数设置 / 环境初始化模块
 *   - 用户设置加载（loadUserSettings）
 *   - 声音轮询与播放（pollSound / SoundCenter）
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
    // 1b. 无闯红灯摄像头时也下发（TBT 距离兜底）
    red_light_no_camera = if (prefs.contains("red_light_no_camera")) {
        prefs.getBoolean("red_light_no_camera", false)
    } else {
        editor.putBoolean("red_light_no_camera", false)
        false
    }

    // 1c. 兜底通道作用距离上限（米）
    red_light_max_dist = if (prefs.contains("red_light_max_dist")) {
        prefs.getInt("red_light_max_dist", 200)
    } else {
        editor.putInt("red_light_max_dist", 200)
        200
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
        // CP 侧 nRoadLimitSpeed > 200 会按 "(n-20)/10" 反解(历史放大编码)，
        // 例如 250 会被解析成 23 km/h——这里必须与 CP 保持一致地上限夹住
        prefs.getInt("max_speed_limit", 130).coerceIn(30, 200)
    } else {
        editor.putInt("max_speed_limit", 130)
        130
    }

    // ---- 与 CP 对齐新增的开关 ----
    phone_gps_enable = if (prefs.contains("phone_gps_enable")) {
        prefs.getBoolean("phone_gps_enable", false)
    } else {
        editor.putBoolean("phone_gps_enable", false)
        false
    }
    traffic_detect_cmd = if (prefs.contains("traffic_detect_cmd")) {
        prefs.getBoolean("traffic_detect_cmd", false)
    } else {
        editor.putBoolean("traffic_detect_cmd", false)
        false
    }
    lane_stop_when_invalid = if (prefs.contains("lane_stop_when_invalid")) {
        prefs.getBoolean("lane_stop_when_invalid", true)
    } else {
        editor.putBoolean("lane_stop_when_invalid", true)
        true
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

    //匝道/分岔提前减速
    // 一次性迁移：首版默认值(高速800m/80、公路300m/40)偏保守，
    // 改为 400m/60、200m/45；只替换仍是旧默认值的项，用户改过的不动。
    if (!prefs.getBoolean("ramp_default_migrated_v2", false)) {
        editor.putBoolean("ramp_default_migrated_v2", true)
        if (prefs.getInt("ramp_slowdown_dist_h", 800) == 800) editor.putInt("ramp_slowdown_dist_h", 400)
        if (prefs.getInt("ramp_slowdown_dist_l", 300) == 300) editor.putInt("ramp_slowdown_dist_l", 200)
        if (prefs.getInt("ramp_slowdown_speed_h", 80) == 80) editor.putInt("ramp_slowdown_speed_h", 60)
        if (prefs.getInt("ramp_slowdown_speed_l", 40) == 40) editor.putInt("ramp_slowdown_speed_l", 45)
        // 先落地：apply() 会立即更新内存缓存，下面的 prefs.getInt 才能读到迁移后的值
        editor.apply()
    }

    ramp_slowdown_en = if (prefs.contains("ramp_slowdown_en")) {
        prefs.getBoolean("ramp_slowdown_en", true)
    } else {
        editor.putBoolean("ramp_slowdown_en", true)
        true
    }
    ramp_slowdown_dist_h = if (prefs.contains("ramp_slowdown_dist_h")) {
        prefs.getInt("ramp_slowdown_dist_h", 400)
    } else {
        editor.putInt("ramp_slowdown_dist_h", 400)
        400
    }
    ramp_slowdown_dist_l = if (prefs.contains("ramp_slowdown_dist_l")) {
        prefs.getInt("ramp_slowdown_dist_l", 200)
    } else {
        editor.putInt("ramp_slowdown_dist_l", 200)
        200
    }
    ramp_slowdown_speed_h = if (prefs.contains("ramp_slowdown_speed_h")) {
        prefs.getInt("ramp_slowdown_speed_h", 60)
    } else {
        editor.putInt("ramp_slowdown_speed_h", 60)
        60
    }
    ramp_slowdown_speed_l = if (prefs.contains("ramp_slowdown_speed_l")) {
        prefs.getInt("ramp_slowdown_speed_l", 45)
    } else {
        editor.putInt("ramp_slowdown_speed_l", 45)
        45
    }
    //一次性迁移：匝道减速维持时间默认 10s → 6s(10s 偏长，出匝道后限速恢复太慢)，
    //只替换仍是旧默认值的项，用户改过的不动
    if (!prefs.getBoolean("ramp_hold_migrated_v3", false)) {
        editor.putBoolean("ramp_hold_migrated_v3", true)
        if (prefs.getInt("ramp_hold_time", 10) == 10) editor.putInt("ramp_hold_time", 6)
        editor.apply()
    }

    ramp_hold_time = if (prefs.contains("ramp_hold_time")) {
        prefs.getInt("ramp_hold_time", 6)
    } else {
        editor.putInt("ramp_hold_time", 6)
        6
    }

    //车道线判据不可用(false 视觉线型)时是否拦截变道：默认拦截(fail-safe)，可热调关闭
    lane_unknown_block = if (prefs.contains("lane_unknown_block")) {
        prefs.getBoolean("lane_unknown_block", true)
    } else {
        editor.putBoolean("lane_unknown_block", true)
        true
    }

    //变道安全门"手写版兼容"总开关(默认开)：设置页"宽松变道条件"默认勾选
    safety_legacy_mode = if (prefs.contains("safety_legacy_mode")) {
        prefs.getBoolean("safety_legacy_mode", true)
    } else {
        editor.putBoolean("safety_legacy_mode", true)
        true
    }

    //行车日志落盘总开关(默认开)：设置页"行车日志(调试用)"默认勾选，见 NavRuntimeState.drive_log_en
    drive_log_en = if (prefs.contains("drive_log_en")) {
        prefs.getBoolean("drive_log_en", true)
    } else {
        editor.putBoolean("drive_log_en", true)
        true
    }

    // ---- 以下为实线放宽/进匝道时序参数：首次启动把默认值写入 prefs ----
    //   目的：1) DebugReceiver 的 get 从 prefs 读，不写就会出现"显示 0"的假象；
    //        2) 车上热调过的值能持久化，重启后保持。

    //实线判据"临匝道放宽"总开关(默认开，仅导航变道生效)
    solid_relax_en = if (prefs.contains("solid_relax_en")) {
        prefs.getBoolean("solid_relax_en", true)
    } else {
        editor.putBoolean("solid_relax_en", true)
        true
    }

    //在"进匝道距离"之前多少米开始解除实线判定(默认 15m)
    solid_relax_margin_m = if (prefs.contains("solid_relax_margin_m")) {
        prefs.getFloat("solid_relax_margin_m", 15f)
    } else {
        editor.putFloat("solid_relax_margin_m", 15f)
        15f
    }

    //"车辆已靠边"抑制变道(默认开)：该侧实线(+高速该侧车道数<=1)持续5秒 → 禁止继续往该方向变道
    last_lane_suppress_en = if (prefs.contains("last_lane_suppress_en")) {
        prefs.getBoolean("last_lane_suppress_en", true)
    } else {
        editor.putBoolean("last_lane_suppress_en", true)
        true
    }

    //连续岔路判定：下一步岔路距离上限(m，默认 500)
    fork_chain_dist_m = if (prefs.contains("fork_chain_dist_m")) {
        prefs.getInt("fork_chain_dist_m", 500)
    } else {
        editor.putInt("fork_chain_dist_m", 500)
        500
    }

    //临匝道放宽窗口偏移量(m，默认 高速10 / 公路5)：窗口 = 进匝道距离 + 本值
    //  窗口内同时解除：实线判据 / 激光盲区 / "车辆已靠边"抑制(三者同源)
    solid_relax_offset_h = if (prefs.contains("solid_relax_offset_h")) {
        prefs.getInt("solid_relax_offset_h", 10)
    } else {
        editor.putInt("solid_relax_offset_h", 10)
        10
    }
    solid_relax_offset_l = if (prefs.contains("solid_relax_offset_l")) {
        prefs.getInt("solid_relax_offset_l", 5)
    } else {
        editor.putInt("solid_relax_offset_l", 5)
        5
    }

    //导航靠边/进匝道：语音后等待(默认 0=不等，播报后立即下发)
    navi_edge_speak_delay_ms = if (prefs.contains("navi_edge_speak_delay_ms")) {
        prefs.getInt("navi_edge_speak_delay_ms", 0).toLong()
    } else {
        editor.putInt("navi_edge_speak_delay_ms", 0)
        0L
    }

    //导航靠边/进匝道：Prepare 状态超时(默认 700ms，普通变道仍为 2000ms)
    navi_edge_atc_timeout_ms = if (prefs.contains("navi_edge_atc_timeout_ms")) {
        prefs.getInt("navi_edge_atc_timeout_ms", 700).toLong()
    } else {
        editor.putInt("navi_edge_atc_timeout_ms", 700)
        700L
    }


    //侧向安全去抖确认帧数(约 = 帧数 × 100ms)：调大更保守/反应慢，调小更灵敏/可能误放行
    safeConfirmFrames = if (prefs.contains("safe_confirm_frames")) {
        prefs.getInt("safe_confirm_frames", 5)
    } else {
        editor.putInt("safe_confirm_frames", 5)
        5
    }.coerceIn(1, 30)

    //导航变道(靠边/进匝道)专用的去抖帧数：错过路口代价大，默认比超车更快放行
    safeConfirmFramesNavi = if (prefs.contains("safe_confirm_frames_navi")) {
        prefs.getInt("safe_confirm_frames_navi", 2)
    } else {
        editor.putInt("safe_confirm_frames_navi", 2)
        2
    }.coerceIn(1, 30)

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
    app_lidar_self_check = if (prefs.contains("app_lidar_self_check")) {
        prefs.getBoolean("app_lidar_self_check", true)
    } else {
        editor.putBoolean("app_lidar_self_check", true)
        true
    }
    app_thresh_follow_cp = if (prefs.contains("app_thresh_follow_cp")) {
        prefs.getBoolean("app_thresh_follow_cp", true)
    } else {
        editor.putBoolean("app_thresh_follow_cp", true)
        true
    }
    //有推荐/禁行车道时禁止超车(超车设置页开关，默认开)
    //  导航车道数组里出现 "*"(推荐) 或 "X"(禁行) → 本帧不允许超车(实时判定，见 autoOvertake)
    overtake_block_on_nav_lane_hint = if (prefs.contains("overtake_block_on_nav_lane_hint")) {
        prefs.getBoolean("overtake_block_on_nav_lane_hint", true)
    } else {
        editor.putBoolean("overtake_block_on_nav_lane_hint", true)
        true
    }
    lidar_front_dist_mode = prefs.getInt("lidar_front_dist_mode", 0)
    lidar_front_dist_m = prefs.getFloat("lidar_front_dist_m", 5f)
    lidar_front_time_s = prefs.getFloat("lidar_front_time_s", 1.2f)
    lidar_rear_dist_mode = prefs.getInt("lidar_rear_dist_mode", 0)
    lidar_rear_dist_m = prefs.getFloat("lidar_rear_dist_m", 10f)
    lidar_rear_time_s = prefs.getFloat("lidar_rear_time_s", 1.5f)
    lidar_front_vrel_time_s = prefs.getFloat("lidar_front_vrel_time_s", 4f)
    lidar_rear_vrel_time_s = prefs.getFloat("lidar_rear_vrel_time_s", 4f)

    overtake_reverse_cooldown_ms = (if (prefs.contains("overtake_reverse_cooldown_s")) {
        prefs.getInt("overtake_reverse_cooldown_s", 20)
    } else {
        editor.putInt("overtake_reverse_cooldown_s", 20)
        20
    }).coerceIn(0, 120) * 1000L

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

    //参数应用（Python 版已移除，参数由 Kotlin 侧直接使用）
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

        //声音请求（原 Python sound_center 轮询，现由 SoundCenter 直接提供）
        val sound = SoundCenter.take()
        if (sound.isNotEmpty() && app_speak) {
            SoundPlayer.play(this, sound)
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

