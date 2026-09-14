package actor.starintel.mobile

import actor.starintel.update.PackageUpdateInstaller
import actor.starintel.update.UpdateFeed
import actor.starintel.update.UpdateManifest
import actor.starintel.update.UpdateRelease
import actor.starintel.update.UpdateSources
import android.app.Activity
import android.content.Intent
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

class UpdateManagerActivity : Activity() {
    private lateinit var sourceLabel: TextView
    private lateinit var status: TextView
    private lateinit var releases: LinearLayout
    private lateinit var installCompanion: Button
    private lateinit var progress: ProgressBar

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private var source = UpdateSources.MASTER
    private var manifest: UpdateManifest? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = prefs.getString(KEY_SOURCE, UpdateSources.MASTER) ?: UpdateSources.MASTER

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(32))
            setBackgroundColor(BACKGROUND)
        }

        root.addView(TextView(this).apply {
            text = "STARINTEL · COMPANION"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            setTextColor(CYAN)
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "Update manager"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, matchWrap(top = 3))
        root.addView(TextView(this).apply {
            text = "Choose exactly what StarIntel tracks: current master, the newest published release, or a specific version."
            textSize = 14f
            setTextColor(MUTED)
        }, matchWrap(top = 8))

        sourceLabel = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(CYAN)
        }
        root.addView(sourceLabel, matchWrap(top = 22))

        root.addView(Button(this).apply {
            text = "USE MASTER"
            setOnClickListener { select(UpdateSources.MASTER) }
        }, matchWrap(top = 10))
        root.addView(Button(this).apply {
            text = "USE LATEST RELEASE"
            setOnClickListener { select(UpdateSources.LATEST) }
        }, matchWrap(top = 6))
        root.addView(Button(this).apply {
            text = "LIST VERSIONED RELEASES"
            setOnClickListener { loadReleases() }
        }, matchWrap(top = 6))

        releases = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(releases, matchWrap(top = 8))

        root.addView(Button(this).apply {
            text = "REFRESH SELECTED SOURCE"
            setOnClickListener { loadManifest() }
        }, matchWrap(top = 18))

        installCompanion = Button(this).apply {
            text = "UPDATE COMPANION"
            visibility = View.GONE
            setOnClickListener { installCompanion() }
        }
        root.addView(installCompanion, matchWrap(top = 8))

        root.addView(Button(this).apply {
            text = "OPEN WATCH APPS"
            setOnClickListener { startActivity(Intent(this@UpdateManagerActivity, WatchPackagesActivity::class.java)) }
        }, matchWrap(top = 8))

        progress = ProgressBar(this).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        root.addView(progress, centeredWrap(top = 12))

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        }
        root.addView(status, matchWrap(top = 12))

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
        renderSource()
        loadManifest()
    }

    override fun onResume() {
        super.onResume()
        if (!busy && manifest != null) renderManifest(manifest!!)
    }

    private fun select(value: String) {
        if (busy) return
        source = value
        prefs.edit().putString(KEY_SOURCE, value).apply()
        releases.removeAllViews()
        manifest = null
        installCompanion.visibility = View.GONE
        renderSource()
        loadManifest()
    }

    private fun renderSource() {
        sourceLabel.text = "SOURCE · ${UpdateSources.label(source)}"
    }

    private fun loadReleases() {
        if (busy) return
        setBusy(true)
        releases.removeAllViews()
        status.text = "Loading published releases…"
        Thread {
            runCatching { UpdateFeed.listReleases(20) }
                .onSuccess { list -> runOnUiThread { setBusy(false); renderReleases(list) } }
                .onFailure { error -> runOnUiThread { setBusy(false); showError("Could not list releases: ${error.message}") } }
        }.start()
    }

    private fun renderReleases(list: List<UpdateRelease>) {
        if (list.isEmpty()) {
            status.text = "No published releases found."
            return
        }
        list.forEach { release ->
            releases.addView(Button(this).apply {
                text = buildString {
                    append(release.tagName)
                    if (release.prerelease) append(" · prerelease")
                }
                setOnClickListener { select(UpdateSources.tag(release.tagName)) }
            }, matchWrap(top = 5))
        }
        status.text = "Choose a release above, or keep ${UpdateSources.label(source)}."
    }

    private fun loadManifest() {
        if (busy) return
        setBusy(true)
        manifest = null
        installCompanion.visibility = View.GONE
        status.text = "Loading ${UpdateSources.label(source)} manifest…"
        Thread {
            runCatching { UpdateFeed.fetch(source) }
                .onSuccess { result -> runOnUiThread { setBusy(false); manifest = result; renderManifest(result) } }
                .onFailure { error -> runOnUiThread { setBusy(false); showError("Source unavailable: ${error.message}") } }
        }.start()
    }

    private fun renderManifest(value: UpdateManifest) {
        val current = packageManager.getPackageInfo(packageName, 0)
        val currentCode = current.longVersionCode
        val currentName = current.versionName ?: "?"
        val relation = when {
            value.versionCode > currentCode -> "update available"
            value.versionCode == currentCode -> "same version"
            else -> "older than installed"
        }
        status.text = buildString {
            append("Installed $currentName ($currentCode)\n")
            append("Selected ${value.versionName} (${value.versionCode}) · $relation\n")
            append("Ref ${value.ref} · ${value.commit.take(12)}")
        }
        status.setTextColor(CYAN)
        installCompanion.visibility = if (value.versionCode > currentCode) View.VISIBLE else View.GONE
    }

    private fun installCompanion() {
        val value = manifest ?: return
        if (busy) return
        if (!PackageUpdateInstaller.canRequestInstalls(this)) {
            status.text = "Allow StarIntel Companion to install apps, then return and tap UPDATE COMPANION again."
            status.setTextColor(WARNING)
            PackageUpdateInstaller.openInstallPermission(this)
            return
        }
        val artifact = value.artifact("phone")
        setBusy(true)
        status.text = "Downloading and verifying StarIntel Companion…"
        Thread {
            runCatching { UpdateFeed.download(this, artifact) }
                .onSuccess { apk ->
                    runOnUiThread {
                        setBusy(false)
                        status.text = "Verified. Opening Android package installer…"
                        status.setTextColor(CYAN)
                        PackageUpdateInstaller.install(this, apk, artifact.packageName)
                    }
                }
                .onFailure { error -> runOnUiThread { setBusy(false); showError("Download failed: ${error.message}") } }
        }.start()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        progress.visibility = if (value) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        status.text = message
        status.setTextColor(WARNING)
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun centeredWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val PREFS = "starintel_updates"
        const val KEY_SOURCE = "source"
        private val BACKGROUND = Color.rgb(5, 7, 10)
        private val CYAN = Color.rgb(0, 229, 255)
        private val MUTED = Color.rgb(176, 187, 199)
        private val WARNING = Color.rgb(255, 132, 132)
    }
}
