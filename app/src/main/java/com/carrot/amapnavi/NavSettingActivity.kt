package com.carrot.amapnavi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 车道领航专用设置页（入口在设置页顶部的"领航参数"按钮）。
 *
 * 与主设置页(SettingActivity)共用同一份 SharedPreferences("user_settings")。
 * 数值沿用原有单位：atc_dist_h/l 与 fork_dist_h/l 保持原样(与旧设置页一致)。
 */
class NavSettingActivity : AppCompatActivity() {

    private lateinit var cbAppNavi: CheckBox
    private lateinit var cbAppRoadNavi: CheckBox
    private lateinit var rgNavMode: RadioGroup
    private lateinit var cbSuggestLane: CheckBox
    private lateinit var cbDisLane: CheckBox
    private lateinit var cbKeepLeft: CheckBox
    private lateinit var cbKeepRight: CheckBox
    private lateinit var cbSpeak: CheckBox
    private lateinit var cbSpeakFirst: CheckBox
    private lateinit var cbBlindDecSpeed: CheckBox
    private lateinit var cbRoadMedian: CheckBox
    private lateinit var cbRightFirst: CheckBox
    private lateinit var cbNavTurn: CheckBox
    private lateinit var cbVisionKeepRight: CheckBox
    private lateinit var cbLaneProb: CheckBox
    private lateinit var cbSafetyLegacy: CheckBox
    private lateinit var cbLastLaneSuppress: CheckBox
    private lateinit var cbDriveLog: CheckBox
    private lateinit var cbRampSlowdown: CheckBox

    private lateinit var etNaviMinSpeed: EditText
    private lateinit var etAtcDistH: EditText
    private lateinit var etAtcDistL: EditText
    private lateinit var etForkDistH: EditText
    private lateinit var etForkDistL: EditText
    // 临匝道放宽窗口偏移量：窗口 = 分岔距离(fork_dist_h/l) + 本值
    private lateinit var etRelaxOffsetH: EditText
    private lateinit var etRelaxOffsetL: EditText
    private lateinit var etRampDistH: EditText
    private lateinit var etRampDistL: EditText
    private lateinit var etRampSpeedH: EditText
    private lateinit var etRampSpeedL: EditText
    private lateinit var etRampHold: EditText
    // 匝道参数组(未勾选开关时整组隐藏)
    private lateinit var tvRampDesc: TextView
    private lateinit var tvRampDistTitle: TextView
    private lateinit var tvRampSpeedTitle: TextView
    private lateinit var tvRampHoldTitle: TextView
    private lateinit var layoutRampDist: LinearLayout
    private lateinit var layoutRampSpeed: LinearLayout
    private lateinit var layoutRampHold: LinearLayout

