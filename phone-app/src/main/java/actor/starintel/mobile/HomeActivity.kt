package actor.starintel.mobile

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class HomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(38), dp(24), dp(36))
            setBackgroundColor(BACKGROUND)
        }

        root.addView(TextView(this).apply {
            text = "STARINTEL"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.14f
            setTextColor(CYAN)
        }, matchWrap())

        root.addView(TextView(this).apply {
            text = "Companion"
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, matchWrap(top = 2))

        root.addView(TextView(this).apply {
            text = "One guided path: connect the watch, install the surfaces you want, then choose an update channel only if you need to."
            textSize = 15f
            setTextColor(MUTED)
            setLineSpacing(0f, 1.18f)
        }, matchWrap(top = 10))

        root.addView(TextView(this).apply {
            text = "RECOMMENDED FLOW"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(CYAN)
        }, matchWrap(top = 26))

        root.addView(menuButton("1 · CONNECT WATCH", "Find the paired watch, then send the server and private key") {
            startActivity(Intent(this, MainActivity::class.java))
        }, matchWrap(top = 10))

        root.addView(menuButton("2 · INSTALL WATCH APPS", "Bluetooth-aware queue with progress, retry, and recovery") {
            startActivity(Intent(this, WatchPackagesActivity::class.java))
        }, matchWrap(top = 12))

        root.addView(menuButton("3 · UPDATE CHANNEL", "Optional: master, latest release, or a pinned version") {
            startActivity(Intent(this, UpdateManagerActivity::class.java))
        }, matchWrap(top = 12))

        root.addView(TextView(this).apply {
            text = "After one receiver bootstrap, normal nearby installs need no VPN or direct ADB. Android/Wear OS still owns install approval."
            textSize = 12f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }, matchWrap(top = 24))

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
    }

    private fun menuButton(title: String, subtitle: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(CARD)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), STROKE)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(TextView(this@HomeActivity).apply {
                text = title
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }, matchWrap())
            addView(TextView(this@HomeActivity).apply {
                text = subtitle
                textSize = 13f
                setTextColor(MUTED)
            }, matchWrap(top = 4))
        }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val BACKGROUND = Color.BLACK
        private val CARD = Color.rgb(12, 16, 22)
        private val STROKE = Color.rgb(45, 59, 72)
        private val CYAN = Color.rgb(0, 229, 255)
        private val MUTED = Color.rgb(183, 194, 207)
    }
}
