#!/usr/bin/env bash
# Uploads a Hopi release to Maven Central through the Central Portal's Publisher API
# (central.sonatype.org/publish/publish-portal-api; docs/RELEASING.md), after the release build:
#
#   ./gradlew --no-configuration-cache checkPublication -Phopi.release=true
#
# It zips this version's files of the two repositories that build fills, build/publication-check
# (the libraries) and gradle-plugin/build/functional-repo (the plugin, its marker, hopi-instrument),
# without maven-metadata.xml, into build/central/hopi-<hopi.version>-bundle.zip; uploads it; and
# polls the deployment's status until the portal's verdict. With CENTRAL_PUBLISHING_TYPE=USER_MANAGED
# (the default) the deployment stops at VALIDATED, for Publish at
# https://central.sonatype.com/publishing/deployments; with AUTOMATIC it goes on to PUBLISHED.
#
#   scripts/central-release.sh                  build the bundle, upload it, wait for the verdict
#   scripts/central-release.sh --dry-run        build and list the bundle; no network
#   scripts/central-release.sh --status <id>    wait for deployment <id>'s verdict
#
# Environment: MAVEN_CENTRAL_USERNAME and MAVEN_CENTRAL_PASSWORD, a Central Portal user token
# (Account, Generate User Token; not the account's password); CENTRAL_PUBLISHING_TYPE, USER_MANAGED
# or AUTOMATIC; CENTRAL_TIMEOUT_SECONDS, how long to poll (default 1800).
#
# Exits 0 on VALIDATED (USER_MANAGED) or PUBLISHED; 1 on FAILED (with the portal's errors), a
# timeout or an HTTP error (with the portal's answer); 2 on bad usage or missing input.
set -euo pipefail

api=https://central.sonatype.com/api/v1/publisher
deployments=https://central.sonatype.com/publishing/deployments
repo=$(cd "$(dirname "$0")/.." && pwd -P)
group_path=io/github/felipekoga/hopi
type=${CENTRAL_PUBLISHING_TYPE:-USER_MANAGED}
timeout=${CENTRAL_TIMEOUT_SECONDS:-1800}
interval=10

usage() {
    echo "usage: scripts/central-release.sh [--dry-run | --status <deployment id>]" >&2
    exit 2
}
# fail <message> [exit code, default 1]
fail() {
    echo "central-release.sh: $1" >&2
    exit "${2:-1}"
}

mode=upload
deployment=
case "${1:-}" in
    "") ;;
    --dry-run) [ $# -eq 1 ] || usage; mode=dry-run ;;
    --status) [ $# -eq 2 ] || usage; mode=status; deployment=$2 ;;
    *) usage ;;
esac
case "$type" in
    USER_MANAGED | AUTOMATIC) ;;
    *) fail "CENTRAL_PUBLISHING_TYPE is $type, not USER_MANAGED or AUTOMATIC" 2 ;;
esac
case "$timeout" in
    '' | *[!0-9]*) fail "CENTRAL_TIMEOUT_SECONDS is $timeout, not a number of seconds" 2 ;;
esac

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# The Authorization header, in a file curl reads (-H @file), so the token is on no command line.
authorize() {
    [ -n "${MAVEN_CENTRAL_USERNAME:-}" ] && [ -n "${MAVEN_CENTRAL_PASSWORD:-}" ] ||
        fail "set MAVEN_CENTRAL_USERNAME and MAVEN_CENTRAL_PASSWORD to a Central Portal user token" 2
    command -v curl > /dev/null || fail "needs curl" 2
    command -v jq > /dev/null || fail "needs jq" 2
    local token
    token=$(printf '%s:%s' "$MAVEN_CENTRAL_USERNAME" "$MAVEN_CENTRAL_PASSWORD" | base64 | tr -d '\n')
    (umask 077 && printf 'Authorization: Bearer %s\n' "$token" > "$work/authorization")
}

# request <method> <url> [curl arguments...]: the HTTP status; the body is in $work/body.
request() {
    local method=$1 url=$2
    shift 2
    curl -sS -X "$method" -H @"$work/authorization" -o "$work/body" -w '%{http_code}' "$@" "$url" ||
        fail "could not reach $url"
}

# http_failure <what> <status>: exits with the portal's answer.
http_failure() {
    local hint=""
    case "$2" in
        401 | 403) hint=": the portal refused the credentials; MAVEN_CENTRAL_USERNAME and MAVEN_CENTRAL_PASSWORD must be a user token (Account, Generate User Token)" ;;
    esac
    echo "central-release.sh: $1 failed with HTTP $2$hint" >&2
    [ -s "$work/body" ] && { cat "$work/body" >&2; echo >&2; }
    exit 1
}

