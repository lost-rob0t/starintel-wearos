{
  description = "Nix build environment for StarIntel Android + Wear OS apps";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f system);

      mkEnv = system:
        let
          pkgs = import nixpkgs {
            inherit system;
            config = {
              android_sdk.accept_license = true;
              allowUnfree = true;
            };
          };

          androidComposition = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "36" ];
            buildToolsVersions = [ "36.0.0" ];
            includeSources = false;
            includeSystemImages = false;
            includeEmulator = false;
            includeNDK = false;
          };

          androidSdk = androidComposition.androidsdk;
          jdk = pkgs.jdk17;
          gradle = pkgs.gradle_9.override { java = jdk; };
          androidHome = "${androidSdk}/libexec/android-sdk";
          aapt2 = "${androidHome}/build-tools/36.0.0/aapt2";

          common = ''
            if [[ ! -f settings.gradle.kts ]]; then
              echo "error: run this from the starintel-wearos repository root" >&2
              exit 2
            fi
            export ANDROID_HOME="${androidHome}"
            export ANDROID_SDK_ROOT="$ANDROID_HOME"
            export JAVA_HOME="${jdk}"
            export GRADLE_USER_HOME="''${GRADLE_USER_HOME:-''${XDG_CACHE_HOME:-$HOME/.cache}/starintel-wearos/gradle}"
            export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=${aapt2} -Dorg.gradle.java.home=$JAVA_HOME ''${GRADLE_OPTS:-}"
            mkdir -p "$GRADLE_USER_HOME" build/nix
          '';

          wffContractChecks = ''
            python3 scripts/check-watchface-slot-contract.py
            python3 scripts/check-watchface-layout.py
            python3 scripts/check-watchface-themes.py
          '';

          mkBuildApp = { name, task, sourceApk, outputName }:
            pkgs.writeShellApplication {
              name = "starintel-${name}";
              runtimeInputs = [ gradle pkgs.coreutils ];
              text = common + ''
                gradle --no-daemon --stacktrace ${task}
                cp -f "${sourceApk}" "build/nix/${outputName}"
                echo "built build/nix/${outputName}"
              '';
            };

          buildPhone = mkBuildApp {
            name = "build-phone";
            task = ":phone-app:assembleDebug";
            sourceApk = "phone-app/build/outputs/apk/debug/phone-app-debug.apk";
            outputName = "phone-app-debug.apk";
          };

          buildQuasar = mkBuildApp {
            name = "build-quasar";
            task = ":quasar-app:assembleDebug";
            sourceApk = "quasar-app/build/outputs/apk/debug/quasar-app-debug.apk";
            outputName = "quasar-app-debug.apk";
          };

          buildOperator = mkBuildApp {
            name = "build-operator";
            task = ":operator-app:assembleDebug";
            sourceApk = "operator-app/build/outputs/apk/debug/operator-app-debug.apk";
            outputName = "operator-app-debug.apk";
          };

          buildCollector = mkBuildApp {
            name = "build-collector";
            task = ":collector-app:assembleDebug";
            sourceApk = "collector-app/build/outputs/apk/debug/collector-app-debug.apk";
            outputName = "collector-app-debug.apk";
          };

          buildMaps = mkBuildApp {
            name = "build-maps";
            task = ":maps-app:assembleDebug";
            sourceApk = "maps-app/build/outputs/apk/debug/maps-app-debug.apk";
            outputName = "maps-app-debug.apk";
          };

          buildWear = mkBuildApp {
            name = "build-wear";
            task = ":wear-app:assembleDebug";
            sourceApk = "wear-app/build/outputs/apk/debug/wear-app-debug.apk";
            outputName = "wear-app-debug.apk";
          };

          buildWatchface = pkgs.writeShellApplication {
            name = "starintel-build-watchface";
            runtimeInputs = [ gradle pkgs.coreutils pkgs.python3 ];
            text = common + wffContractChecks + ''
              gradle --no-daemon --stacktrace \
                :watchface:assembleNeonDebug \
                :watchface:assembleCommandDebug \
                :watchface:assembleTerminalDebug
              cp -f watchface/build/outputs/apk/neon/debug/watchface-neon-debug.apk build/nix/watchface-neon-debug.apk
              cp -f watchface/build/outputs/apk/command/debug/watchface-command-debug.apk build/nix/watchface-command-debug.apk
              cp -f watchface/build/outputs/apk/terminal/debug/watchface-terminal-debug.apk build/nix/watchface-terminal-debug.apk
              printf 'built:\n  %s\n  %s\n  %s\n' \
                build/nix/watchface-neon-debug.apk \
                build/nix/watchface-command-debug.apk \
                build/nix/watchface-terminal-debug.apk
            '';
          };

          buildAll = pkgs.writeShellApplication {
            name = "starintel-build-all";
            runtimeInputs = [ gradle pkgs.coreutils pkgs.python3 ];
            text = common + wffContractChecks + ''
              gradle --no-daemon --stacktrace \
                :android-contracts:test \
                :phone-app:testDebugUnitTest :phone-app:assembleDebug \
                :quasar-app:testDebugUnitTest :quasar-app:assembleDebug \
                :operator-app:testDebugUnitTest :operator-app:assembleDebug \
                :collector-app:testDebugUnitTest :collector-app:assembleDebug \
                :maps-app:testDebugUnitTest :maps-app:assembleDebug \
                :wear-app:testDebugUnitTest :wear-app:assembleDebug \
                :watchface:assembleNeonDebug \
                :watchface:assembleCommandDebug \
                :watchface:assembleTerminalDebug

              cp -f phone-app/build/outputs/apk/debug/phone-app-debug.apk build/nix/phone-app-debug.apk
              cp -f quasar-app/build/outputs/apk/debug/quasar-app-debug.apk build/nix/quasar-app-debug.apk
              cp -f operator-app/build/outputs/apk/debug/operator-app-debug.apk build/nix/operator-app-debug.apk
              cp -f collector-app/build/outputs/apk/debug/collector-app-debug.apk build/nix/collector-app-debug.apk
              cp -f maps-app/build/outputs/apk/debug/maps-app-debug.apk build/nix/maps-app-debug.apk
              cp -f wear-app/build/outputs/apk/debug/wear-app-debug.apk build/nix/wear-app-debug.apk
              cp -f watchface/build/outputs/apk/neon/debug/watchface-neon-debug.apk build/nix/watchface-neon-debug.apk
              cp -f watchface/build/outputs/apk/command/debug/watchface-command-debug.apk build/nix/watchface-command-debug.apk
              cp -f watchface/build/outputs/apk/terminal/debug/watchface-terminal-debug.apk build/nix/watchface-terminal-debug.apk

              echo "built:"
              printf '  %s\n' \
                build/nix/phone-app-debug.apk \
                build/nix/quasar-app-debug.apk \
                build/nix/operator-app-debug.apk \
                build/nix/collector-app-debug.apk \
                build/nix/maps-app-debug.apk \
                build/nix/wear-app-debug.apk \
                build/nix/watchface-neon-debug.apk \
                build/nix/watchface-command-debug.apk \
                build/nix/watchface-terminal-debug.apk
            '';
          };

          checkAll = pkgs.writeShellApplication {
            name = "starintel-check";
            runtimeInputs = [ gradle pkgs.python3 ];
            text = common + wffContractChecks + ''
              gradle --no-daemon --stacktrace \
                :android-contracts:test \
                :phone-app:testDebugUnitTest \
                :quasar-app:testDebugUnitTest \
                :operator-app:testDebugUnitTest \
                :collector-app:testDebugUnitTest \
                :maps-app:testDebugUnitTest \
                :wear-app:testDebugUnitTest \
                :watchface:assembleNeonDebug \
                :watchface:assembleCommandDebug \
                :watchface:assembleTerminalDebug
            '';
          };

          pairAndroid = pkgs.writeShellApplication {
            name = "starintel-pair-android";
            runtimeInputs = [ androidSdk pkgs.bash pkgs.gawk pkgs.coreutils pkgs.qrencode ];
            text = ''
              if [[ ! -f scripts/pair-android.sh ]]; then
                echo "error: run this from the starintel-wearos repository root" >&2
                exit 2
              fi
              exec bash scripts/pair-android.sh "$@"
            '';
          };

          pairWatch = pkgs.writeShellApplication {
            name = "starintel-pair-watch";
            runtimeInputs = [ androidSdk pkgs.bash pkgs.gawk pkgs.coreutils pkgs.qrencode ];
            text = ''
              if [[ ! -f scripts/pair-watch.sh ]]; then
                echo "error: run this from the starintel-wearos repository root" >&2
                exit 2
              fi
              exec bash scripts/pair-watch.sh "$@"
            '';
          };

          installPhone = pkgs.writeShellApplication {
            name = "starintel-install-phone";
            runtimeInputs = [ androidSdk pkgs.bash pkgs.gnugrep ];
            text = ''
              if [[ ! -f scripts/install-phone.sh ]]; then
                echo "error: run this from the starintel-wearos repository root" >&2
                exit 2
              fi
              export STARINTEL_PHONE_APK="''${STARINTEL_PHONE_APK:-build/nix/phone-app-debug.apk}"
              exec bash scripts/install-phone.sh "$@"
            '';
          };

          installWatch = pkgs.writeShellApplication {
            name = "starintel-install-watch";
            runtimeInputs = [ androidSdk pkgs.bash ];
            text = ''
              if [[ ! -f scripts/install-watch.sh ]]; then
                echo "error: run this from the starintel-wearos repository root" >&2
                exit 2
              fi
              export STARINTEL_WEAR_APK="''${STARINTEL_WEAR_APK:-build/nix/wear-app-debug.apk}"
              export STARINTEL_NEON_FACE_APK="''${STARINTEL_NEON_FACE_APK:-build/nix/watchface-neon-debug.apk}"
              export STARINTEL_COMMAND_FACE_APK="''${STARINTEL_COMMAND_FACE_APK:-build/nix/watchface-command-debug.apk}"
              export STARINTEL_TERMINAL_FACE_APK="''${STARINTEL_TERMINAL_FACE_APK:-build/nix/watchface-terminal-debug.apk}"
              exec bash scripts/install-watch.sh "$@"
            '';
          };

          toolchain = pkgs.buildEnv {
            name = "starintel-wearos-android-toolchain";
            paths = [ jdk gradle androidSdk pkgs.python3 ];
          };
        in
        {
          inherit pkgs androidSdk jdk gradle toolchain buildPhone buildQuasar buildOperator buildCollector buildMaps buildWear buildWatchface buildAll checkAll pairAndroid pairWatch installPhone installWatch;
        };
    in
    {
      packages = forAllSystems (system:
        let e = mkEnv system;
        in {
          android-sdk = e.androidSdk;
          gradle = e.gradle;
          toolchain = e.toolchain;
          default = e.toolchain;
        });

      apps = forAllSystems (system:
        let e = mkEnv system;
        in {
          build-phone = { type = "app"; program = "${e.buildPhone}/bin/starintel-build-phone"; };
          build-quasar = { type = "app"; program = "${e.buildQuasar}/bin/starintel-build-quasar"; };
          build-operator = { type = "app"; program = "${e.buildOperator}/bin/starintel-build-operator"; };
          build-collector = { type = "app"; program = "${e.buildCollector}/bin/starintel-build-collector"; };
          build-maps = { type = "app"; program = "${e.buildMaps}/bin/starintel-build-maps"; };
          build-wear = { type = "app"; program = "${e.buildWear}/bin/starintel-build-wear"; };
          build-watchface = { type = "app"; program = "${e.buildWatchface}/bin/starintel-build-watchface"; };
          build-all = { type = "app"; program = "${e.buildAll}/bin/starintel-build-all"; };
          check = { type = "app"; program = "${e.checkAll}/bin/starintel-check"; };
          pair-android = { type = "app"; program = "${e.pairAndroid}/bin/starintel-pair-android"; };
          pair-watch = { type = "app"; program = "${e.pairWatch}/bin/starintel-pair-watch"; };
          install-phone = { type = "app"; program = "${e.installPhone}/bin/starintel-install-phone"; };
          install-watch = { type = "app"; program = "${e.installWatch}/bin/starintel-install-watch"; };
          default = { type = "app"; program = "${e.buildAll}/bin/starintel-build-all"; };
        });

      devShells = forAllSystems (system:
        let e = mkEnv system;
        in {
          default = e.pkgs.mkShell {
            packages = [ e.jdk e.gradle e.androidSdk e.pkgs.qrencode e.pkgs.python3 ];
            ANDROID_HOME = "${e.androidSdk}/libexec/android-sdk";
            ANDROID_SDK_ROOT = "${e.androidSdk}/libexec/android-sdk";
            JAVA_HOME = "${e.jdk}";
            shellHook = ''
              export GRADLE_USER_HOME="''${GRADLE_USER_HOME:-''${XDG_CACHE_HOME:-$HOME/.cache}/starintel-wearos/gradle}"
              export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/36.0.0/aapt2 -Dorg.gradle.java.home=$JAVA_HOME ''${GRADLE_OPTS:-}"
              mkdir -p "$GRADLE_USER_HOME"
              echo "StarIntel Wear OS Nix shell"
              echo "  Java:   $(java -version 2>&1 | head -n1)"
              echo "  Gradle: $(gradle --version | awk '/^Gradle / { print $2; exit }')"
              echo "  SDK:    $ANDROID_HOME"
            '';
          };
        });

      checks = forAllSystems (system:
        let e = mkEnv system;
        in { toolchain = e.toolchain; });
    };
}
