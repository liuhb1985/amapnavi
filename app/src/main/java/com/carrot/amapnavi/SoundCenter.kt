package com.carrot.amapnavi

/* =========================================================
 * 声音请求中心（原 Python sound_center.py 的 Kotlin 版本）
 *
 * CP 的车辆状态报文里带 "sound":N 索引，这里按索引查到 res/raw 里的文件名，
 * 由 pollSound() 轮询取走并播放，语义与 Python 版一致：
 *   一个请求只播一次，播完即清空。
 *
 * 索引表与 Python 版 SOUND_TABLE 完全一致，老版本 CP 发什么就播什么。
 * ========================================================= */
object SoundCenter {

    /** 索引 → res/raw 文件名（不含扩展名），空串表示该索引无声音 */
    private val SOUND_TABLE = mapOf(
        0 to "",
        1 to "audio_pre_lane_change",          //audioPreLaneChange
        2 to "audio_lane_change",              //audioLaneChange
        3 to "audio_turn",                     //audioTurn
        4 to "audio_atc_cancel",               //audioAtcCancel
        5 to "audio_atc_resume",               //audioAtcResume
        6 to "audio_car_watchout",             //laneChangeBlocked
        7 to "audio_pre_lane_left",            //audioPreLaneChangeLeft
        8 to "audio_pre_lane_right",           //audioPreLaneChangeRight
        9 to "audio_lane_change_ok",           //audioLaneChangeOk
        10 to "audio_last_lane",               //audioLastLane
        11 to "audio_new_lane",                //audioNewLane
        12 to "audio_lane_change_end",         //audioLaneChangeEnd
        13 to "traffic_sign_green",
        14 to "traffic_sign_changed",
        15 to "audio_traffic_error",
        16 to "audio_lane_change_cancel",
        17 to "",
        18 to "",
        19 to "",
        20 to "engage",
        21 to "disengage",
        22 to "prompt",
        23 to "prompt_distracted",
        24 to "warning_soft",
        25 to "warning_immediate",
        26 to "tici_engaged",
        27 to "tici_disengaged",
        28 to "audio_stopstop",
        29 to "audio_stopping",
        30 to "audio_auto_hold",
        31 to "audio_1",
        32 to "audio_2",
        33 to "audio_3",
        34 to "audio_4",
        35 to "audio_5",
        36 to "audio_6",
        37 to "audio_7",
        38 to "audio_8",
        39 to "audio_9",
        40 to "audio_10",
    )

    private val lock = Any()

    /** 待播报的声音文件名（null 表示没有请求） */
    private var pending: String? = null

    /** CP 报文里的 sound 索引 → 记录一次播报请求 */
    fun requestIndex(index: Int) {
        val name = SOUND_TABLE[index]
        if (name.isNullOrEmpty()) return
        synchronized(lock) { pending = name }
    }

    /** 直接按文件名请求播报（调试/其它模块用） */
    fun request(name: String) {
        if (name.isEmpty()) return
        synchronized(lock) { pending = name }
    }

    /** 取走待播报的声音，没有则返回空串（对应 Python 的 get_requested_sound） */
    fun take(): String = synchronized(lock) {
        val s = pending
        pending = null
        s ?: ""
    }
}
