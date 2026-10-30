# Aicode Macrobenchmark smoke tests

This module measures the `universalRelease` app variant on a connected physical Android device.
AndroidX Macrobenchmark is used for cold-start timing and frame timing while the chat list is
scrolled during an actual streamed model response. It is intentionally not wired into CI.

## Prerequisites

- Android 10+ physical device, screen on, unlocked, adequately charged, connected with ADB.
- Install/launch the app once, finish onboarding, configure a working provider and default model,
  and verify that a normal chat request succeeds.
- Keep the device on the same power/display settings between runs. Do not use an emulator for
  performance conclusions.
- `chatStreamingScroll` sends one request asking for a long Chinese response. It uses the configured
  provider and may consume its normal API quota. Run it only when that is acceptable.

## Commands

Build the instrumentation APK and its `com.aicode` Release target:

```powershell
.\gradlew.bat :macrobenchmark:assemble
```

Cold startup (five measured launches):

```powershell
.\gradlew.bat :macrobenchmark:connectedCheck "-Pandroid.testInstrumentationRunnerArguments.class=com.aicode.macrobenchmark.AgentMacrobenchmark#coldStartup"
```

Chat streaming + scroll (one request and one measured run):

```powershell
.\gradlew.bat :macrobenchmark:connectedCheck "-Pandroid.testInstrumentationRunnerArguments.class=com.aicode.macrobenchmark.AgentMacrobenchmark#chatStreamingScroll"
```

The benchmark report and Perfetto traces are emitted under
`macrobenchmark/build/outputs/connected_android_test_additional_output/`. Record the device model,
Android build, battery/power state, app version, and measured `timeToInitialDisplay` / frame timing
metrics with each run; do not compare results from different devices as if they were a before/after
pair. This repository has no existing stable device baseline, so these smoke tests do not claim a
performance improvement by themselves.
