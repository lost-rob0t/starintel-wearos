package actor.starintel.mobile

import actor.starintel.update.PackageUpdateInstaller
import actor.starintel.update.UpdateArtifact
import actor.starintel.update.UpdateFeed
import actor.starintel.update.UpdateManifest
import actor.starintel.update.UpdateSources
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

class PhonePackagesActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private val prefs by lazy { getSharedPreferences(UpdateManagerActivity.PREFS, MODE_PRIVATE) }
    private var manifest: UpdateManifest? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(36))
            setBackgroundColor(BACKGROUND)
        }
        root.addView(TextView(this).apply {
            text = "STARINTEL · COMPANION"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .1f
            setTextColor(CYAN)
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "Phone apps"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, matchWrap(top = 3))
        root.addView(TextView(this).apply {
            text = "Install native StarIntel apps from the selected signed release catalog. Android always requires your approval."
            textSize = 14f
            setTextColor(MUTED)
        }, matchWrap(top = 8))
        root.addView(Button(this).apply { text = "REFRESH CATALOG"; setOnClickListener { refresh() } }, matchWrap(top = 18))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, matchWrap(top = 10))
        progress = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        root.addView(progress, centeredWrap(top = 12))
        status = TextView(this).apply { textSize = 13f; setTextColor(MUTED); gravity = Gravity.CENTER }
        root.addView(status, matchWrap(top = 10))
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (!PackageUpdateInstaller.canRequestInstalls(this)) {
            status.text = "Allow StarIntel Companion to install unknown apps, then return here."
        }
    }

    private fun refresh() {
        if (busy) return
        busy = true
        progress.visibility = View.VISIBLE
        list.removeAllViews()
        val source = prefs.getString(UpdateManagerActivity.KEY_SOURCE, UpdateSources.MASTER) ?: UpdateSources.MASTER
        status.text = "Loading ${UpdateSources.label(source)}…"
        Thread {
            runCatching { UpdateFeed.fetch(source) }
                .onSuccess { value -> runOnUiThread {
                    manifest = value
                    busy = false
                    progress.visibility = View.GONE
                    render(value)
                    status.text = "${value.versionName} · ${value.commit.take(10)}"
                } }
                .onFailure { error -> runOnUiThread {
                    busy = false
                    progress.visibility = View.GONE
                    status.text = "Catalog failed: ${error.message}"
                    status.setTextColor(WARNING)
                } }
        }.start()
    }

    private fun render(value: UpdateManifest) {
        value.phoneArtifacts().forEach { artifact ->
            list.addView(Button(this).apply {
                text = when (artifact.id) {
                    "phone" -> "UPDATE COMPANION"
                    "quasar" -> "INSTALL / UPDATE QUASAR"
                    else -> "INSTALL ${artifact.id.uppercase()}"
                }
                setOnClickListener { install(artifact) }
            }, matchWrap(top = 7))
        }
    }

    private fun install(artifact: UpdateArtifact) {
        if (busy) return
        if (!PackageUpdateInstaller.canRequestInstalls(this)) {
            PackageUpdateInstaller.openInstallPermission(this)
            return
        }
        busy = true
        progress.visibility = View.VISIBLE
        setButtons(false)
        status.setTextColor(MUTED)
        status.text = "Downloading ${artifact.id}…"
        Thread {
            runCatching {
                val apk = UpdateFeed.download(this, artifact)
                PackageUpdateInstaller.verify(this, apk, artifact.packageName)?.let { error(it) }
                apk
            }.onSuccess { apk -> runOnUiThread {
                busy = false
                progress.visibility = View.GONE
                setButtons(true)
                status.text = "Verified ${artifact.id}. Approve the Android installer."
                runCatching { PackageUpdateInstaller.install(this, apk, artifact.packageName) }
                    .onFailure { status.text = it.message ?: "Could not start installer"; status.setTextColor(WARNING) }
            } }.onFailure { error -> runOnUiThread {
                busy = false
                progress.visibility = View.GONE
                setButtons(true)
                status.text = "Install blocked: ${error.message}"
                status.setTextColor(WARNING)
            } }
        }.start()
    }

    private fun setButtons(enabled: Boolean) {
        for (index in 0 until list.childCount) list.getChildAt(index).isEnabled = enabled
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun centeredWrap(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(top) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val BACKGROUND = Color.rgb(5, 7, 10)
        private val CYAN = Color.rgb(0, 229, 255)
        private val MUTED = Color.rgb(176, 187, 199)
        private val WARNING = Color.rgb(255, 132, 132)
    }
}
