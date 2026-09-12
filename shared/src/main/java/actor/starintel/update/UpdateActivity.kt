package actor.starintel.update

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class UpdateActivity : Activity() {
    private lateinit var source: TextView
    private lateinit var exactTag: EditText
    private lateinit var status: TextView
    private lateinit var check: Button
    private lateinit var install: Button
    private lateinit var progress: ProgressBar

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private var selectedSource: String = UpdateSources.MASTER
    private var pendingManifest: UpdateManifest? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedSource = prefs.getString(KEY_SOURCE, UpdateSources.MASTER) ?: UpdateSources.MASTER

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(24), dp(22), dp(28))
            setBackgroundColor(BACKGROUND)
        }

        root.addView(TextView(this).apply {
            text = "STARINTEL · UPDATE"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(CYAN)
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        }, matchWrap())

        source = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(source, matchWrap(top = 10))

        val master = Button(this).apply {
            text = "MASTER"
            setOnClickListener { selectSource(UpdateSources.MASTER) }
        }
        val tagged = Button(this).apply {
            text = "LATEST TAG"
            setOnClickListener { selectSource(UpdateSources.TAGGED) }
        }
        root.addView(master, matchWrap(top = 12))
        root.addView(tagged, matchWrap(top = 4))

        exactTag = EditText(this).apply {
            hint = "Exact tag (optional), e.g. v0.2.0"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(exactTag, matchWrap(top = 12))

        check = Button(this).apply {
            text = "CHECK FOR UPDATE"
            setOnClickListener { checkForUpdate() }
        }
        root.addView(check, matchWrap(top = 12))

        install = Button(this).apply {
            text = "INSTALL UPDATE"
            visibility = View.GONE
            setOnClickListener { installPending() }
        }
        root.addView(install, matchWrap(top = 4))

        progress = ProgressBar(this).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        root.addView(progress, centeredWrap(top = 10))

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        }
        root.addView(status, matchWrap(top = 10))

        root.addView(TextView(this).apply {
            text = if (isWatch()) {
                "Watch updates install the watch-face package first, then StarIntel Wear. Android may ask you to approve each package install."
            } else {
                "Companion updates are checksum-verified, then handed to Android's package installer. Android may ask you to allow StarIntel as an install source."
            }
            textSize = 10f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }, matchWrap(top = 14))

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })

        renderSource()
        status.text = "Installed version: ${installedVersionLabel()}"
    }

    override fun onResume() {
        super.onResume()
        if (!busy && pendingManifest != null) {
            install.isEnabled = true
        }
    }

    private fun selectSource(value: String) {
        if (busy) return
        selectedSource = value
        exactTag.text?.clear()
        prefs.edit().putString(KEY_SOURCE, value).apply()
        pendingManifest = null
        install.visibility = View.GONE
        renderSource()
        status.text = "Source changed. Check for an update."
        status.setTextColor(MUTED)
    }

    private fun renderSource() {
        source.text = "SOURCE · ${UpdateSources.label(selectedSource)}"
    }

    private fun effectiveSource(): String {
        val tag = exactTag.text.toString().trim()
        if (tag.isBlank()) return selectedSource
        return "tag:$tag"
    }

    private fun checkForUpdate() {
        if (busy) return
        setBusy(true)
        pendingManifest = null
        install.visibility = View.GONE
        val requestedSource = effectiveSource()
        status.text = "Checking ${UpdateSources.label(requestedSource)}…"
        status.setTextColor(MUTED)

        Thread {
            runCatching { UpdateFeed.fetch(requestedSource) }
                .onSuccess { manifest ->
                    runOnUiThread {
                        setBusy(false)
                        val current = installedVersionCode()
                        if (manifest.versionCode <= current) {
                            status.text = buildString {
                                append("Installed ${installedVersionLabel()} · selected ${manifest.versionName} (${manifest.versionCode}).")
                                if (requestedSource.startsWith("tag:") && manifest.versionCode < current) {
                                    append(" Older-tag rollback is intentionally CLI-only; use --allow-downgrade over ADB.")
                                } else {
                                    append(" No newer update is available.")
                                }
                            }
                            status.setTextColor(CYAN)
                            return@runOnUiThread
                        }

                        pendingManifest = manifest
                        install.text = "INSTALL ${manifest.versionName}"
                        install.visibility = View.VISIBLE
                        install.isEnabled = true
                        status.text = "Update available · ${manifest.versionName} · ${manifest.commit.take(10)}"
                        status.setTextColor(CYAN)
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        setBusy(false)
                        status.text = "Update check failed: ${error.message ?: error.javaClass.simpleName}"
                        status.setTextColor(WARNING)
                    }
                }
        }.start()
    }

    private fun installPending() {
        val manifest = pendingManifest ?: return
        if (busy) return
        if (!PackageUpdateInstaller.canRequestInstalls(this)) {
            status.text = "Allow StarIntel to install unknown apps, then return and tap INSTALL again."
            status.setTextColor(WARNING)
            PackageUpdateInstaller.openInstallPermission(this)
            return
        }

        setBusy(true)
        status.text = "Downloading and verifying update…"
        status.setTextColor(MUTED)

        Thread {
            runCatching {
                if (isWatch()) {
                    val face = manifest.artifact("watchface")
                    val wear = manifest.artifact("wear")
                    val faceApk = UpdateFeed.download(this, "watchface", face)
                    val wearApk = UpdateFeed.download(this, "wear", wear)
                    InstallPlan(
                        firstApk = faceApk,
                        firstPackage = face.packageName,
                        nextApk = wearApk,
                        nextPackage = wear.packageName,
                    )
                } else {
                    val phone = manifest.artifact("phone")
                    val phoneApk = UpdateFeed.download(this, "phone", phone)
                    InstallPlan(phoneApk, phone.packageName, null, null)
                }
            }.onSuccess { plan ->
                runOnUiThread {
                    setBusy(false)
                    status.text = "Verified. Android installer opened."
                    status.setTextColor(CYAN)
                    PackageUpdateInstaller.install(
                        activity = this,
                        apk = plan.firstApk,
                        expectedPackage = plan.firstPackage,
                        nextApk = plan.nextApk,
                        nextPackage = plan.nextPackage,
                    )
                }
            }.onFailure { error ->
                runOnUiThread {
                    setBusy(false)
                    status.text = "Update download failed: ${error.message ?: error.javaClass.simpleName}"
                    status.setTextColor(WARNING)
                }
            }
        }.start()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        progress.visibility = if (value) View.VISIBLE else View.GONE
        check.isEnabled = !value
        install.isEnabled = !value
        exactTag.isEnabled = !value
    }

    private fun installedVersionCode(): Long {
        val info = packageManager.getPackageInfo(packageName, 0)
        return info.longVersionCode
    }

    private fun installedVersionLabel(): String {
        val info = packageManager.getPackageInfo(packageName, 0)
        return "${info.versionName ?: "?"} (${info.longVersionCode})"
    }

    private fun isWatch(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun centeredWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        topMargin = dp(top)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class InstallPlan(
        val firstApk: File,
        val firstPackage: String,
        val nextApk: File?,
        val nextPackage: String?,
    )

    companion object {
        private const val PREFS = "starintel_updates"
        private const val KEY_SOURCE = "source"
        private val BACKGROUND = Color.rgb(32, 33, 70)
        private val CYAN = Color.rgb(45, 226, 230)
        private val MUTED = Color.rgb(243, 244, 245)
        private val WARNING = Color.rgb(221, 84, 110)
    }
}
