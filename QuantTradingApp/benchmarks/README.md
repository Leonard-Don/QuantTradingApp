# Android baseline profile benchmark skeleton

This module hosts Macrobenchmark/Baseline Profile instrumentation tests for the QuantTradingApp Android app. It follows the AndroidX Baseline Profile workflow at a project-specific, minimal scope:

- `BaselineProfileGenerator` launches the app and records startup classes/methods.
- `StartupBenchmark` compares cold startup with no compilation vs partial compilation.
- The `:app` module consumes this module through `baselineProfile(project(":benchmarks"))`.

## Local verification

This repo's Kotlin/Gradle stack requires Java 17 for Gradle script compilation:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH=/opt/homebrew/opt/openjdk@17/bin:$PATH
```

A full assemble/collection run also requires a local Android SDK (`ANDROID_HOME` or `local.properties`). The instrumentation tasks additionally require a connected managed/physical device:

```bash
./gradlew :benchmarks:assembleBenchmarkRelease :app:assembleDemo
./gradlew :benchmarks:connectedBenchmarkReleaseAndroidTest
./gradlew :benchmarks:collectNonMinifiedReleaseBaselineProfile
```

This app currently uses build types (`debug`, `demo`, `release`) and does not define product flavors, so the benchmark module does not need a matching flavor dimension. If product flavors are added later, mirror the relevant dimensions in `:benchmarks` before collecting profiles.

On machines with Java 17 but without Android SDK/device access, use `./gradlew :benchmarks:tasks --all` as the lightweight configuration smoke; it verifies that the Gradle module is visible and exposes the expected `collectNonMinifiedReleaseBaselineProfile` and benchmark tasks without touching signing or release secrets. If `:app` release minification changes, re-check the actual collection task name with `./gradlew :benchmarks:tasks --all` before running the command above.
