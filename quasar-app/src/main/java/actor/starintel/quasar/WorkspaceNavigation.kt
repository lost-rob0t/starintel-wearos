package actor.starintel.quasar

import android.animation.ValueAnimator
import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.drawerlayout.widget.DrawerLayout

/** A modal native drawer expands over the content, so opening it never rebuilds a route. */
internal class WorkspaceNavigation(
    private val activity: Activity,
    private val rail: Boolean,
    content: View,
    private val destinations: List<Destination>,
    saved: Bundle?,
) : DrawerLayout(activity) {
    data class Destination(val route: String, val label: String, val group: String, val action: () -> Unit)
    private val items = mutableListOf<Pair<Destination, TextView>>()
    private val railItems = mutableListOf<Pair<Destination, TextView>>()
    private val scroll = ScrollView(activity)
    private val opener: TextView
    private var rawRoute = "home"
    var selectedDestination = "home"
        private set
    val isExpanded get() = isDrawerOpen(GravityCompat.START)

    init {
        isSaveEnabled = false // The activity owns restoration, including route and drawer scroll.
        setScrimColor(if (QuasarDesign.isDark) 0x99000000.toInt() else 0x66000000)
        setDrawerTitle(GravityCompat.START, "Workspace navigation")
        val main = LinearLayout(activity).apply {
            orientation = if (rail) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        opener = iconControl(if (rail) "Expand navigation" else "Open navigation", "Menu") { expand() }
        if (rail) {
            main.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setBackgroundColor(QuasarDesign.panel)
                addView(opener, LinearLayout.LayoutParams(-1, dp(48)))
                addView(ScrollView(activity).apply {
                    isFillViewport = false
                    addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        destinations.filter { it.group == "Workspace" }.forEach { destination ->
                            val item = iconControl(destination.label, destination.label) {
                                if (destination.route != rawRoute) destination.action()
                            }
                            item.tag = destination.label
                            railItems.add(destination to item)
                            addView(item, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(4) })
                        }
                    })
                }, LinearLayout.LayoutParams(-1, 0, 1f))
            }, LinearLayout.LayoutParams(dp(72), -1))
        } else {
            main.addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(16), 0)
                setBackgroundColor(QuasarDesign.background)
                addView(opener, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(QuasarDesign.title(activity, "Workspace", 16f).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        main.addView(content, if (rail) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f))
        addView(main, LayoutParams(-1, -1))

        val pane = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(QuasarDesign.panel)
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        pane.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(QuasarDesign.title(activity, "Your workspace", 20f).apply {
                ViewCompat.setAccessibilityHeading(this, true)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconControl(if (rail) "Collapse navigation" else "Close navigation", "Close") { collapse() },
                LinearLayout.LayoutParams(dp(48), dp(48)))
        }, LinearLayout.LayoutParams(-1, -2))
        scroll.contentDescription = "Workspace destinations"
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        var group = ""
        destinations.forEach { destination ->
            if (destination.group != group) {
                group = destination.group
                rows.addView(QuasarDesign.eyebrow(activity, group).apply {
                    ViewCompat.setAccessibilityHeading(this, true)
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); bottomMargin = dp(8) })
            }
            val row = QuasarDesign.action(activity, destination.label) {
                collapse()
                // Selecting the current route closes the drawer without resetting work.
                if (destination.route != rawRoute) destination.action()
            }.apply {
                tag = destination.label
                gravity = Gravity.CENTER_VERTICAL
                compoundDrawablePadding = dp(12)
            }
            items.add(destination to row)
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
        scroll.addView(rows)
        pane.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val width = minOf(320, activity.resources.configuration.screenWidthDp - 48).coerceAtLeast(240)
        addView(pane, LayoutParams(dp(width), -1, GravityCompat.START))
        addDrawerListener(object : SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                ViewCompat.setStateDescription(opener, "Expanded")
            }
            override fun onDrawerClosed(drawerView: View) {
                ViewCompat.setStateDescription(opener, "Collapsed")
                opener.requestFocus()
            }
        })
        ViewCompat.setStateDescription(opener, "Collapsed")
        post {
            if (saved?.getBoolean("open") == true) openDrawer(GravityCompat.START, false)
            scroll.post { scroll.scrollTo(0, saved?.getInt("scroll") ?: 0) }
        }
    }

    fun expand() {
        WindowCompat.getInsetsController(activity.window, this).hide(WindowInsetsCompat.Type.ime())
        openDrawer(GravityCompat.START, ValueAnimator.areAnimatorsEnabled())
    }

    fun collapse(): Boolean {
        if (!isDrawerVisible(GravityCompat.START)) return false
        closeDrawer(GravityCompat.START, ValueAnimator.areAnimatorsEnabled())
        return true
    }

    fun saveWorkspace() = Bundle().apply {
        putBoolean("open", isDrawerVisible(GravityCompat.START)); putInt("scroll", scroll.scrollY)
    }

    fun select(route: String, primary: String) {
        rawRoute = route
        selectedDestination = when {
            route.startsWith("document:") -> "search"
            route.startsWith("target:") -> "target:"
            else -> route
        }
        items.forEach { (destination, row) -> style(row, destination, destination.route == selectedDestination, false) }
        railItems.forEach { (destination, row) -> style(row, destination, destination.label == primary, true) }
    }

    private fun style(row: TextView, destination: Destination, selected: Boolean, iconOnly: Boolean) {
        val fill = if (selected) QuasarPalette.mix(QuasarDesign.cyan, QuasarDesign.panel, .88) else QuasarDesign.panel
        val ink = if (selected) QuasarPalette.legible(QuasarDesign.cyan, listOf(fill), QuasarDesign.isDark) else QuasarDesign.text
        row.setTextColor(ink)
        row.isSelected = selected
        row.contentDescription = destination.label + if (selected) ", selected" else ""
        row.background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(QuasarPalette.mix(QuasarDesign.cyan, fill, .75)),
            android.graphics.drawable.GradientDrawable().apply { setColor(fill); cornerRadius = dp(12).toFloat() }, null,
        )
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(QuasarIcon(destination.label, ink, dp(22)), null, null, null)
        if (iconOnly) {
            row.gravity = Gravity.CENTER
            row.setPadding(dp(17), dp(13), dp(17), dp(13))
        }
        ViewCompat.setAccessibilityDelegate(row, object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
                info.isCheckable = true; info.isChecked = selected
            }
        })
    }

    private fun iconControl(label: String, icon: String, click: () -> Unit) = QuasarDesign.action(activity, "", onClick = click).apply {
        contentDescription = label
        tooltipText = label
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setCompoundDrawablesRelativeWithIntrinsicBounds(QuasarIcon(icon, QuasarDesign.cyan, dp(24)), null, null, null)
    }

    private fun dp(value: Int) = activity.dp(value)
}
