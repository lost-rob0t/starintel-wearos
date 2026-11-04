package actor.starintel.wear

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import actor.starintel.android.model.ActorCatalog
import actor.starintel.android.model.RemoteActor
import actor.starintel.wear.data.ActorsResult
import actor.starintel.wear.data.StarIntelApiClient
import actor.starintel.wear.ui.StarIntelActivity
import actor.starintel.wear.ui.applyStarIntelTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Browse the operator-visible server actor fleet; tap an actor to dispatch. */
class ActorsActivity : StarIntelActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var listStatus: TextView
    private lateinit var fleet: LinearLayout
    private lateinit var refresh: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(30))
            setBackgroundColor(palette.background)
        }
        root.addView(TextView(this).apply {
            text = "ACTORS"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
            gravity = Gravity.CENTER
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "Server fleet your account can read"
            textSize = 10f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
        }, matchWrap(top = 2))

        listStatus = TextView(this).apply {
            text = "Loading fleet…"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(palette.muted)
        }
        fleet = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        refresh = Button(this).apply {
            text = "REFRESH FLEET"
            applyStarIntelTheme(palette)
            setOnClickListener { refreshFleet() }
        }

        ActorCatalog.all.forEach { known ->
            root.addView(sectionTitle(known.name.uppercase()), matchWrap(top = 9))
            root.addView(actorCard(known.name, known.kind.uppercase(), known.accepts, known.description, known.monitor))
        }

        root.addView(sectionTitle("SERVER FLEET"), matchWrap(top = 11))
        root.addView(refresh, matchWrap(top = 3))
        root.addView(listStatus, matchWrap(top = 3))
        root.addView(fleet, matchWrap(top = 4))

        setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(root)
        })

        refreshFleet()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshFleet() {
        if (!::refresh.isInitialized) return
        refresh.isEnabled = false
        listStatus.text = "Loading fleet…"
        listStatus.setTextColor(palette.muted)
        fleet.removeAllViews()

        scope.launch {
            val result = StarIntelApiClient.get(this@ActorsActivity).actors()
            refresh.isEnabled = true
            when {
                result.error != null -> {
                    listStatus.text = result.error
                    listStatus.setTextColor(palette.warning)
                }
                result.actors.isEmpty() -> {
                    listStatus.text = "No operator-visible actors"
                    listStatus.setTextColor(palette.muted)
                }
                else -> {
                    var ready = 0
                    listStatus.text = "${result.actors.size} actors · tap to dispatch"
                    listStatus.setTextColor(palette.accent)
                    result.actors
                        .filter { it.name !in ActorCatalog.all.map { known -> known.name } }
                        .forEach { actor ->
                            if (actor.ready) ready++
                            fleet.addView(
                                actorCard(
                                    actor.name,
                                    if (actor.ready) "READY" else actor.status.uppercase(),
                                    actor.accepts,
                                    actor.uri,
                                    ActorCatalog.isMonitor(actor.name),
                                    onClick = { dispatch(actor) },
                                ),
                                matchWrap(top = 4),
                            )
                        }
                    if (ready > 0) listStatus.text = "${result.actors.size} actors · $ready ready"
                }
            }
        }
    }

    private fun dispatch(actor: RemoteActor) {
        startActivity(
            Intent(this, TargetsActivity::class.java)
                .putExtra(TargetsActivity.EXTRA_ACTOR, actor.name)
                .putStringArrayListExtra(TargetsActivity.EXTRA_ACCEPTS, ArrayList(actor.accepts)),
        )
    }

    private fun actorCard(
        name: String,
        status: String,
        accepts: List<String>,
        detail: String,
        monitor: Boolean,
        onClick: (() -> Unit)? = null,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(7), dp(8), dp(7))
        setBackgroundColor(palette.surface)
        if (onClick != null) {
            isClickable = true
            isFocusable = true
            contentDescription = "Actor $name"
            setOnClickListener { onClick() }
        }
        addView(TextView(this@ActorsActivity).apply {
            text = if (status.isBlank()) name else "$name · $status"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.text)
            maxLines = 2
        })
        if (accepts.isNotEmpty()) {
            addView(TextView(this@ActorsActivity).apply {
                text = accepts.joinToString(prefix = "Accepts ", separator = ", ")
                textSize = 9f
                setTextColor(palette.accent)
                maxLines = 2
            })
        }
        addView(TextView(this@ActorsActivity).apply {
            text = if (monitor) "Monitor · runs continuously on the server" else detail
            textSize = 9f
            setTextColor(palette.muted)
            maxLines = 3
        })
        if (onClick != null) {
            addView(TextView(this@ActorsActivity).apply {
                text = if (monitor) "Tap for guidance" else "Tap to dispatch"
                textSize = 9f
                setTextColor(palette.warning)
            })
        }
    }

    private fun sectionTitle(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(palette.accent)
        letterSpacing = .12f
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = top }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
