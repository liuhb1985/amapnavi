package com.carrot.amapnavi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
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

    // 侧向危险阈值：固定距离(m) / 绝对速度时距(s)，前后各一套
    private lateinit var rgFrontThreshMode: RadioGroup
    private lateinit var etFrontThresh: EditText
    private lateinit var tvFrontThreshUnit: TextView
    private lateinit var rgRearThreshMode: RadioGroup
    private lateinit var etRearThresh: EditText
    private lateinit var tvRearThreshUnit: TextView
    private lateinit var etFrontVrel: EditText
    private lateinit var etRearVrel: EditText
    private var frontVrelS = 4f
    private var rearVrelS = 4f
    private var frontDistM = 5f
    private var frontTimeS = 1.2f
    private var rearDistM = 10f
    private var rearTimeS = 1.5f
    private var lastFrontIsTime = false
    private var lastRearIsTime = false
    // load() 里 check() 会触发选中监听，此时 EditView 还是布局默认值，不能回写
    private var loading = false

    private val prefsName = "user_settings"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overtake_setting)

        rgOvertakeMode = findViewById(R.id.rgOvertakeSettingMode)
        cbHighway = findViewById(R.id.cbOvertakeSettingHighway)
        cbRoad = findViewById(R.id.cbOvertakeSettingRoad)
        cbLidarSelfCheck = findViewById(R.id.cbOvertakeSettingLidarSelfCheck)
        cbThreshFollowCp = findViewById(R.id.cbOvertakeSettingThreshFollowCp)

        rgFrontThreshMode = findViewById(R.id.rgFrontThreshMode)
        etFrontThresh = findViewById(R.id.etFrontThresh)
        tvFrontThreshUnit = findViewById(R.id.tvFrontThreshUnit)
        rgRearThreshMode = findViewById(R.id.rgRearThreshMode)
        etRearThresh = findViewById(R.id.etRearThresh)
        tvRearThreshUnit = findViewById(R.id.tvRearThreshUnit)
        etFrontVrel = findViewById(R.id.etFrontVrel)
        etRearVrel = findViewById(R.id.etRearVrel)
        findViewById<Button>(R.id.btnMinusFrontVrel).setOnClickListener { stepVrel(true, -1) }
        findViewById<Button>(R.id.btnPlusFrontVrel).setOnClickListener { stepVrel(true, 1) }
        findViewById<Button>(R.id.btnMinusRearVrel).setOnClickListener { stepVrel(false, -1) }
        findViewById<Button>(R.id.btnPlusRearVrel).setOnClickListener { stepVrel(false, 1) }
        rgFrontThreshMode.setOnCheckedChangeListener { _, _ ->
            if (loading) return@setOnCheckedChangeListener
            readFrontInput(lastFrontIsTime)          // 先按旧模式存回
            lastFrontIsTime = frontIsTime()
            showFrontThresh()
        }
        rgRearThreshMode.setOnCheckedChangeListener { _, _ ->
            if (loading) return@setOnCheckedChangeListener
            readRearInput(lastRearIsTime)
            lastRearIsTime = rearIsTime()
            showRearThresh()
        }
        findViewById<Button>(R.id.btnMinusFrontThresh).setOnClickListener { stepFront(-1) }
        findViewById<Button>(R.id.btnPlusFrontThresh).setOnClickListener { stepFront(1) }
        findViewById<Button>(R.id.btnMinusRearThresh).setOnClickListener { stepRear(-1) }
        findViewById<Button>(R.id.btnPlusRearThresh).setOnClickListener { stepRear(1) }
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

        // 顶部一排分页入口（跳走时关闭本页，保证一次返回回到主界面）
        findViewById<Button>(R.id.btnGotoGeneral).setOnClickListener {
            startActivity(Intent(this, SettingActivity::class.java))
            finish()
        }
        findViewById<Button>(R.id.btnGotoNav).setOnClickListener {
            startActivity(Intent(this, NavSettingActivity::class.java))
            finish()
        }

        highlightSettingTab(R.id.btnGotoOvertake)

        // 顶部留空：与车机适配参数 top_pad_dp 保持一致
        setTopPaddingDp(
                findViewById(R.id.overtakeSettingContent),
                getSharedPreferences(prefsName, Context.MODE_PRIVATE).getInt("top_pad_dp", 5))
    }

    private fun setTopPaddingDp(view: View, dp: Int) {
        val px = (dp * resources.displayMetrics.density).toInt()
        view.setPadding(view.paddingLeft, px, view.paddingRight, view.paddingBottom)
    }

    private fun changeValue(view: EditText, delta: Int) {
        val cur = view.text.toString().toIntOrNull() ?: 20
        view.setText((cur + delta).coerceIn(0, 120).toString())
    }

    // ---------------- 侧向危险阈值 ----------------
    private fun frontIsTime() = rgFrontThreshMode.checkedRadioButtonId == R.id.rbFrontThreshTime
    private fun rearIsTime() = rgRearThreshMode.checkedRadioButtonId == R.id.rbRearThreshTime

    private fun readFrontInput(isTime: Boolean) {
        val v = etFrontThresh.text.toString().toFloatOrNull() ?: return
        if (isTime) frontTimeS = v else frontDistM = v
    }
    private fun readRearInput(isTime: Boolean) {
        val v = etRearThresh.text.toString().toFloatOrNull() ?: return
        if (isTime) rearTimeS = v else rearDistM = v
    }
    private fun showFrontThresh() {
        etFrontThresh.setText("%.1f".format(if (frontIsTime()) frontTimeS else frontDistM))
        tvFrontThreshUnit.text = if (frontIsTime()) "s" else "m"
    }
    private fun showRearThresh() {
        etRearThresh.setText("%.1f".format(if (rearIsTime()) rearTimeS else rearDistM))
        tvRearThreshUnit.text = if (rearIsTime()) "s" else "m"
    }
    private fun stepFront(dir: Int) {
        if (frontIsTime()) frontTimeS = (frontTimeS + dir * 0.1f).coerceIn(0.2f, 5.0f)
        else frontDistM = (frontDistM + dir * 0.5f).coerceIn(1f, 50f)
        showFrontThresh()
    }
    private fun stepRear(dir: Int) {
        if (rearIsTime()) rearTimeS = (rearTimeS + dir * 0.1f).coerceIn(0.2f, 5.0f)
        else rearDistM = (rearDistM + dir * 0.5f).coerceIn(1f, 50f)
        showRearThresh()
    }

    // 相对速度时距：0.1s 步进，0 = 不外推
    private fun stepVrel(front: Boolean, dir: Int) {
        if (front) {
            frontVrelS = (frontVrelS + dir * 0.1f).coerceIn(0f, 8.0f)
            etFrontVrel.setText("%.1f".format(frontVrelS))
        } else {
            rearVrelS = (rearVrelS + dir * 0.1f).coerceIn(0f, 8.0f)
            etRearVrel.setText("%.1f".format(rearVrelS))
        }
    }

    private fun save() {
        readFrontInput(frontIsTime())   // 先取回输入框里的值(可能手输过)
        readRearInput(rearIsTime())
        frontVrelS = etFrontVrel.text.toString().toFloatOrNull() ?: frontVrelS
        rearVrelS = etRearVrel.text.toString().toFloatOrNull() ?: rearVrelS
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
                .putInt("lidar_front_dist_mode", if (frontIsTime()) 1 else 0)
                .putFloat("lidar_front_dist_m", frontDistM)
                .putFloat("lidar_front_time_s", frontTimeS)
                .putInt("lidar_rear_dist_mode", if (rearIsTime()) 1 else 0)
                .putFloat("lidar_rear_dist_m", rearDistM)
                .putFloat("lidar_rear_time_s", rearTimeS)
                .putFloat("lidar_front_vrel_time_s", frontVrelS)
                .putFloat("lidar_rear_vrel_time_s", rearVrelS)
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
        frontDistM = prefs.getFloat("lidar_front_dist_m", 5f)
        frontTimeS = prefs.getFloat("lidar_front_time_s", 1.2f)
        rearDistM = prefs.getFloat("lidar_rear_dist_m", 10f)
        rearTimeS = prefs.getFloat("lidar_rear_time_s", 1.5f)
        frontVrelS = prefs.getFloat("lidar_front_vrel_time_s", 4f)
        rearVrelS = prefs.getFloat("lidar_rear_vrel_time_s", 4f)
        etFrontVrel.setText("%.1f".format(frontVrelS))
        etRearVrel.setText("%.1f".format(rearVrelS))
        val frontMode = prefs.getInt("lidar_front_dist_mode", 0)
        val rearMode = prefs.getInt("lidar_rear_dist_mode", 0)
        lastFrontIsTime = frontMode == 1
        lastRearIsTime = rearMode == 1
        loading = true
        rgFrontThreshMode.check(if (frontMode == 1) R.id.rbFrontThreshTime else R.id.rbFrontThreshFix)
        rgRearThreshMode.check(if (rearMode == 1) R.id.rbRearThreshTime else R.id.rbRearThreshFix)
        showFrontThresh()
        showRearThresh()
        loading = false
        etReverseCooldown.setText(
            prefs.getInt("overtake_reverse_cooldown_s", 20).toString()
        )
    }
}