    private val prefsName = "user_settings"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nav_setting)

        cbAppNavi = findViewById(R.id.cbNavSettingAppNavi)
        cbAppRoadNavi = findViewById(R.id.cbNavSettingAppRoadNavi)
        rgNavMode = findViewById(R.id.rgNavSettingMode)
        cbSuggestLane = findViewById(R.id.cbNavSettingSuggestLane)
        cbDisLane = findViewById(R.id.cbNavSettingDisLane)
        cbKeepLeft = findViewById(R.id.cbNavSettingKeepLeft)
        cbKeepRight = findViewById(R.id.cbNavSettingKeepRight)
        cbSpeak = findViewById(R.id.cbNavSettingSpeak)
        cbSpeakFirst = findViewById(R.id.cbNavSettingSpeakFirst)
        cbBlindDecSpeed = findViewById(R.id.cbNavSettingBlindDecSpeed)
        cbRoadMedian = findViewById(R.id.cbNavSettingRoadMedian)
        cbRightFirst = findViewById(R.id.cbNavSettingRightFirst)
        cbNavTurn = findViewById(R.id.cbNavSettingNavTurn)
        cbVisionKeepRight = findViewById(R.id.cbNavSettingVisionKeepRight)
        cbLaneProb = findViewById(R.id.cbNavSettingLaneProb)
        cbSafetyLegacy = findViewById(R.id.cbNavSettingSafetyLegacy)
        cbLastLaneSuppress = findViewById(R.id.cbNavSettingLastLaneSuppress)
        cbDriveLog = findViewById(R.id.cbNavSettingDriveLog)
        etNaviMinSpeed = findViewById(R.id.etNavNaviMinSpeed)
        etAtcDistH = findViewById(R.id.etNavAtcDistH)
        etAtcDistL = findViewById(R.id.etNavAtcDistL)
        etForkDistH = findViewById(R.id.etNavForkDistH)
        etForkDistL = findViewById(R.id.etNavForkDistL)
        etRelaxOffsetH = findViewById(R.id.etNavRelaxOffsetH)
        etRelaxOffsetL = findViewById(R.id.etNavRelaxOffsetL)
        cbRampSlowdown = findViewById(R.id.cbNavSettingRampSlowdown)
        etRampDistH = findViewById(R.id.etNavRampDistH)
        etRampDistL = findViewById(R.id.etNavRampDistL)
        etRampSpeedH = findViewById(R.id.etNavRampSpeedH)
        etRampSpeedL = findViewById(R.id.etNavRampSpeedL)
        etRampHold = findViewById(R.id.etNavRampHold)
        tvRampDesc = findViewById(R.id.tvRampDesc)
        tvRampDistTitle = findViewById(R.id.tvRampDistTitle)
        tvRampSpeedTitle = findViewById(R.id.tvRampSpeedTitle)
        tvRampHoldTitle = findViewById(R.id.tvRampHoldTitle)
        layoutRampDist = findViewById(R.id.layoutRampDist)
        layoutRampSpeed = findViewById(R.id.layoutRampSpeed)
        layoutRampHold = findViewById(R.id.layoutRampHold)

        // 未勾选"匝道/分岔提前减速"时，下面的距离/速度/维持设置自动隐藏
        cbRampSlowdown.setOnCheckedChangeListener { _, checked ->
            applyRampGroupVisible(checked)
        }
        applyRampGroupVisible(cbRampSlowdown.isChecked)

        // 隐藏"领航转弯"与"使用车道置信度"(保留控件与保存逻辑，不影响已有设置值)
        cbNavTurn.visibility = View.GONE
        cbLaneProb.visibility = View.GONE

        load()

        bindStep(R.id.btnMinusNaviMinSpeed, R.id.btnPlusNaviMinSpeed, etNaviMinSpeed, 1, 0, 120)
        bindStep(R.id.btnMinusAtcDistH, R.id.btnPlusAtcDistH, etAtcDistH, 100, 0, 100000)
        bindStep(R.id.btnMinusAtcDistL, R.id.btnPlusAtcDistL, etAtcDistL, 50, 0, 100000)
        bindStep(R.id.btnMinusForkDistH, R.id.btnPlusForkDistH, etForkDistH, 10, 0, 100000)
        bindStep(R.id.btnMinusForkDistL, R.id.btnPlusForkDistL, etForkDistL, 10, 0, 100000)
        // 放宽偏移量：0 = 关闭该窗口(只保留连续岔路判定)
        bindStep(R.id.btnMinusRelaxOffsetH, R.id.btnPlusRelaxOffsetH, etRelaxOffsetH, 50, 0, 2000)
        bindStep(R.id.btnMinusRelaxOffsetL, R.id.btnPlusRelaxOffsetL, etRelaxOffsetL, 50, 0, 2000)
        bindStep(R.id.btnMinusRampDistH, R.id.btnPlusRampDistH, etRampDistH, 100, 0, 5000)
        bindStep(R.id.btnMinusRampDistL, R.id.btnPlusRampDistL, etRampDistL, 50, 0, 5000)
        bindStep(R.id.btnMinusRampSpeedH, R.id.btnPlusRampSpeedH, etRampSpeedH, 5, 0, 150)
        bindStep(R.id.btnMinusRampSpeedL, R.id.btnPlusRampSpeedL, etRampSpeedL, 5, 0, 150)
        bindStep(R.id.btnMinusRampHold, R.id.btnPlusRampHold, etRampHold, 5, 0, 300)

        // 分页入口（跳走时关闭本页，保证一次返回回到主界面）
        findViewById<Button>(R.id.btnGotoGeneral).setOnClickListener {
            startActivity(Intent(this, SettingActivity::class.java))
            finish()
        }
        findViewById<Button>(R.id.btnGotoOvertake).setOnClickListener {
            startActivity(Intent(this, OvertakeSettingActivity::class.java))
            finish()
        }

        highlightSettingTab(R.id.btnGotoNav)

        // 顶部留空：与车机适配参数 top_pad_dp 保持一致
        setTopPaddingDp(
                findViewById(R.id.navSettingContent),
                getSharedPreferences(prefsName, Context.MODE_PRIVATE).getInt("top_pad_dp", 5))
    }

    private fun setTopPaddingDp(view: View, dp: Int) {
        val px = (dp * resources.displayMetrics.density).toInt()
        view.setPadding(view.paddingLeft, px, view.paddingRight, view.paddingBottom)

        findViewById<Button>(R.id.btnSaveNavSetting).setOnClickListener {
            save()
            finish()
        }
        findViewById<Button>(R.id.btnCancelNavSetting).setOnClickListener { finish() }
    }

    // 匝道参数组可见性：开关未勾选时整组隐藏(控件与保存逻辑保留，设置值不丢)
    private fun applyRampGroupVisible(show: Boolean) {
        val vis = if (show) View.VISIBLE else View.GONE
        arrayOf<View>(tvRampDesc, tvRampDistTitle, layoutRampDist,
                tvRampSpeedTitle, layoutRampSpeed, tvRampHoldTitle, layoutRampHold)
                .forEach { it.visibility = vis }
    }

    private fun bindStep(minusId: Int, plusId: Int, view: EditText, step: Int, min: Int, max: Int) {
        findViewById<Button>(minusId).setOnClickListener { changeValue(view, -step, min, max) }
        findViewById<Button>(plusId).setOnClickListener { changeValue(view, step, min, max) }
    }

    private fun changeValue(view: EditText, delta: Int, min: Int, max: Int) {
        val cur = view.text.toString().toIntOrNull() ?: min
        view.setText((cur + delta).coerceIn(min, max).toString())
    }

    private fun save() {
        val mode = when (rgNavMode.checkedRadioButtonId) {
            R.id.rbNavSettingFastMode -> 1
            R.id.rbNavSettingSlowMode -> 3
            else -> 2
        }
        getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("app_navi", cbAppNavi.isChecked)
                .putBoolean("app_road_navi", cbAppRoadNavi.isChecked)
                .putInt("nav_mode", mode)
                .putBoolean("suggest_lane_en", cbSuggestLane.isChecked)
                .putBoolean("dis_lane_en", cbDisLane.isChecked)
                .putBoolean("keep_left_en", cbKeepLeft.isChecked)
                .putBoolean("keep_right_en", cbKeepRight.isChecked)
                .putBoolean("navi_speak", cbSpeak.isChecked)
                .putBoolean("navi_speak_first", cbSpeakFirst.isChecked)
                .putBoolean("navi_blind_dec_speed", cbBlindDecSpeed.isChecked)
                .putBoolean("road_median", cbRoadMedian.isChecked)
                .putBoolean("road_right_first", cbRightFirst.isChecked)
                .putBoolean("nav_turn", cbNavTurn.isChecked)
                .putBoolean("vision_keep_right", cbVisionKeepRight.isChecked)
                .putBoolean("lane_prob_enable", cbLaneProb.isChecked)
                .putBoolean("safety_legacy_mode", cbSafetyLegacy.isChecked)
                .putBoolean("last_lane_suppress_en", cbLastLaneSuppress.isChecked)
                .putBoolean("drive_log_en", cbDriveLog.isChecked)
                .putInt("navi_min_speed", etNaviMinSpeed.text.toString().toIntOrNull() ?: 20)
                .putInt("atc_dist_h", etAtcDistH.text.toString().toIntOrNull() ?: 2000)
                .putInt("atc_dist_l", etAtcDistL.text.toString().toIntOrNull() ?: 500)
                .putInt("fork_dist_h", etForkDistH.text.toString().toIntOrNull() ?: 80)
                .putInt("fork_dist_l", etForkDistL.text.toString().toIntOrNull() ?: 30)
                .putInt("solid_relax_offset_h", etRelaxOffsetH.text.toString().toIntOrNull() ?: 10)
                .putInt("solid_relax_offset_l", etRelaxOffsetL.text.toString().toIntOrNull() ?: 5)
                .putBoolean("ramp_slowdown_en", cbRampSlowdown.isChecked)
                .putInt("ramp_slowdown_dist_h", etRampDistH.text.toString().toIntOrNull() ?: 400)
                .putInt("ramp_slowdown_dist_l", etRampDistL.text.toString().toIntOrNull() ?: 200)
                .putInt("ramp_slowdown_speed_h", etRampSpeedH.text.toString().toIntOrNull() ?: 60)
                .putInt("ramp_slowdown_speed_l", etRampSpeedL.text.toString().toIntOrNull() ?: 45)
                .putInt("ramp_hold_time", etRampHold.text.toString().toIntOrNull() ?: 6)
                .apply()

        // 变道判据旧版逻辑开关：立即生效，不必重启 App
        safety_legacy_mode = cbSafetyLegacy.isChecked
        // 行车日志落盘开关：立即生效(见 NavRuntimeState.drive_log_en)
        drive_log_en = cbDriveLog.isChecked
        // "车辆已靠边"抑制开关：立即生效(见 NavRuntimeState.last_lane_suppress_en)
        last_lane_suppress_en = cbLastLaneSuppress.isChecked
        // 临匝道放宽窗口偏移量：立即生效(见 solidLineRelaxed)
        solid_relax_offset_h = etRelaxOffsetH.text.toString().toIntOrNull() ?: 10
        solid_relax_offset_l = etRelaxOffsetL.text.toString().toIntOrNull() ?: 5
    }

    private fun load() {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        cbAppNavi.isChecked = prefs.getBoolean("app_navi", false)
        cbAppRoadNavi.isChecked = prefs.getBoolean("app_road_navi", false)
        when (prefs.getInt("nav_mode", 2)) {
            1 -> rgNavMode.check(R.id.rbNavSettingFastMode)
            3 -> rgNavMode.check(R.id.rbNavSettingSlowMode)
            else -> rgNavMode.check(R.id.rbNavSettingNormalMode)
        }
        cbSuggestLane.isChecked = prefs.getBoolean("suggest_lane_en", false)
        cbDisLane.isChecked = prefs.getBoolean("dis_lane_en", false)
        cbKeepLeft.isChecked = prefs.getBoolean("keep_left_en", false)
        cbKeepRight.isChecked = prefs.getBoolean("keep_right_en", false)
        cbSpeak.isChecked = prefs.getBoolean("navi_speak", true)
        cbSpeakFirst.isChecked = prefs.getBoolean("navi_speak_first", true)
        cbBlindDecSpeed.isChecked = prefs.getBoolean("navi_blind_dec_speed", false)
        cbRoadMedian.isChecked = prefs.getBoolean("road_median", true)
        cbRightFirst.isChecked = prefs.getBoolean("road_right_first", true)
        cbNavTurn.isChecked = prefs.getBoolean("nav_turn", false)
        cbVisionKeepRight.isChecked = prefs.getBoolean("vision_keep_right", false)
        cbLaneProb.isChecked = prefs.getBoolean("lane_prob_enable", false)
        cbSafetyLegacy.isChecked = prefs.getBoolean("safety_legacy_mode", true)
        cbLastLaneSuppress.isChecked = prefs.getBoolean("last_lane_suppress_en", true)
        cbDriveLog.isChecked = prefs.getBoolean("drive_log_en", true)
        etNaviMinSpeed.setText(prefs.getInt("navi_min_speed", 20).toString())
        etAtcDistH.setText(prefs.getInt("atc_dist_h", 2000).toString())
        etAtcDistL.setText(prefs.getInt("atc_dist_l", 500).toString())
        etForkDistH.setText(prefs.getInt("fork_dist_h", 80).toString())
        etForkDistL.setText(prefs.getInt("fork_dist_l", 30).toString())
        etRelaxOffsetH.setText(prefs.getInt("solid_relax_offset_h", 10).toString())
        etRelaxOffsetL.setText(prefs.getInt("solid_relax_offset_l", 5).toString())
        cbRampSlowdown.isChecked = prefs.getBoolean("ramp_slowdown_en", true)
        etRampDistH.setText(prefs.getInt("ramp_slowdown_dist_h", 400).toString())
        etRampDistL.setText(prefs.getInt("ramp_slowdown_dist_l", 200).toString())
        etRampSpeedH.setText(prefs.getInt("ramp_slowdown_speed_h", 60).toString())
        etRampSpeedL.setText(prefs.getInt("ramp_slowdown_speed_l", 45).toString())
        etRampHold.setText(prefs.getInt("ramp_hold_time", 6).toString())
    }
}
