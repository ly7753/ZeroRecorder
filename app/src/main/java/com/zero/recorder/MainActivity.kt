package com.zero.recorder

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var statusIcon: TextView
    private lateinit var statusText: TextView
    private lateinit var statusCard: LinearLayout
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private val SHIZUKU_CODE = 1001

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == SHIZUKU_CODE) {
            runOnUiThread { updateUI() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bgColor = Color.parseColor("#09090B")
        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Deprecated in API 35, but still needed for older devices
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bgColor)
        }

        val titleText = TextView(this).apply {
            text = "ZeroRecorder"
            textSize = 36f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        layout.addView(titleText)

        val subTitle = TextView(this).apply {
            text = "后台录屏工具"
            textSize = 14f
            setTextColor(Color.parseColor("#71717A"))
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.05f
        }
        val subParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp2px(4f), 0, dp2px(48f)) }
        layout.addView(subTitle, subParams)

        statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp2px(20f), dp2px(12f), dp2px(20f), dp2px(12f))
        }

        statusIcon = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, dp2px(8f), 0)
        }

        statusText = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }

        statusCard.addView(statusIcon)
        statusCard.addView(statusText)

        val cardParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp2px(48f)) }
        layout.addView(statusCard, cardParams)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        startButton = createStyledButton("开始录制", Color.parseColor("#3B82F6"), Color.parseColor("#2563EB"), Color.parseColor("#1D4ED8"))
        val startParams = LinearLayout.LayoutParams(dp2px(140f), dp2px(52f)).apply { setMargins(0, 0, dp2px(16f), 0) }
        startButton.setOnClickListener { handleStartClick() }
        buttonRow.addView(startButton, startParams)

        stopButton = createStyledButton("停止录制", Color.parseColor("#EF4444"), Color.parseColor("#DC2626"), Color.parseColor("#991B1B")).apply {
            isEnabled = false
            alpha = 0.5f
            setOnClickListener {
                thread {
                    RecorderLauncher.forceStop()
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "已发送停止指令", Toast.LENGTH_SHORT).show()
                        updateUI()
                    }
                }
            }
        }
        val stopParams = LinearLayout.LayoutParams(dp2px(140f), dp2px(52f))
        buttonRow.addView(stopButton, stopParams)

        layout.addView(buttonRow)
        setContentView(layout)

        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }

    private fun updateUI() {
        if (!Shizuku.pingBinder()) {
            setStatusCardStyle("❌", "Shizuku 未运行，请先激活", Color.parseColor("#EF4444"))
            startButton.isEnabled = false
            startButton.alpha = 0.5f
            return
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            setStatusCardStyle("✅", "Shizuku 已授权，可以录制", Color.parseColor("#10B981"))
            startButton.isEnabled = true
            startButton.text = "开始录制"
            startButton.alpha = 1.0f
            stopButton.isEnabled = true
            stopButton.alpha = 1.0f
        } else {
            setStatusCardStyle("⚠️", "等待 Shizuku 授权", Color.parseColor("#F59E0B"))
            startButton.isEnabled = true
            startButton.text = "请求授权"
            startButton.alpha = 1.0f
            stopButton.isEnabled = true
            stopButton.alpha = 1.0f
        }
    }

    private fun setStatusCardStyle(icon: String, text: String, baseColor: Int) {
        statusIcon.text = icon
        statusText.text = text
        statusText.setTextColor(baseColor)

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp2px(30f).toFloat()
            setColor(Color.argb(38, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)))
            setStroke(dp2px(1f), Color.argb(100, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)))
        }
        statusCard.background = bg
    }

    private fun handleStartClick() {
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_CODE)
            return
        }

        val success = RecorderLauncher.startRecording(this)
        if (success) {
            Toast.makeText(this, "已在后台开始录制", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            Toast.makeText(this, "启动失败，请检查环境", Toast.LENGTH_LONG).show()
        }
    }

    private fun createStyledButton(text: String, colorStart: Int, colorEnd: Int, pressedColor: Int): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            letterSpacing = 0.02f

            val normal = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(colorStart, colorEnd)).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp2px(16f).toFloat()
            }

            val pressed = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp2px(16f).toFloat()
                setColor(pressedColor)
            }

            val stateDrawable = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressed)
                addState(intArrayOf(-android.R.attr.state_enabled), normal)
                addState(intArrayOf(), normal)
            }

            background = stateDrawable
            elevation = dp2px(4f).toFloat()
        }
    }

    private fun dp2px(dp: Float): Int {
        val density = resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }
}
