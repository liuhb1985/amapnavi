package com.carrot.amapnavi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity

/**
 * 超车功能专用设置页（入口在"超车"诊断页里）。
 *
 * 与主设置页(SettingActivity)共用同一份 SharedPreferences("user_settings")，
 * 保存后 MainActivity 回到前台会重新读取（见 MainActivitySettings）。
 */
class OvertakeSettingActivity : AppCompatActivity() {

    private lateinit var rgOvertakeMode: RadioGroup
    private lateinit var cbHighway: CheckBox
    private lateinit var cbRoad: CheckBox
    private lateinit var cbLidarSelfCheck: CheckBox
    private lateinit var cbThreshFollowCp: CheckBox
    private lateinit var etReverseCooldown: EditText

    private val prefsName = "user_settings"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overtake_setting)

        rgOvertakeMode = findViewById(R.id.rgOvertakeSettingMode)
        cbHighway = findViewById(R.id.cbOvertakeSettingHighway)
        cbRoad = findViewById(R.id.cbOvertakeSettingRoad)
        cbLidarSelfCheck = findViewById(R.id.cbOvertakeSettingLidarSelfCheck)
        cbThreshFollowCp = findViewById(R.id.cbOvertakeSettingThreshFollowCp)
        etReverseCooldown = findViewById(R.id.etReverseCooldown)

        load()

        findViewById<Button>(R.id.btnMinusReverseCooldown).setOnClickListener {
            changeValue(etReverseCooldown, -1)
        }
        findViewById<Button>(R.id.btnPlusReverseCooldown).setOnClickListener {
            changeValue(etReverseCooldown, 1)
        }
        findViewById<Button>(R.id.btnSaveOvertakeSetting).setOnClickListener {
            save()
            finish()
        }
        findViewById<Button>(R.id.btnCancelOvertakeSetting).setOnClickListener { finish() }

        // 顶部一排分页入口
        findViewById<Button>(R.id.btnGotoGeneral).setOnClickListener {
            startActivity(Intent(this, SettingActivity::class.java))
        }
        findViewById<Button>(R.id.btnGotoNav).setOnClickListener {
            startActivity(Intent(this, NavSettingActivity::class.java))
        }
    }

    private fun changeValue(view: EditText, delta: Int) {
        val cur = view.text.toString().toIntOrNull() ?: 20
        view.setText((cur + delta).coerceIn(0, 120).toString())
    }

    private fun save() {
        val mode = when (rgOvertakeMode.checkedRadioButtonId) {
            R.id.rbOvertakeSettingFastMode -> 1
            R.id.rbOvertakeSettingSlowMode -> 3
            else -> 2
        }
        val cooldown = etReverseCooldown.text.toString().toIntOrNull() ?: 20
        getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit()
                .putInt("overtake_mode", mode)
                .putBoolean("app_overtake", cbHighway.isChecked)
                .putBoolean("app_road_overtake", cbRoad.isChecked)
                .putBoolean("app_lidar_self_check", cbLidarSelfCheck.isChecked)
                .putBoolean("app_thresh_follow_cp", cbThreshFollowCp.isChecked)
                .putInt("overtake_reverse_cooldown_s", cooldown.coerceIn(0, 120))
                .apply()
    }

    private fun load() {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        when (prefs.getInt("overtake_mode", 2)) {
            1 -> rgOvertakeMode.check(R.id.rbOvertakeSettingFastMode)
            3 -> rgOvertakeMode.check(R.id.rbOvertakeSettingSlowMode)
            else -> rgOvertakeMode.check(R.id.rbOvertakeSettingNormalMode)
        }
        cbHighway.isChecked = prefs.getBoolean("app_overtake", false)
        cbRoad.isChecked = prefs.getBoolean("app_road_overtake", false)
        cbLidarSelfCheck.isChecked = prefs.getBoolean("app_lidar_self_check", true)
        cbThreshFollowCp.isChecked = prefs.getBoolean("app_thresh_follow_cp", true)
        etReverseCooldown.setText(
            prefs.getInt("overtake_reverse_cooldown_s", 20).toString()
        )
    }
}
