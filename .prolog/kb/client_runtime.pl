% Reusable behavior and verification boundaries for the native clients.
client_endpoint(radar, '/api/v1/documents/search?feed=recent&limit=24').
public_endpoint(radar, '/api/v1/search?feed=recent&limit=24').
radar_contract(1, bounded_snapshot, max_documents(32)).
radar_lifecycle(foreground_poll_seconds(15), cancel_on_pause).
radar_failure(retain_last_snapshot, label_stale).
graph_axis(elapsed_time, additions_per_observation).
graph_discontinuity(counter_reset).
graph_discontinuity(missing_observation).
health_provider_types(['SHORT_TEXT', 'RANGED_VALUE']).
health_gauge_invariant(clamp_to_provider_range).
health_gauge_invariant(empty_when_max_not_greater_than_min).
render_test('ClientScreensTest', robolectric_native_api_35, round_dp(227)).
acceptance_boundary(mock_render, does_not_prove_hardware_provider_or_wff_rendering).
