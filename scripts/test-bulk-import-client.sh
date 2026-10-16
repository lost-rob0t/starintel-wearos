#!/usr/bin/env bash
# Supplemental JVM contract gate; needs Kotlin 2.2.0 compiler/runtime and JUnit jars.
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
tools_dir="${IMPORT_TEST_JARS:?Set IMPORT_TEST_JARS to the dependency jar directory}"
classes="$(mktemp -d)"
trap 'rm -rf "$classes"' EXIT
sources=(
  "$repo_root/starintel-android/src/main/java/actor/starintel/android/model/StarIntelModels.kt"
  "$repo_root/starintel-android/src/main/java/actor/starintel/android/model/RemoteActor.kt"
  "$repo_root/starintel-android/src/main/java/actor/starintel/android/api/StarIntelClient.kt"
  "$repo_root/starintel-android/src/main/java/actor/starintel/android/api/BulkImportSession.kt"
  "$repo_root/starintel-android/src/main/java/actor/starintel/android/api/SearchPage.kt"
  "$repo_root/starintel-android/src/main/java/actor/starintel/android/api/SearchPager.kt"
  "$repo_root/starintel-android/src/test/java/actor/starintel/android/api/ActorConnectionTest.kt"
  "$repo_root/starintel-android/src/test/java/actor/starintel/android/api/BulkImportSessionTest.kt"
  "$repo_root/starintel-android/src/test/java/actor/starintel/android/api/SearchPagingTest.kt"
)
java -cp "$tools_dir/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 17 \
  -classpath "$tools_dir/kotlin-stdlib.jar:$tools_dir/json.jar:$tools_dir/junit.jar" \
  -d "$classes" "${sources[@]}"
java -cp "$classes:$tools_dir/kotlin-stdlib.jar:$tools_dir/json.jar:$tools_dir/junit.jar:$tools_dir/hamcrest.jar" \
  org.junit.runner.JUnitCore actor.starintel.android.api.ActorConnectionTest \
  actor.starintel.android.api.BulkImportSessionTest actor.starintel.android.api.SearchPagingTest
