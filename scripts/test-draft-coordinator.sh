#!/usr/bin/env bash
# Supplemental JVM-only gate; does not replace Android/Keystore/Nix validation.
set -euo pipefail
: "${KOTLIN_TEST_LIBS:?Directory containing Kotlin compiler runtime, stdlib, JUnit 4.13.2 and Hamcrest jars}"
root=$(cd "$(dirname "$0")/.." && pwd)
output=$(mktemp -d)
trap 'rm -rf "$output"' EXIT
classpath=$(find "$KOTLIN_TEST_LIBS" -maxdepth 1 -name '*.jar' -printf '%p:' | sed 's/:$//')
java -cp "$classpath" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$classpath" -d "$output/tests.jar" \
  "$root/quasar-app/src/main/java/actor/starintel/quasar/DraftLimits.kt" \
  "$root/quasar-app/src/main/java/actor/starintel/quasar/DraftCoordinator.kt" \
  "$root/quasar-app/src/test/java/actor/starintel/quasar/DraftCoordinatorTest.kt"
java -cp "$output/tests.jar:$classpath" org.junit.runner.JUnitCore actor.starintel.quasar.DraftCoordinatorTest
