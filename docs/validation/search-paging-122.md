# Search paging (#122)

This slice is stacked on native redesign PR120, commit
`82c2a3ae332fdc42c61e487b2607d81210ea9278`; main was
`e80ab4558cc580eff26b13f8f86edf3578143730`. It does not modify the
redesign branch, Quasar PR110, or server transaction work.

## Contract and limits

Inspected server commit `54a5f65f0fb3ecc286da6fe426213f015a255562`:
`cli/http-contract-documents.lisp` declares the `bookmark` query parameter;
`source/frontends/http-contract-routes.lisp` delegates documents.search to
`handle-authorized-search-route` in `http-authorization-routes.lisp`, which
passes the opaque bookmark to Couch FTS and returns the tenant-stripped search
body. Android preserves `rows`, `bookmark`, and optional numeric `total_rows`.
The HTTP fixture checks the actual request field and encoding, with a synthetic
Couch-shaped response. No live authenticated server contract run is claimed.

`searchPage(query, limit, cursor)` is additive. `search(query, limit): JSONArray`
remains the compatibility snapshot wrapper. Fallback remains limited to explicit
404/405 and preserves the bookmark. Authentication, permission, and server errors
are not retried. Cursor values are never interpreted as offsets. Nonempty pages
with unchanged bookmarks are rejected; empty pages end traversal. Missing totals
stay unknown. `hasMore == null` means the server has not proven another page;
a supplied bookmark permits an explicit Next check, including after a short page.
Duplicate IDs within a page are collapsed. Separate pages can contain the same
record if the server changes; there is no global deduplication claim.

The search controller retains at most three pages and a fixed 1 MiB Bloom cycle
guard (three SHA-256-derived bits per cursor). A detected repeat or rare collision
stops traversal with an actionable restart message. Older pages are evicted,
Previous is limited to the retained window, and Search restarts page one. Cached
forward navigation does not refetch or append. Saved state contains only the last
successful query/bookmark/page number, not result bodies; recreation refetches that
page. Earlier pages are not reconstructed after process death. Saved scroll and
selection use the redesign's existing state mechanism.

Field/map behavior remains explicitly bounded to a 100-record snapshot. This
change does not page or expand field features. Search and Datasets have independent
three-page windows. No corpus is materialized and no automatic mutation retry is
introduced.

## Complexity

Before: O(B + P) response parse/projection for one snapshot, not O(N).
After: O(B + P + L) per fetched page for bounded response characters B, page rows
P <= 100 (UI uses 40), and bookmark length L <= 8192; SHA-256 cycle checks are
bounded by L. Cache memory is O(C * B + M) with C <= 3 pages and M = 1 MiB per
search controller. Only two route controllers exist. Three 2 MiB response limits
bound retained page input, rather than total corpus size N. RecyclerView keeps the
redesign's bounded visible holders. No measured speed/memory improvement is claimed.

## Validation

Local JDK21 + Kotlin compiler 2.2.0 JVM17 target, JSON 20240303 and JUnit4.13.2:
11 tests passed (7 new paging tests and 4 existing ActorConnection tests). This
compiles the real StarIntelClient, SearchPage, SearchPager and shared model sources.
The procedural test traverses 1,000,000 generated rows in 25,000 pages, asserts the
three-page bound on every response, then checks the empty terminal page and cached
back/forward navigation. Other tests cover malformed/repeated cursors, optional
totals, duplicate IDs, stale completion, failed-page retry, checkpoint restoration,
and HTTP encoding/fallback/non-retry status behavior.

Both `python3 scripts/test-update-manifest.py` and
`python3 scripts/test-release-contract.py` passed. `git diff --check` passed.
The canonical `nix flake check --no-update-lock-file --show-trace` and
`nix run --no-update-lock-file .#build-all` were attempted but Nix is absent.
Gradle, Android SDK and an emulator are absent locally. Full Android compilation,
instrumentation, screenshot evidence and physical-device checks are not claimed.
The existing WorkstationJourney is extended to traverse pages, recreate on page 2,
return from document detail, retain a page on HTTP503, retry and navigate cached
pages. Its new screenshot name is `quasar-search-paged-restored`; it remains unrun
locally. Hosted CI results are recorded in the PR when available.
