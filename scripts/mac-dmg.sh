#!/usr/bin/env bash
# Builds the native macOS viewer (hopi-mac) in Release and packages it as a .dmg, on a Mac with the
# Xcode the project needs (docs/RELEASING.md, "The macOS viewer"). Run locally or by the mac-app
# workflow.
#
#   scripts/mac-dmg.sh [version]     version: the app's MARKETING_VERSION (default: hopi.version)
#
# Writes build/mac/Hopi-<version>.dmg: Hopi.app plus a link to /Applications.
#
# Unsigned by default (the app is signed ad hoc): macOS warns when opening it, and it opens with
# right-click, Open. With these variables set it is signed with a Developer ID, notarized and
# stapled instead, and opens without a warning, even offline:
#
#   MACOS_CERTIFICATE_P12        the "Developer ID Application" certificate and key, .p12, base64
#   MACOS_CERTIFICATE_PASSWORD   the .p12's password
#   APPLE_TEAM_ID                the developer team (10 characters)
#   NOTARY_KEY_P8                an App Store Connect API key (.p8), base64
#   NOTARY_KEY_ID                its Key ID
#   NOTARY_ISSUER_ID             its Issuer ID
#
# The certificate goes into a temporary keychain that is deleted on exit; nothing touches the
# login keychain. Setting some of them but not all is an error: a release is never half signed.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
version=${1:-$(sed -n 's/^hopi\.version=//p' "$root/gradle.properties" | tr -d '[:space:]')}
[ -n "$version" ] || { echo "mac-dmg.sh: no version (pass one, or set hopi.version)" >&2; exit 1; }

signing_vars=(MACOS_CERTIFICATE_P12 MACOS_CERTIFICATE_PASSWORD APPLE_TEAM_ID NOTARY_KEY_P8 NOTARY_KEY_ID NOTARY_ISSUER_ID)
set_count=0
for name in "${signing_vars[@]}"; do [ -n "${!name:-}" ] && set_count=$((set_count + 1)); done
if [ "$set_count" -ne 0 ] && [ "$set_count" -ne "${#signing_vars[@]}" ]; then
    echo "mac-dmg.sh: set all of ${signing_vars[*]}, or none (unsigned)" >&2
    exit 1
fi
sign=$([ "$set_count" -ne 0 ] && echo yes || echo no)

out="$root/build/mac"
work=$(mktemp -d)
keychain="$work/hopi-signing.keychain-db"
previous=()
cleanup() {
    if [ "${#previous[@]}" -gt 0 ]; then security list-keychains -d user -s "${previous[@]}"; fi
    [ -f "$keychain" ] && security delete-keychain "$keychain" 2>/dev/null || true
    rm -rf "$work"
}
trap cleanup EXIT
mkdir -p "$out"

identity="-"
build_settings=(MARKETING_VERSION="$version")
if [ "$sign" = yes ]; then
    keychain_password=$(uuidgen)
    security create-keychain -p "$keychain_password" "$keychain"
    security set-keychain-settings -lut 3600 "$keychain"
    security unlock-keychain -p "$keychain_password" "$keychain"
    printf '%s' "$MACOS_CERTIFICATE_P12" | base64 --decode > "$work/certificate.p12"
    security import "$work/certificate.p12" -k "$keychain" -P "$MACOS_CERTIFICATE_PASSWORD" -T /usr/bin/codesign
    rm -f "$work/certificate.p12"
    security set-key-partition-list -S apple-tool:,apple: -s -k "$keychain_password" "$keychain" >/dev/null
    # codesign finds the identity only in a keychain on the search list: add ours for this run.
    while IFS= read -r line; do
        line=${line#"${line%%[![:space:]]*}"}
        previous+=("${line//\"/}")
    done < <(security list-keychains -d user)
    security list-keychains -d user -s "$keychain" "${previous[@]}"
    identity="Developer ID Application"
    build_settings+=(
        CODE_SIGN_IDENTITY="$identity" CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM="$APPLE_TEAM_ID"
        OTHER_CODE_SIGN_FLAGS="--timestamp --keychain $keychain"
    )
fi

echo "Building Hopi.app $version ($([ "$sign" = yes ] && echo "Developer ID" || echo "unsigned"))"
xcodebuild -project "$root/hopi-mac/Hopi.xcodeproj" -scheme Hopi -configuration Release \
    -derivedDataPath "$work/derived" "${build_settings[@]}" build -quiet
app="$work/derived/Build/Products/Release/Hopi.app"
codesign --verify --deep --strict "$app"

stage="$work/stage"
mkdir -p "$stage"
cp -R "$app" "$stage/"
ln -s /Applications "$stage/Applications"
dmg="$out/Hopi-$version.dmg"
rm -f "$dmg"
hdiutil create -volname "Hopi $version" -srcfolder "$stage" -fs HFS+ -format UDZO -ov "$dmg" -quiet

if [ "$sign" = yes ]; then
    codesign --sign "$identity" --timestamp --keychain "$keychain" "$dmg"
    printf '%s' "$NOTARY_KEY_P8" | base64 --decode > "$work/notary.p8"
    echo "Notarizing (several minutes)"
    xcrun notarytool submit "$dmg" --key "$work/notary.p8" --key-id "$NOTARY_KEY_ID" --issuer "$NOTARY_ISSUER_ID" --wait
    xcrun stapler staple "$dmg"
    spctl --assess --type open --context context:primary-signature --verbose "$dmg"
fi

echo "$dmg"