# wait_for <deployment id>: polls the status until VALIDATED (USER_MANAGED), PUBLISHED or FAILED.
wait_for() {
    local id=$1 status state previous="" deadline=$((SECONDS + timeout))
    echo "Deployment $id ($type); the portal shows it at $deployments"
    while :; do
        status=$(request POST "$api/status?id=$id")
        [ "$status" = 200 ] || http_failure "the status of deployment $id" "$status"
        state=$(jq -r '.deploymentState // empty' "$work/body")
        [ "$state" = "$previous" ] || echo "$(date '+%H:%M:%S') $state"
        previous=$state
        case "$state" in
            PUBLISHED)
                echo "Published to Maven Central: $(jq -r '.purls // [] | join(" ")' "$work/body")"
                return 0 ;;
            VALIDATED)
                if [ "$type" = USER_MANAGED ]; then
                    echo "Validated. Press Publish for deployment $id at $deployments"
                    return 0
                fi ;;
            FAILED)
                echo "central-release.sh: deployment $id failed validation:" >&2
                jq '.errors // .' "$work/body" >&2
                exit 1 ;;
            PENDING | VALIDATING | PUBLISHING) ;;
            *) fail "deployment $id is in an unknown state: $(cat "$work/body")" ;;
        esac
        [ "$SECONDS" -lt "$deadline" ] || fail "deployment $id is still $state after ${timeout}s; run scripts/central-release.sh --status $id to wait again"
        sleep "$interval"
    done
}

if [ "$mode" = status ]; then
    authorize
    wait_for "$deployment"
    exit 0
fi

version=$(sed -n 's/^hopi\.version=//p' "$repo/gradle.properties" | tr -d '[:space:]')
[ -n "$version" ] || fail "no hopi.version in gradle.properties" 2
case "$version" in *-SNAPSHOT) fail "hopi.version $version is a snapshot, which Maven Central refuses" 2 ;; esac
libraries=$repo/build/publication-check
plugin=$repo/gradle-plugin/build/functional-repo
for root in "$libraries" "$plugin"; do
    [ -d "$root/$group_path" ] ||
        fail "no $root/$group_path: run ./gradlew --no-configuration-cache checkPublication -Phopi.release=true first" 2
done

# This version's directories, <group path>/<artifact>/<version>/, from each repository; every file
# in them but maven-metadata.xml and its checksums (which sit one level up anyway).
for root in "$libraries" "$plugin"; do
    (cd "$root" && find "$group_path" -mindepth 3 -maxdepth 3 -type f -path "$group_path/*/$version/*" ! -name 'maven-metadata.xml*') |
        sed "s|^|$root	|"
done | sort -t '	' -k 2 > "$work/files"
[ -s "$work/files" ] || fail "no file of version $version under $libraries or $plugin" 2
duplicates=$(cut -f 2 "$work/files" | uniq -d)
[ -z "$duplicates" ] || fail "both repositories hold $duplicates" 2

unsigned=$(cut -f 2 "$work/files" | awk '
    { file[$0] = 1 }
    END { for (f in file) if (f !~ /\.(asc|md5|sha1|sha256|sha512)$/ && !((f ".asc") in file)) print f }' | sort)

bundle=$repo/build/central/hopi-$version-bundle.zip
mkdir -p "$(dirname "$bundle")"
rm -f "$bundle"
for root in "$libraries" "$plugin"; do
    awk -F '	' -v root="$root" '$1 == root { print $2 }' "$work/files" > "$work/list"
    [ -s "$work/list" ] || fail "no file of version $version under $root" 2
    (cd "$root" && zip -q -X "$bundle" -@ < "$work/list")
done

if [ "$mode" = dry-run ]; then
    zipinfo -1 "$bundle"
    echo
    echo "Directories:"
    zipinfo -1 "$bundle" | sed 's|/[^/]*$||' | sort | uniq -c | awk '{ printf "  %s (%d files)\n", $2, $1 }'
    echo "$bundle: $(zipinfo -1 "$bundle" | wc -l | tr -d ' ') files, $(du -h "$bundle" | cut -f 1)"
    if [ -n "$unsigned" ]; then
        echo "Not signed, which Maven Central would refuse (run the release build with the key):"
        echo "$unsigned" | sed 's/^/  /'
    fi
    echo "Dry run: nothing uploaded."
    exit 0
fi

[ -z "$unsigned" ] || fail "$(echo "$unsigned" | wc -l | tr -d ' ') files have no signature, for example $(echo "$unsigned" | head -1): run ./gradlew --no-configuration-cache checkPublication -Phopi.release=true with the signing key" 2
authorize
echo "Uploading $bundle ($type)"
status=$(request POST "$api/upload?name=hopi-$version&publishingType=$type" -F "bundle=@$bundle;type=application/octet-stream")
[ "$status" = 201 ] || http_failure "the upload" "$status"
deployment=$(tr -d '[:space:]' < "$work/body")
[ -n "$deployment" ] || fail "the portal returned no deployment id"
wait_for "$deployment"
