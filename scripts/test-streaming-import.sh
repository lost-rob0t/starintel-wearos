#!/usr/bin/env bash
# Supplemental JVM gate. Full Android/Nix gates remain mandatory before merge.
# Supply a local directory containing json.jar (20240303), junit.jar (4.13.2),
# hamcrest.jar (1.3), and android-stubs.jar (or an SDK android.jar under that name).
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
tools_dir="${IMPORT_TEST_JARS:?Set IMPORT_TEST_JARS to the dependency jar directory}"
classes="$(mktemp -d)"
trap 'rm -rf "$classes"' EXIT
classpath="$tools_dir/json.jar:$tools_dir/junit.jar:$tools_dir/hamcrest.jar:$tools_dir/android-stubs.jar"
java com.sun.tools.javac.Main -source 17 -target 17 -cp "$classpath" -d "$classes" \
  "$repo_root"/quasar-app/src/main/java/actor/starintel/quasar/importing/*.java \
  "$repo_root"/quasar-app/src/test/java/actor/starintel/quasar/importing/*.java
java -Xmx32m -cp "$classes:$classpath" org.junit.runner.JUnitCore \
  actor.starintel.quasar.importing.StreamingImportTest \
  actor.starintel.quasar.importing.Utf8BudgetTest \
  actor.starintel.quasar.importing.BulkImportTransportTest
