package com.carrot.amapnavi

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

class WxPayActivity : AppCompatActivity() {
    private lateinit var btnCancel: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wxpay)

        val imageView = findViewById<ImageView>(R.id.imageView)

        imageView.post {
            val screenWidth = resources.displayMetrics.widthPixels
            val targetWidth = (screenWidth * 0.65f).toInt()

            val lp = imageView.layoutParams
            lp.width = targetWidth
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            imageView.layoutParams = lp
        }

        btnCancel = findViewById(R.id.btnCancel)
        btnCancel.setOnClickListener { finish() }
    }
}
