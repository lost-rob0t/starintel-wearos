package actor.starintel.wear.tiles

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import actor.starintel.wear.ConfigActivity
import actor.starintel.wear.data.StarIntelRepository
import actor.starintel.wear.data.StarIntelSnapshot
import actor.starintel.wear.data.ageLabel
import actor.starintel.wear.data.compactCount

enum class TileKind { OPS, TARGETS, CORPUS }

data class TileCopy(
    val title: String,
    val primary: String,
    val secondary: String,
    val footer: String,
)

abstract class StarIntelTileService(
    private val kind: TileKind,
) : Material3TileService() {
    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        val snapshot = StarIntelRepository.get(applicationContext).snapshot()
        val copy = copyFor(kind, snapshot)
        val pendingIntent = PendingIntent.getActivity(
            this@StarIntelTileService,
            kind.ordinal,
            Intent(this@StarIntelTileService, ConfigActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openApp = requestParams.scope.clickable(
            pendingIntent = pendingIntent,
            id = "open-starintel-${kind.name.lowercase()}",
        )
        val layout = primaryLayout(
            titleSlot = {
                text(copy.title.layoutString, typography = Typography.TITLE_SMALL)
            },
            mainSlot = {
                text(copy.primary.layoutString, typography = Typography.DISPLAY_MEDIUM)
            },
            bottomSlot = {
                text(copy.footer.layoutString, typography = Typography.LABEL_SMALL)
            },
            labelForBottomSlot = {
                text(copy.secondary.layoutString, typography = Typography.LABEL_SMALL)
            },
            onClick = openApp,
        )

        return Tile.Builder()
            .setTileTimeline(Timeline.fromLayoutElement(layout))
            .setFreshnessIntervalMillis(FRESHNESS_MS)
            .build()
    }

    private fun copyFor(kind: TileKind, data: StarIntelSnapshot): TileCopy {
        if (!data.configured) {
            return TileCopy("StarIntel", "SETUP", "Open the app", "server URL")
        }

        return when (kind) {
            TileKind.OPS -> TileCopy(
                title = "StarIntel · Ops",
                primary = when {
                    data.reachable && !data.stale -> "ONLINE"
                    data.reachable -> "STALE"
                    else -> "OFFLINE"
                },
                secondary = "v${data.version}",
                footer = data.ageLabel(),
            )

            TileKind.TARGETS -> TileCopy(
                title = "StarIntel · Targets",
                primary = data.targetsTotal.compactCount(),
                secondary = "${data.targetCount.compactCount()} target · ${data.investigationTargetCount.compactCount()} inv",
                footer = if (data.reachable) data.ageLabel() else "cached",
            )

            TileKind.CORPUS -> {
                val topTypes = data.documentsByType.entries
                    .sortedByDescending { it.value }
                    .take(2)
                    .joinToString(" · ") { "${it.key} ${it.value.compactCount()}" }
                    .ifBlank { "aggregate documents" }
                TileCopy(
                    title = "StarIntel · Corpus",
                    primary = data.documentsTotal.compactCount(),
                    secondary = topTypes,
                    footer = if (data.reachable) data.ageLabel() else "cached",
                )
            }
        }
    }

    companion object {
        private const val FRESHNESS_MS = 60_000L
    }
}

class OpsTileService : StarIntelTileService(TileKind.OPS)
class TargetsTileService : StarIntelTileService(TileKind.TARGETS)
class CorpusTileService : StarIntelTileService(TileKind.CORPUS)
