package com.carrot.amapnavi

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingActivity : AppCompatActivity() {

    private lateinit var cbNaviTrafficAssist: CheckBox
    private lateinit var cbCpTrafficAssist: CheckBox
    private lateinit var cbRedLightTrafficAssist: CheckBox

    private lateinit var cbLayoutCpMinSpeedLimt : LinearLayout
    private lateinit var cbLayoutRedLightMinSpeedLimt : LinearLayout
    private lateinit var cbLayoutNaviTrafficAssistTime : LinearLayout
    private lateinit var etLayoutLaneDetectInterval : LinearLayout

    private lateinit var cbTunnelDecSpeed: CheckBox
    private lateinit var cbSmoothSpeedLimit: CheckBox

    private lateinit var cbLayoutVisionLaneMode : LinearLayout
    private lateinit var rgVisionLaneMode: RadioGroup
    // 超车相关设置已移到 OvertakeSettingActivity（入口在超车诊断页里）

    private lateinit var cbAppSpeak: CheckBox
    private lateinit var cbAppLog: CheckBox
    private lateinit var cbLaneShow: CheckBox
    private lateinit var cbVideoShow: CheckBox
    private lateinit var cbVideoShowFast: CheckBox
    private lateinit var cbLaneDetect: CheckBox
    private lateinit var cbLaneColor: CheckBox

    private lateinit var etRedLightMinSpeedLimt: EditText
    private lateinit var btnMinusRedLightMinSpeedLimt: Button
    private lateinit var btnPlusRedLightMinSpeedLimt: Button

    private lateinit var etCpMinSpeedLimt: EditText
    private lateinit var btnMinusCpMinSpeedLimt: Button
    private lateinit var btnPlusCpMinSpeedLimt: Button

    private lateinit var etRoadSpeedLimitPercent: EditText
    private lateinit var btnMinusRoadSpeedLimitPercent: Button
    private lateinit var btnPlusRoadSpeedLimitPercent: Button

    private lateinit var etRoadSpeedLimitKph: EditText
    private lateinit var btnMinusRoadSpeedLimitKph: Button
    private lateinit var btnPlusRoadSpeedLimitKph: Button

    private lateinit var etOffsetHighway: EditText
    private lateinit var btnMinusHighway: Button
    private lateinit var btnPlusHighway: Button

    private lateinit var etOffsetRoad: EditText
    private lateinit var btnMinusRoad: Button
    private lateinit var btnPlusRoad: Button

    private lateinit var etTopPad: EditText
    private lateinit var btnMinusTopPad: Button
    private lateinit var btnPlusTopPad: Button

    private lateinit var etMinSpeedLimit: EditText
    private lateinit var btnMinusMinSpeedLimit: Button
    private lateinit var btnPlusMinSpeedLimit: Button

    private lateinit var etMaxSpeedLimit: EditText
    private lateinit var btnMinusMaxSpeedLimit: Button
    private lateinit var btnPlusMaxSpeedLimit: Button

    private lateinit var etStationDistOffset: EditText
    private lateinit var btnMinusStationDistOffset: Button
    private lateinit var btnPlusStationDistOffset: Button

    private lateinit var etStationSpeed: EditText
    private lateinit var btnMinusStationSpeed: Button
    private lateinit var btnPlusStationSpeed: Button

    private lateinit var etLaneDetectInterval: EditText
    private lateinit var btnMinusLaneDetectInterval: Button
    private lateinit var btnPlusLaneDetectInterval: Button

    private lateinit var etStationKeepTime: EditText
    private lateinit var btnMinusStationKeepTime: Button
    private lateinit var btnPlusStationKeepTime: Button

    private lateinit var etNaviTrafficAssistTime: EditText
    private lateinit var btnMinusNaviTrafficAssistTime: Button
    private lateinit var btnPlusNaviTrafficAssistTime: Button

    private lateinit var btnSaveSetting: Button
    private lateinit var btnCancelSetting: Button

    private val prefsName = "user_settings"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setting)

        cbNaviTrafficAssist = findViewById(R.id.cbNaviTrafficAssist)
        cbCpTrafficAssist = findViewById(R.id.cbCpTrafficAssist)
        cbRedLightTrafficAssist = findViewById(R.id.cbRedLightTrafficAssist)

        cbLayoutCpMinSpeedLimt = findViewById(R.id.cbLayoutCpMinSpeedLimt)
        cbLayoutRedLightMinSpeedLimt = findViewById(R.id.cbLayoutRedLightMinSpeedLimt)
        cbLayoutNaviTrafficAssistTime = findViewById(R.id.cbLayoutNaviTrafficAssistTime)
        etLayoutLaneDetectInterval = findViewById(R.id.etLayoutLaneDetectInterval)

        cbTunnelDecSpeed = findViewById(R.id.cbTunnelDecSpeed)
        cbAppSpeak = findViewById(R.id.cbAppSpeak)
        cbAppLog = findViewById(R.id.cbAppLog)

        cbLayoutVisionLaneMode = findViewById(R.id.cbLayoutVisionLaneMode)
        rgVisionLaneMode = findViewById(R.id.rgVisionLaneMode)
        // (超车设置在 OvertakeSettingActivity)

        cbSmoothSpeedLimit = findViewById(R.id.cbSmoothSpeedLimit)
        cbLaneShow = findViewById(R.id.cbLaneShow)
        cbVideoShow = findViewById(R.id.cbVideoShow)
        cbVideoShowFast = findViewById(R.id.cbVideoShowFast)
        cbLaneDetect = findViewById(R.id.cbLaneDetect)
        cbLaneColor = findViewById(R.id.cbLaneColor)

        etRedLightMinSpeedLimt = findViewById(R.id.etRedLightMinSpeedLimt)
        btnMinusRedLightMinSpeedLimt = findViewById(R.id.btnMinusRedLightMinSpeedLimt)
        btnPlusRedLightMinSpeedLimt = findViewById(R.id.btnPlusRedLightMinSpeedLimt)

        etCpMinSpeedLimt = findViewById(R.id.etCpMinSpeedLimt)
        btnMinusCpMinSpeedLimt = findViewById(R.id.btnMinusCpMinSpeedLimt)
        btnPlusCpMinSpeedLimt = findViewById(R.id.btnPlusCpMinSpeedLimt)

        etRoadSpeedLimitPercent = findViewById(R.id.etRoadSpeedLimitPercent)
        btnMinusRoadSpeedLimitPercent = findViewById(R.id.btnMinusRoadSpeedLimitPercent)
        btnPlusRoadSpeedLimitPercent = findViewById(R.id.btnPlusRoadSpeedLimitPercent)

        etRoadSpeedLimitKph = findViewById(R.id.etRoadSpeedLimitKph)
        btnMinusRoadSpeedLimitKph = findViewById(R.id.btnMinusRoadSpeedLimitKph)
        btnPlusRoadSpeedLimitKph = findViewById(R.id.btnPlusRoadSpeedLimitKph)

        etOffsetHighway = findViewById(R.id.etOffsetHighway)
        btnMinusHighway = findViewById(R.id.btnMinusHighway)
        btnPlusHighway = findViewById(R.id.btnPlusHighway)

        etOffsetRoad = findViewById(R.id.etOffsetRoad)
        btnMinusRoad = findViewById(R.id.btnMinusRoad)
        btnPlusRoad = findViewById(R.id.btnPlusRoad)

        etTopPad= findViewById(R.id.etTopPad)
        btnMinusTopPad = findViewById(R.id.btnMinusTopPad)
        btnPlusTopPad = findViewById(R.id.btnPlusTopPad)

        etMinSpeedLimit = findViewById(R.id.etMinSpeedLimit)
        btnMinusMinSpeedLimit = findViewById(R.id.btnMinusMinSpeedLimit)
        btnPlusMinSpeedLimit = findViewById(R.id.btnPlusMinSpeedLimit)

        etMaxSpeedLimit = findViewById(R.id.etMaxSpeedLimit)
        btnMinusMaxSpeedLimit = findViewById(R.id.btnMinusMaxSpeedLimit)
        btnPlusMaxSpeedLimit = findViewById(R.id.btnPlusMaxSpeedLimit)

        etStationDistOffset = findViewById(R.id.etStationDistOffset)
        btnMinusStationDistOffset = findViewById(R.id.btnMinusStationDistOffset)
        btnPlusStationDistOffset = findViewById(R.id.btnPlusStationDistOffset)

        etStationSpeed = findViewById(R.id.etStationSpeed)
        btnMinusStationSpeed = findViewById(R.id.btnMinusStationSpeed)
        btnPlusStationSpeed = findViewById(R.id.btnPlusStationSpeed)

        etLaneDetectInterval = findViewById(R.id.etLaneDetectInterval)
        btnMinusLaneDetectInterval = findViewById(R.id.btnMinusLaneDetectInterval)
        btnPlusLaneDetectInterval = findViewById(R.id.btnPlusLaneDetectInterval)

        etStationKeepTime = findViewById(R.id.etStationKeepTime)
        btnMinusStationKeepTime = findViewById(R.id.btnMinusStationKeepTime)
        btnPlusStationKeepTime = findViewById(R.id.btnPlusStationKeepTime)

        etNaviTrafficAssistTime = findViewById(R.id.etNaviTrafficAssistTime)
        btnMinusNaviTrafficAssistTime = findViewById(R.id.btnMinusNaviTrafficAssistTime)
        btnPlusNaviTrafficAssistTime = findViewById(R.id.btnPlusNaviTrafficAssistTime)

        btnSaveSetting = findViewById(R.id.btnSaveSetting)
        btnCancelSetting = findViewById(R.id.btnCancelSetting)

        loadSettings()
        setupButtons()
    }

    private fun setupButtons() {
        fun changeValue(editText: EditText, delta: Int, min: Int = 0, max: Int = Int.MAX_VALUE) {
            val current = editText.text.toString().toIntOrNull() ?: min
            val newValue = (current + delta).coerceIn(min, max)
            editText.setText(newValue.toString())
        }

        btnMinusMinSpeedLimit.setOnClickListener { changeValue(etMinSpeedLimit, -5) }
        btnPlusMinSpeedLimit.setOnClickListener { changeValue(etMinSpeedLimit, 5) }

        btnMinusMaxSpeedLimit.setOnClickListener { changeValue(etMaxSpeedLimit, -5) }
        btnPlusMaxSpeedLimit.setOnClickListener { changeValue(etMaxSpeedLimit, 5) }

        btnMinusStationDistOffset.setOnClickListener { changeValue(etStationDistOffset, -1) }
        btnPlusStationDistOffset.setOnClickListener { changeValue(etStationDistOffset, 1) }

        btnMinusStationSpeed.setOnClickListener { changeValue(etStationSpeed, -1) }
        btnPlusStationSpeed.setOnClickListener { changeValue(etStationSpeed, 1) }

        btnMinusLaneDetectInterval.setOnClickListener { changeValue(etLaneDetectInterval, -10) }
        btnPlusLaneDetectInterval.setOnClickListener { changeValue(etLaneDetectInterval, 10) }

        btnMinusStationKeepTime.setOnClickListener { changeValue(etStationKeepTime, -1) }
        btnPlusStationKeepTime.setOnClickListener { changeValue(etStationKeepTime, 1) }

        btnMinusNaviTrafficAssistTime.setOnClickListener { changeValue(etNaviTrafficAssistTime, -1) }
        btnPlusNaviTrafficAssistTime.setOnClickListener { changeValue(etNaviTrafficAssistTime, 1) }

        btnMinusRedLightMinSpeedLimt.setOnClickListener { changeValue(etRedLightMinSpeedLimt, -1) }
        btnPlusRedLightMinSpeedLimt.setOnClickListener { changeValue(etRedLightMinSpeedLimt, 1) }

        btnMinusCpMinSpeedLimt.setOnClickListener { changeValue(etCpMinSpeedLimt, -1) }
        btnPlusCpMinSpeedLimt.setOnClickListener { changeValue(etCpMinSpeedLimt, 1) }

        btnMinusRoadSpeedLimitPercent.setOnClickListener { changeValue(etRoadSpeedLimitPercent, -1) }
        btnPlusRoadSpeedLimitPercent.setOnClickListener { changeValue(etRoadSpeedLimitPercent, 1) }

        btnMinusRoadSpeedLimitKph.setOnClickListener { changeValue(etRoadSpeedLimitKph, -1) }
        btnPlusRoadSpeedLimitKph.setOnClickListener { changeValue(etRoadSpeedLimitKph, 1) }

        btnMinusHighway.setOnClickListener { changeValue(etOffsetHighway, -1) }
        btnPlusHighway.setOnClickListener { changeValue(etOffsetHighway, 1) }

        btnMinusRoad.setOnClickListener { changeValue(etOffsetRoad, -1) }
        btnPlusRoad.setOnClickListener { changeValue(etOffsetRoad, 1) }

        btnMinusTopPad.setOnClickListener { changeValue(etTopPad, -5) }
        btnPlusTopPad.setOnClickListener { changeValue(etTopPad, 5) }

        btnSaveSetting.setOnClickListener {
            saveSettings()
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }

        cbNaviTrafficAssist.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // 当选中导航辅助停车时，让另外两个可选
                cbCpTrafficAssist.isEnabled = true
                cbRedLightTrafficAssist.isEnabled = true

                cbLayoutNaviTrafficAssistTime.visibility = View.VISIBLE
            } else {
                // 当取消选中时，取消勾选并禁用
                cbCpTrafficAssist.isChecked = false
                cbCpTrafficAssist.isEnabled = false

                cbRedLightTrafficAssist.isChecked = false
                cbRedLightTrafficAssist.isEnabled = false

                cbLayoutCpMinSpeedLimt.visibility = View.GONE
                cbLayoutRedLightMinSpeedLimt.visibility = View.GONE
                cbLayoutNaviTrafficAssistTime.visibility = View.GONE
            }
        }

        cbCpTrafficAssist.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                cbLayoutCpMinSpeedLimt.visibility = View.VISIBLE
            } else {
                cbLayoutCpMinSpeedLimt.visibility = View.GONE
            }
        }

        cbRedLightTrafficAssist.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                cbLayoutRedLightMinSpeedLimt.visibility = View.VISIBLE
            } else {
                cbLayoutRedLightMinSpeedLimt.visibility = View.GONE
            }
        }

        cbLaneDetect.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                etLayoutLaneDetectInterval.visibility = View.VISIBLE
            } else {
                etLayoutLaneDetectInterval.visibility = View.GONE
            }
        }

        cbAppSpeak.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                SoundPlayer.play(this, "engage")
            } else {
                SoundPlayer.play(this, "disengage")
            }
        }

        btnCancelSetting.setOnClickListener { finish() }

        // 顶部一排分页入口（跳走时关闭本页，保证一次返回回到主界面）
        findViewById<Button>(R.id.btnGotoNav).setOnClickListener {
            startActivity(android.content.Intent(this, NavSettingActivity::class.java))
            finish()
        }
        findViewById<Button>(R.id.btnGotoOvertake).setOnClickListener {
            startActivity(android.content.Intent(this, OvertakeSettingActivity::class.java))
            finish()
        }
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit()
                .putBoolean("navi_traffic_assist", cbNaviTrafficAssist.isChecked)
                .putBoolean("cp_traffic_assist", cbCpTrafficAssist.isChecked)
                .putBoolean("redlight_traffic_assist", cbRedLightTrafficAssist.isChecked)
                .putBoolean("tunnel_dec_speed", cbTunnelDecSpeed.isChecked)
                .putBoolean("app_speak", cbAppSpeak.isChecked)
                .putBoolean("app_log", cbAppLog.isChecked)
                .putBoolean("smooth_speed_limit", cbSmoothSpeedLimit.isChecked)
                .putBoolean("lane_show", cbLaneShow.isChecked)
                .putBoolean("video_show", cbVideoShow.isChecked)
                .putBoolean("video_show_fast", cbVideoShowFast.isChecked)
                .putBoolean("lane_detect", cbLaneDetect.isChecked)
                .putBoolean("lane_color", cbLaneColor.isChecked)
                .putInt("red_light_min_speed_limit", etRedLightMinSpeedLimt.text.toString().toIntOrNull() ?: 1)
                .putInt("cp_min_speed_limit", etCpMinSpeedLimt.text.toString().toIntOrNull() ?: 20)
                .putInt("road_speed_limit_percent", etRoadSpeedLimitPercent.text.toString().toIntOrNull() ?: 0)
                .putInt("road_speed_limit_kph", etRoadSpeedLimitKph.text.toString().toIntOrNull() ?: 0)
                .putInt("offset_highway", etOffsetHighway.text.toString().toIntOrNull() ?: 20)
                .putInt("offset_road", etOffsetRoad.text.toString().toIntOrNull() ?: 20)
                .putInt("top_pad_dp", etTopPad.text.toString().toIntOrNull() ?: 5)
                .putInt("min_speed_limit", etMinSpeedLimit.text.toString().toIntOrNull() ?: 30)
                .putInt("max_speed_limit", etMaxSpeedLimit.text.toString().toIntOrNull() ?: 130)
                .putInt("station_dist_offset", etStationDistOffset.text.toString().toIntOrNull() ?: 0)
                .putInt("station_speed", etStationSpeed.text.toString().toIntOrNull() ?: 15)
                .putInt("lane_detect_interval", etLaneDetectInterval.text.toString().toIntOrNull() ?: 500)
                .putInt("station_keep_time", etStationKeepTime.text.toString().toIntOrNull() ?: 0)
                .putInt("navi_traffic_assist_time", etNaviTrafficAssistTime.text.toString().toIntOrNull() ?: 10)

                .putInt("vision_lane_mode", when (rgVisionLaneMode.checkedRadioButtonId) {
                    R.id.rbVisionLaneFastMode -> 1
                    R.id.rbVisionLaneNormalMode -> 2
                    R.id.rbVisionLaneSlowMode -> 3
                    else -> 1   // 默认保守
                })
                // (超车相关设置由 OvertakeSettingActivity 保存)

                .apply()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        cbNaviTrafficAssist.isChecked = prefs.getBoolean("navi_traffic_assist", false)
        cbCpTrafficAssist.isChecked = prefs.getBoolean("cp_traffic_assist", false)
        cbRedLightTrafficAssist.isChecked = prefs.getBoolean("redlight_traffic_assist", false)
        cbTunnelDecSpeed.isChecked = prefs.getBoolean("tunnel_dec_speed", true)
        cbAppSpeak.isChecked = prefs.getBoolean("app_speak", false)
        cbAppLog.isChecked = prefs.getBoolean("app_log", false)
        cbSmoothSpeedLimit.isChecked = prefs.getBoolean("smooth_speed_limit", false)
        cbLaneShow.isChecked = prefs.getBoolean("lane_show", false)
        cbVideoShow.isChecked = prefs.getBoolean("video_show", false)
        cbVideoShowFast.isChecked = prefs.getBoolean("video_show_fast", false)
        cbLaneDetect.isChecked = prefs.getBoolean("lane_detect", true)
        cbLaneColor.isChecked = prefs.getBoolean("lane_color", false)
        etRedLightMinSpeedLimt.setText(prefs.getInt("red_light_min_speed_limit", 1).toString())
        etCpMinSpeedLimt.setText(prefs.getInt("cp_min_speed_limit", 20).toString())
        etRoadSpeedLimitPercent.setText(prefs.getInt("road_speed_limit_percent", 0).toString())
        etRoadSpeedLimitKph.setText(prefs.getInt("road_speed_limit_kph", 0).toString())
        etOffsetHighway.setText(prefs.getInt("offset_highway", 20).toString())
        etOffsetRoad.setText(prefs.getInt("offset_road", 15).toString())
        etTopPad.setText(prefs.getInt("top_pad_dp", 5).toString())
        etMinSpeedLimit.setText(prefs.getInt("min_speed_limit", 30).toString())
        etMaxSpeedLimit.setText(prefs.getInt("max_speed_limit", 130).toString())
        etStationDistOffset.setText(prefs.getInt("station_dist_offset", 0).toString())
        etStationSpeed.setText(prefs.getInt("station_speed", 0).toString())
        etLaneDetectInterval.setText(prefs.getInt("lane_detect_interval", 500).toString())
        etStationKeepTime.setText(prefs.getInt("station_keep_time", 15).toString())
        etNaviTrafficAssistTime.setText(prefs.getInt("navi_traffic_assist_time", 10).toString())

        val visionLaneMode = prefs.getInt("vision_lane_mode", 1) // 默认标准
        when (visionLaneMode) {
            1 -> rgVisionLaneMode.check(R.id.rbVisionLaneFastMode)
            2 -> rgVisionLaneMode.check(R.id.rbVisionLaneNormalMode)
            3 -> rgVisionLaneMode.check(R.id.rbVisionLaneSlowMode)
        }
        // (超车设置在 OvertakeSettingActivity 里加载)

        // 顶部留空加到内容布局上(车机适配)：值取"顶部留空"参数，与另外两个设置页一致
        val content = findViewById<LinearLayout>(R.id.settingContentLayout)
        val top_pad_dp = etTopPad.text.toString().toIntOrNull() ?: 5
        setTopPaddingDp(content, top_pad_dp)
    }

    fun setTopPaddingDp(view: View, dp: Int) {
        val px = (dp * view.resources.displayMetrics.density).toInt()
        view.setPadding(
            view.paddingLeft,
            px,
            view.paddingRight,
            view.paddingBottom
        )
    }

    companion object {
        fun getSettings(context: Context): Map<String, Any> {
            val prefs = context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
            return mapOf(
                    "navi_traffic_assist" to prefs.getBoolean("navi_traffic_assist", false),
                    "cp_traffic_assist" to prefs.getBoolean("cp_traffic_assist", false),
                    "redlight_traffic_assist" to prefs.getBoolean("redlight_traffic_assist", false),
                    "tunnel_dec_speed" to prefs.getBoolean("tunnel_dec_speed", true),
                    "app_speak" to prefs.getBoolean("app_speak", false),
                    "app_log" to prefs.getBoolean("app_log", false),
                    "app_navi" to prefs.getBoolean("app_navi", false),
                    "app_road_navi" to prefs.getBoolean("app_road_navi", false),
                    "navi_speak" to prefs.getBoolean("navi_speak", true),
                    "navi_speak_first" to prefs.getBoolean("navi_speak_first", true),
                    "navi_blind_dec_speed" to prefs.getBoolean("navi_blind_dec_speed", false),
                    "suggest_lane_en" to prefs.getBoolean("suggest_lane_en", false),
                    "dis_lane_en" to prefs.getBoolean("dis_lane_en", false),
                    "keep_left_en" to prefs.getBoolean("keep_left_en", false),
                    "keep_right_en" to prefs.getBoolean("keep_right_en", false),
                    "road_median" to prefs.getBoolean("road_median", true),
                    "road_right_first" to prefs.getBoolean("road_right_first", true),
                    "lane_prob_enable" to prefs.getBoolean("lane_prob_enable", false),
                    "vision_keep_right" to prefs.getBoolean("vision_keep_right", false),
                    "nav_turn" to prefs.getBoolean("nav_turn", false),
                    "nav_mode" to prefs.getInt("nav_mode", 2),
                    "smooth_speed_limit" to prefs.getBoolean("smooth_speed_limit", false),
                    "lane_show" to prefs.getBoolean("lane_show", false),
                    "video_show" to prefs.getBoolean("video_show", false),
                    "video_show_fast" to prefs.getBoolean("video_show_fast", false),
                    "lane_detect" to prefs.getBoolean("lane_detect", true),
                    "lane_color" to prefs.getBoolean("lane_color", false),
                    "navi_min_speed" to prefs.getInt("navi_min_speed", 20),
                    "red_light_min_speed_limit" to prefs.getInt("red_light_min_speed_limit", 1),
                    "cp_min_speed_limit" to prefs.getInt("cp_min_speed_limit", 20),
                    "road_speed_limit_percent" to prefs.getInt("road_speed_limit_percent", 0),
                    "road_speed_limit_kph" to prefs.getInt("road_speed_limit_kph", 0),
                    "offset_highway" to prefs.getInt("offset_highway", 20),
                    "offset_road" to prefs.getInt("offset_road", 20),
                    "top_pad_dp" to prefs.getInt("top_pad_dp", 5),
                    "min_speed_limit" to prefs.getInt("min_speed_limit", 30),
                    "max_speed_limit" to prefs.getInt("max_speed_limit", 130),
                    "station_dist_offset" to prefs.getInt("station_dist_offset", 0),
                    "station_speed" to prefs.getInt("station_speed", 15),
                    "lane_detect_interval" to prefs.getInt("lane_detect_interval", 500),
                    "station_keep_time" to prefs.getInt("station_keep_time", 0),
                    "navi_traffic_assist_time" to prefs.getInt("navi_traffic_assist_time", 10),
                    "atc_dist_h" to prefs.getInt("atc_dist_h", 2000),
                    "atc_dist_l" to prefs.getInt("atc_dist_l", 500),
                    "fork_dist_h" to prefs.getInt("fork_dist_h", 80),
                    "fork_dist_l" to prefs.getInt("fork_dist_l", 30),

                    "app_overtake" to prefs.getBoolean("app_overtake", false),
                    "app_road_overtake" to prefs.getBoolean("app_road_overtake", false),
                    "lane_overtake" to prefs.getBoolean("lane_overtake", false),
                    "overtake_mode" to prefs.getInt("overtake_mode", 2),
                    "vision_lane_mode" to prefs.getInt("vision_lane_mode", 1),
            )
        }
    }
}
