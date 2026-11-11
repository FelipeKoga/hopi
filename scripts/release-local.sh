#!/usr/bin/env bash
# Releases Hopi to Maven Central from the owner's Mac (docs/RELEASING.md), the fallback to the
# release workflow when no macOS runner is available: the iOS klibs build on macOS alone (their
# Objective-C cinterop needs Xcode). On the commit tagged v<hopi.version>, with a clean
# tree, it runs:
#
#   1. the iOS simulator tests and the release guards (verifyReleaseHasNoRuntime,
#      verifyIosReleaseExcludesCapture, the plugin's functional tests), with no build cache;
#   2. the release build, ./gradlew --no-configuration-cache checkPublication -Phopi.release=true,
#      signed with the key from your gpg keyring;
#   3. scripts/central-release.sh, which uploads the bundle and waits for VALIDATED.
#
# Then press Publish at https://central.sonatype.com/publishing/deployments.
#
#   scripts/release-local.sh             all three steps
#   scripts/release-local.sh --dry-run   steps 1 and 2, then the bundle's listing; no upload
#
# Environment: HOPI_SIGNING_KEY, the key's user ID or fingerprint in gpg (default "koga (Hopi
# release signing)", scripts/release-secrets.sh's default); CENTRAL_PUBLISHING_TYPE, as for
# scripts/central-release.sh. It asks for the key's passphrase and, unless MAVEN_CENTRAL_USERNAME
# and MAVEN_CENTRAL_PASSWORD are set, for the Central Portal user token. Nothing it asks for is
# written to disk or put on a command line: the key and the token reach Gradle and the upload
# through this script's environment only.
set -euo pipefail

repo=$(cd "$(dirname "$0")/.." && pwd -P)
cd "$repo"
key_id=${HOPI_SIGNING_KEY:-koga (Hopi release signing)}

fail() {
    echo "release-local.sh: $*" >&2
    exit 1
}

dry_run=false
case "${1:-}" in
    "") ;;
    --dry-run) [ $# -eq 1 ] || fail "usage: scripts/release-local.sh [--dry-run]"; dry_run=true ;;
    *) fail "usage: scripts/release-local.sh [--dry-run]" ;;
esac

[ "$(uname -s)" = Darwin ] || fail "a release builds the iOS klibs, which needs a Mac with Xcode"
command -v gpg > /dev/null || fail "needs gpg: brew install gnupg"
command -v xcodebuild > /dev/null || fail "needs Xcode"

version=$(sed -n 's/^hopi\.version=//p' gradle.properties | tr -d '[:space:]')
[ -n "$version" ] || fail "gradle.properties has no hopi.version"
tag="v$version"
[ -z "$(git status --porcelain --untracked-files=no)" ] || fail "the tree has uncommitted changes: commit or stash them"
tagged=$(git rev-parse -q --verify "refs/tags/$tag^{commit}") || fail "no tag $tag: git tag $tag, on the commit to release"
[ "$tagged" = "$(git rev-parse HEAD)" ] || fail "HEAD is not $tag: git checkout $tag"
gpg --list-secret-keys "$key_id" > /dev/null 2>&1 || fail "gpg has no secret key \"$key_id\": set HOPI_SIGNING_KEY, or run scripts/release-secrets.sh"

# Everything asked first, so the long build runs unattended.
read -r -s -p "Passphrase of the signing key \"$key_id\": " passphrase || true
echo >&2
[ -n "$passphrase" ] || fail "no passphrase"
key=$(gpg --batch --pinentry-mode loopback --passphrase-fd 3 --armor --export-secret-keys "$key_id" 3<<< "$passphrase") ||
    fail "gpg could not export \"$key_id\" (a wrong passphrase?)"
case "$key" in
    "-----BEGIN PGP PRIVATE KEY BLOCK-----"*) ;;
    *) fail "gpg exported no secret key for \"$key_id\"" ;;
esac
if ! $dry_run; then
    if [ -z "${MAVEN_CENTRAL_USERNAME:-}" ] || [ -z "${MAVEN_CENTRAL_PASSWORD:-}" ]; then
        read -r -p "Central Portal user token, username: " MAVEN_CENTRAL_USERNAME || true
        read -r -s -p "Central Portal user token, password: " MAVEN_CENTRAL_PASSWORD || true
        echo >&2
        [ -n "$MAVEN_CENTRAL_USERNAME" ] && [ -n "$MAVEN_CENTRAL_PASSWORD" ] || fail "the user token needs both its username and its password"
    fi
    export MAVEN_CENTRAL_USERNAME MAVEN_CENTRAL_PASSWORD
fi

echo "Releasing Hopi $version ($tag, $(xcodebuild -version | head -n 1))" >&2
./gradlew --no-configuration-cache --no-build-cache iosSimulatorArm64Test \
    :sample:androidApp:verifyReleaseHasNoRuntime :sample:shared:verifyIosReleaseExcludesCapture \
    :gradle-plugin:hopi-gradle-plugin:functionalTest

ORG_GRADLE_PROJECT_signingInMemoryKey=$key ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=$passphrase \
    ./gradlew --no-configuration-cache --no-build-cache checkPublication -Phopi.release=true

if $dry_run; then
    scripts/central-release.sh --dry-run
else
    scripts/central-release.sh
fi
