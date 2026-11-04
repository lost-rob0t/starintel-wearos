%% Star server actor registry consumption from Android clients.
%% Source: verified against starintel-server main contract while building
%% quasar actor browsing (branch feat/quasar-actor-browse-watchlist).

%% Server contract: GET /v1/actors, scope actors:read.
%% Response: {status, data:{schema, actors:[entry], count}}; entry keys:
%% resourceUri resourceKind semantic{name,version,digest} accepts produces
%% capabilities operatorVisible provenance status ready observedAt.
server_contract('/v1/actors', actors_read).
registry_entry_bound(1024, 'client rejects larger registries').
remote_actor_visible_if(operatorVisible, true).

%% Watchlist actor (starintel-pro-actors python/src/starintel_pro_actors/watchlist_actor.py)
%% is a monitor: consumes message/social-media-post docs, emits alert docs,
%% rules live server-side; it must not be offered target dispatch.
actor_kind(watchlist, monitor).
actor_accepts(watchlist, [message, 'social-media-post']).
actor_output(watchlist, alert).
dispatch_supported(watchlist, false).
%% Alert browsing is the client projection of monitor output.
alert_browse_query('dtype:alert').

%% quasar-app has zero androidx dependencies: use plain TextWatcher,
%% not androidx ktx helpers.
dependency_invariant(quasar_app, no_androidx).
