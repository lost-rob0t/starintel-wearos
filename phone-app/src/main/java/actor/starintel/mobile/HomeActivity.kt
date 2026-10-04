package actor.starintel.mobile

import actor.starintel.design.Si
import actor.starintel.design.SiTokens
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView

/** Companion owns installation and pairing; each surface owns its credentials. */
class HomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        val si = Si(this, SiTokens.ThemeStore(this).current())
        window.statusBarColor = si.background()
        window.navigationBarColor = si.background()
        val body = si.vertical().apply {
            setPadding(si.dp(20), si.dp(24), si.dp(20), si.dp(32))
            setBackgroundColor(si.background())
        }
        body.addView(si.header("StarIntel // Companion", "Your field kit", "Connect your watch, manage the fleet, and keep every surface current."))
        val connection = si.accentCard(si.accent()).apply {
            addView(si.title("Watch connection"))
            addView(si.body("Pair and check your nearby receiver before sending packages."), si.match(8))
            addView(si.primaryButton("Connect watch") { open(MainActivity::class.java) }, si.match(16))
        }
        body.addView(connection, si.match(20))
        body.addView(si.sectionHeader("Phone surfaces"), si.match(24))
        val surfaces = listOf("actor.starintel.operator" to "Operator", "actor.starintel.quasar" to "Quasar",
            "actor.starintel.collector" to "Collector", "actor.starintel.hackmode" to "Hackmode")
        for ((name, title) in surfaces) {
            val launch = packageManager.getLaunchIntentForPackage(name)
            val card = si.card().apply {
                addView(si.title(title))
                addView(si.statusPill(if (launch != null) "Installed" else "Not installed", "", if (launch != null) si.ok() else si.warn()), si.match(8))
                addView(si.secondaryButton(if (launch != null) "Open $title" else "Install $title") {
                    if (launch != null) startActivity(launch) else open(PhonePackagesActivity::class.java)
                }, si.match(12))
            }
            body.addView(card, si.match(8))
        }
        body.addView(si.sectionHeader("Packages & updates"), si.match(24))
        body.addView(si.secondaryButton("Watch packages") { open(WatchPackagesActivity::class.java) }, si.match(12))
        body.addView(si.secondaryButton("Phone packages") { open(PhonePackagesActivity::class.java) }, si.match(8))
        body.addView(si.secondaryButton("Update channel") { open(UpdateManagerActivity::class.java) }, si.match(8))
        body.addView(si.body("Android asks you to approve each installation. Package catalogs verify the download before opening the installer."), si.match(20))
        Si.install(this, ScrollView(this).apply { isFillViewport = true; addView(body) })
    }

    private fun open(type: Class<out Activity>) { startActivity(Intent(this, type)) }
}
