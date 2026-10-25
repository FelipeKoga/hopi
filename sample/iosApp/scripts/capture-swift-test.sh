#!/bin/sh
# Runs the Swift capture tests (iosAppCaptureTests) on a simulator against the sample's dev server.
# Usage: sample/iosApp/scripts/capture-swift-test.sh ["platform=iOS Simulator,name=iPhone 16,OS=18.2"]
# Not part of the base check: it boots a simulator. xcodebuild builds the Kotlin framework through
# the target's Gradle build phase, so no other Gradle build may run meanwhile.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../.." && pwd)
destination=${1:-"platform=iOS Simulator,name=iPhone 16,OS=18.2"}
python3 "$root/sample/devserver/devserver.py" > "${TMPDIR:-/tmp}/hopi-devserver.log" 2>&1 &
server=$!
trap 'kill "$server" 2>/dev/null || true' EXIT INT TERM
xcodebuild test -project "$root/sample/iosApp/iosApp.xcodeproj" -scheme iosApp -configuration Debug \
    -destination "$destination" -only-testing:iosAppCaptureTests
