# Quasar field map data and tile policy

Quasar's Field surface is a native Android canvas. It does not use a WebView,
Google Maps, Mapbox, or a proprietary map/data SDK. Geo-document markers and
links are projections of Star server search results; the client never creates
roads, observations, or locations to make an offline screen look populated.

## Geo-document projection

The mobile projection accepts bounded search results with one of these point
representations:

- GeoJSON `geometry: {"type":"Point","coordinates":[longitude, latitude]}`
- JSON-LD-style `geo.latitude` and `geo.longitude`
- nested `location` geometry/coordinates
- top-level `latitude`/`longitude` or `lat`/`lon`

Rows without both valid coordinates and a stable `_id`, `id`, or `@id` are not
shown. `links`, `relations`, `relationships`, and `related` identifiers become
edges only when both endpoint documents are present in the current filtered
result set. This keeps the graph honest about incomplete server results.

## Open map tiles

The default interactive basemap uses the public OpenStreetMap raster tile URL.
Every rendered map keeps the required `© OpenStreetMap contributors`
attribution visible. Requests identify Quasar with a stable User-Agent, accept
only images, cap a response at 1 MiB, and use at most two loader threads.

The platform HTTP cache is bounded to 48 MiB and honors server cache headers and
conditional requests. There is deliberately no prefetch, scrape, region
download, background seeding, or cache-warming API for the public tile service.
Cached tiles can be reused for a bounded seven-day degraded window. The public
OpenStreetMap tile service is not an offline map package host.

Production deployments needing guaranteed offline regions or higher request
volume must operate or select an OpenStreetMap-compatible tile service whose
terms allow that workload. A future configurable endpoint must preserve HTTPS,
visible attribution, bounded response/cache limits, and an operator-provided
offline policy; changing the URL must not silently imply an offline license.

## Degraded-state contract

The UI reports basemap and intelligence data independently:

- `MAP LIVE`: a tile was served while network connectivity was available.
- `CACHED MAP`: a tile was served from the HTTP cache without connectivity.
- `NO BASEMAP`: no tile is available; only a neutral coordinate grid is drawn.
- `LOCAL / NO SERVER`: there is no authenticated Star server configuration.
- `DATA DEGRADED`: the server query failed; existing loaded features remain.

The grid is orientation scaffolding, not geographic content. Quasar requests no
device-location permission in this slice: distance and bearing are calculated
from the map crosshair to the selected document, not from the operator's live
position.
