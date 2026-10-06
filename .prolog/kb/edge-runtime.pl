% Verified Android runtime ownership and acceptance boundaries.
android_edge_runtime_owner(starintel_edge, 'd658c0ad7130cab3fa8d99d7d3476c9353d87eeb').
android_edge_runtime_engine(ecl, sento).
android_edge_runtime_operation('runtime.ping').
android_edge_runtime_operation('actor.roundtrip').
android_edge_runtime_operation('actor.list').
android_edge_runtime_operation('actor.dispatch').
android_edge_runtime_invariant(no_client_actor_registry).
android_edge_runtime_invariant(no_general_eval_or_load).
android_edge_runtime_invariant(immutable_assets_copied_to_app_private_storage).
android_edge_runtime_invariant(actor_ids_must_be_registered_by_trusted_edge_image).

collector_ui_invariant(system_bar_insets, 'Si.install').
collector_ui_invariant(capture_layout, two_by_two_action_tiles).
collector_ui_invariant(runtime_state, real_edge_snapshot_not_simulated_readiness).

emulator_acceptance(edge_runtime, api_36_x86_64_art_sento_roundtrip).
emulator_acceptance(collector_ui, waits_for_ecl_sento_ready).
emulator_acceptance(wireless, mocked_wifi_observation_tests).
emulator_acceptance(wigle, rows(325000)).
