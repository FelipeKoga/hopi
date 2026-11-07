# Hopi for macOS

The desktop viewer as a native SwiftUI app (macOS 26 or later, Liquid Glass). It talks to an app's
bridge over the same NDJSON protocol as the CLI (docs/ARCHITECTURE.md, "Wire protocol").

Reaches today: apps on booted iOS simulators and JVM apps (`bridge.json` plus token). Not yet:
Android (`adb forward`), iPhones over USB, rules, performance, export.

```bash
cd hopi-mac
xcodegen generate     # Hopi.xcodeproj from project.yml
xcodebuild -project Hopi.xcodeproj -scheme Hopi -configuration Release -derivedDataPath build build
open build/Build/Products/Release/Hopi.app
```
