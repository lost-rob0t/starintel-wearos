% Installable artifacts and the publication paths that must retain them.
installable_apk(phone, 'actor.starintel.wear', phone).
installable_apk(quasar, 'actor.starintel.quasar', phone).
installable_apk(collector, 'actor.starintel.collector', phone).
installable_apk(hackmode, 'actor.starintel.hackmode', phone).
installable_apk(operator, 'actor.starintel.operator', phone).
installable_apk(wear, 'actor.starintel.wear', wear).
installable_apk(watchface_neon, 'actor.starintel.watchface.neon', wear).
installable_apk(watchface_command, 'actor.starintel.watchface.command', wear).
installable_apk(watchface_terminal, 'actor.starintel.watchface.terminal', wear).

publication_path(ci_artifact, '.github/workflows/android.yml').
publication_path(master_channel, '.github/workflows/update-channel.yml').
publication_path(tagged_release, '.github/workflows/release.yml').
publication_path(local_release_gate, 'scripts/release.sh').

release_invariant(all_installable_modules_share_version_tuple).
release_invariant(every_apk_built_by_nix_build_all).
release_invariant(every_apk_present_in_update_manifest).
release_invariant(every_apk_staged_before_checksum_and_upload).
release_contract_test('scripts/test-release-contract.py').
