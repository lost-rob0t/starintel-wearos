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
import actor.starintel.wear.ExplorerActivity
import actor.starintel.wear.GraphActivity
import actor.starintel.wear.MainActivity
import actor.starintel.wear.SearchActivity
import actor.starintel.wear.TargetsActivity
import actor.starintel.wear.data.ActivityHistoryStore
import actor.starintel.wear.data.ActivityRange
import actor.starintel.wear.data.SavedSearchStore
import actor.starintel.wear.data.StarIntelRepository
import actor.starintel.wear.data.StarIntelSnapshot
import actor.starintel.wear.data.ageLabel
import actor.starintel.wear.data.compactCount

enum class TileKind { OPS, TARGETS, CORPUS, ACTIVITY, SEARCH }

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
        val activityLastHour = if (kind == TileKind.ACTIVITY) {
            ActivityHistoryStore.get(applicationContext)
                .points(ActivityRange.H1)
                .sumOf { it.documentsAdded ?: 0L }
        } else {
            0L
        }
        val searchStore = if (kind == TileKind.SEARCH) SavedSearchStore(applicationContext) else null
        val copy = copyFor(
            kind = kind,
            data = snapshot,
            activityLastHour = activityLastHour,
            activeSearches = searchStore?.activeCount() ?: 0,
            newSearchMatches = searchStore?.latestNewMatchCount() ?: 0,
        )
        val destination = when {
            !snapshot.configured -> MainActivity::class.java
            kind == TileKind.TARGETS -> TargetsActivity::class.java
            kind == TileKind.CORPUS -> ExplorerActivity::class.java
            kind == TileKind.ACTIVITY -> GraphActivity::class.java
            kind == TileKind.SEARCH -> SearchActivity::class.java
            else -> MainActivity::class.java
        }
        val pendingIntent = PendingIntent.getActivity(
            this@StarIntelTileService,
            kind.ordinal,
            Intent(this@StarIntelTileService, destination),
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

    private fun copyFor(
        kind: TileKind,
        data: StarIntelSnapshot,
        activityLastHour: Long,
        activeSearches: Int,
        newSearchMatches: Int,
    ): TileCopy {
        if (!data.configured) {
            return TileCopy("StarIntel", "SETUP", "Open the app", "server + private key")
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
                secondary = "tap to create · ${data.targetCount.compactCount()} target",
                footer = if (data.reachable) data.ageLabel() else "cached",
            )

            TileKind.CORPUS -> {
                val topTypes = data.documentsByType.entries
                    .sortedByDescending { it.value }
                    .take(2)
                    .joinToString(" · ") { "${it.key} ${it.value.compactCount()}" }
                    .ifBlank { "tap to explore corpus" }
                TileCopy(
                    title = "StarIntel · Explorer",
                    primary = data.documentsTotal.compactCount(),
                    secondary = topTypes,
                    footer = if (data.reachable) "tap to browse · ${data.ageLabel()}" else "cached · tap to browse",
                )
            }

            TileKind.ACTIVITY -> TileCopy(
                title = "StarIntel · Activity",
                primary = "+${activityLastHour.compactCount()}",
                secondary = "docs · last 1h",
                footer = if (data.reachable) "tap for graph · ${data.ageLabel()}" else "cached history · tap for graph",
            )

            TileKind.SEARCH -> TileCopy(
                title = "StarIntel · Search",
                primary = if (newSearchMatches > 0) "+$newSearchMatches" else activeSearches.toString(),
                secondary = if (newSearchMatches > 0) "new matches" else "$activeSearches active monitors",
                footer = "tap to search / configure",
            )
        }
    }

    companion object {
        private const val FRESHNESS_MS = 60_000L
    }
}

class OpsTileService : StarIntelTileService(TileKind.OPS)
class TargetsTileService : StarIntelTileService(TileKind.TARGETS)
class CorpusTileService : StarIntelTileService(TileKind.CORPUS)
class ActivityTileService : StarIntelTileService(TileKind.ACTIVITY)
class SearchTileService : StarIntelTileService(TileKind.SEARCH)
