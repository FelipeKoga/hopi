# Hopi for macOS

The desktop viewer as a native SwiftUI app (macOS 26 or later, Liquid Glass). It talks to an app's
bridge over the same NDJSON protocol as the CLI (docs/ARCHITECTURE.md, "Wire protocol").

Reaches apps on Android emulators and devices (`adb forward`, with the app's proof), booted iOS
simulators and JVM apps (`bridge.json` plus token). Three views, ⌘1 to ⌘3:

- **Requests**: the live list, filtered by search and status, the selected one in the inspector.
  Right-click a request to mock its response.
- **Rules**: mock, fail, delay or edit matching requests; hits, limits, enable and remove.
- **Performance**: CPU, memory, frames per second and janky frames as live charts, and hangs,
  janky frames, stalls and memory signals with their stacks. Monitors started here stop when the
  viewer leaves.

Launched with `--app <id>` (and optionally `--platform android|ios|jvm`, `--device <serial or
UDID>`), as `hopi viewer --app <id>` does, it attaches that app as soon as a scan finds it, and
no other one meanwhile.

```bash
cd hopi-mac
xcodegen generate     # Hopi.xcodeproj from project.yml
xcodebuild -project Hopi.xcodeproj -scheme Hopi -configuration Release -derivedDataPath build build
open build/Build/Products/Release/Hopi.app
```

## End-to-end tests

The UI tests drive the viewer against a real app: run a debug build with Hopi (the sample works),
keep it making the request named below (the sample's Items screen, Reload; on an emulator the
sample reaches its dev server through `adb reverse tcp:8765 tcp:8765`), then:

```bash
TEST_RUNNER_HOPI_E2E_APP=dev.koga.hopi.sample TEST_RUNNER_HOPI_E2E_PATH=/items \
  xcodebuild test -project Hopi.xcodeproj -scheme Hopi -destination 'platform=macOS'
```

They add rules to that app and remove them when done. To run them unattended, allow UI automation
without a password once: `sudo automationmodetool enable-automationmode-without-authentication`.
