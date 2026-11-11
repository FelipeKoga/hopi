#!/usr/bin/env bash
# Hopi's consumer matrix: builds the apps in compat/ against the artifacts `./gradlew checkPublication`
# publishes (build/publication-check, and gradle-plugin/build/functional-repo for the plugin), on
# Hopi's floor and on the current toolchain, and prints one PASS or FAIL line per run. Exits 1 when a
# run fails, 2 when the matrix cannot start.
#
#   compat/run.sh            every run
#   compat/run.sh floor      the floor's runs only (or: current)
#
# The floor builds with Gradle 8.0.2 (compat/floor-gradle) in a Gradle user home of its own,
# build/compat-home, which can be deleted whole; the current runs use the repository's wrapper and
# ~/.gradle. Every run uses JDK 17 and --no-daemon, so no Gradle stays behind. Logs:
# build/compat/<run>.log. What each run proves: compat/README.md.
set -u

repo=$(cd "$(dirname "$0")/.." && pwd -P)
floor_home="$repo/build/compat-home"
logs="$repo/build/compat"

# The floor, as README states it (the catalog's floor-* entries where it has them), and the current
# toolchain: the repository's AGP, with the Kotlin and Ktor of an app on today's releases.
floor_agp=8.0.2         # floor-agp-plugin
floor_kgp=2.2.0         # floor-kgp
floor_kgp_ios=2.2.21    # kotlin: the Kotlin that writes Hopi's klibs
floor_ktor=3.3.3        # ktor
current_agp=9.4.0       # agp
current_kgp=2.4.20
current_ktor=3.5.2      # ktor-forward
old_kgp=2.1.21          # below the Kotlin Gradle plugin io.github.felipekoga.hopi needs in a KMP module, 2.2.0

only=${1:-all}
case "$only" in
    all | floor | current) ;;
    *) echo "usage: compat/run.sh [floor|current]" >&2; exit 2 ;;
esac

if [ ! -d "$repo/build/publication-check/io/github/felipekoga/hopi" ] ||
    [ ! -d "$repo/gradle-plugin/build/functional-repo/io/github/felipekoga/hopi/hopi-gradle-plugin" ]; then
    echo "compat/run.sh: build/publication-check or gradle-plugin/build/functional-repo is missing: run ./gradlew checkPublication first" >&2
    exit 2
fi

gradle_of() { sed -n 's/^distributionUrl=.*gradle-\([0-9.]*\)-bin\.zip$/\1/p' "$1/gradle/wrapper/gradle-wrapper.properties"; }
floor_gradle=$(gradle_of "$repo/compat/floor-gradle")
current_gradle=$(gradle_of "$repo")

# JDK 17 for every run: Gradle 8.0 runs on no JDK newer than 19.
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null) || JAVA_HOME=
fi
if [ -n "${JAVA_HOME:-}" ]; then
    export JAVA_HOME
    java="$JAVA_HOME/bin/java"
else
    unset JAVA_HOME
    java=java
fi
if ! command -v "$java" > /dev/null; then
    echo "compat/run.sh: needs JDK 17 (set JAVA_HOME); no $java" >&2
    exit 2
elif ! "$java" -version 2>&1 | grep -q 'version "17[."]'; then
    echo "compat/run.sh: needs JDK 17 (set JAVA_HOME); found: $("$java" -version 2>&1 | head -1)" >&2
    exit 2
fi

# compat/android's SDK, unless ANDROID_HOME or a compat/android/local.properties names one: the
# repository's.
if [ -z "${ANDROID_HOME:-}" ] && [ ! -f "$repo/compat/android/local.properties" ]; then
    sdk=$(sed -n 's/^sdk\.dir=//p' "$repo/local.properties" 2>/dev/null)
    export ANDROID_HOME="${sdk:-$HOME/Library/Android/sdk}"
fi

mkdir -p "$logs"
passed=0
failed=0

# build_fixture <floor|current> <fixture> <arguments...>: one Gradle build of compat/<fixture>.
build_fixture() {
    local stack=$1 fixture=$2
    shift 2
    if [ "$stack" = floor ]; then
        GRADLE_USER_HOME="$floor_home" "$repo/compat/floor-gradle/gradlew" --no-daemon -p "$repo/compat/$fixture" "$@"
    else
        "$repo/gradlew" --no-daemon -p "$repo/compat/$fixture" "$@"
    fi
}

# result <log name> <label> <problems> <details> <start>: the run's line; no problems is a pass.
result() {
    local seconds=$((SECONDS - $5))
    if [ -z "$3" ]; then
        passed=$((passed + 1))
        printf 'PASS  %-55s  %s (%ss)\n' "$2" "$4" "$seconds"
    else
        failed=$((failed + 1))
        printf 'FAIL  %-55s  %s (%ss; log: build/compat/%s.log)\n' "$2" "${3#; }" "$seconds" "$1"
    fi
}

# run_android <floor|current> <agp> <kgp> <gradle>: both APKs, and what each variant's runtime
# classpath resolves.
run_android() {
    local stack=$1 agp=$2 kgp=$3 name="$1-android" start=$SECONDS problems=""
    local log="$logs/$name.log" debug release stdlib r8 lint
    # --rerun-tasks: R8 and lint print their warnings only when they run.
    if ! build_fixture "$stack" android --rerun-tasks -Pagp="$agp" -Pkgp="$kgp" \
        :app:assembleDebug :app:assembleRelease :app:compatReport > "$log" 2>&1; then
        problems="; the build failed"
    fi
    debug=$(sed -n 's/^compat-report debug hopi=\([^ ]*\) .*/\1/p' "$log")
    release=$(sed -n 's/^compat-report release hopi=\([^ ]*\) .*/\1/p' "$log")
    stdlib=$(sed -n 's/^compat-report release .* kotlin-stdlib=\(.*\)$/\1/p' "$log")
    r8=$(grep -c 'R8: An error occurred when parsing kotlin metadata' "$log")
    # Lint's own Kotlin compiler reports a library's metadata it cannot read as an e: line, and passes.
    lint=$(awk '/^> Task /{task=$3} /^e: .*incompatible version of Kotlin/ && task ~ /[Ll]int/{n++} END{print n+0}' "$log")
    if [ -z "$problems" ]; then
        case ",$debug," in *,hopi-core,*) ;; *) problems="$problems; debug's runtime classpath lacks hopi-core ($debug)" ;; esac
        case ",$debug," in *,hopi-network,*) ;; *) problems="$problems; debug's runtime classpath lacks hopi-network ($debug)" ;; esac
        [ "$release" = "hopi-api,hopi-ktor" ] ||
            problems="$problems; release's runtime classpath holds $release, not only hopi-api and hopi-ktor"
        if [ "$stack" = floor ]; then
            case "$stdlib" in 2.2.*) ;; *) problems="$problems; release's kotlin-stdlib is $stdlib, not 2.2.x" ;; esac
        fi
    fi
    result "$name" "$stack android: Gradle $4, AGP $agp, Kotlin $kgp" "$problems" \
        "assembleDebug assembleRelease; debug: $debug; release: $release, kotlin-stdlib $stdlib; Kotlin-metadata warnings: R8 $r8, lint $lint" "$start"
}

# run_jvm <floor|current> <kgp> <gradle>: the app runs, and Hopi's rule answers its request.
run_jvm() {
    local stack=$1 kgp=$2 name="$1-jvm" start=$SECONDS problems=""
    local log="$logs/$name.log"
    if ! build_fixture "$stack" jvm -Pkgp="$kgp" run > "$log" 2>&1; then
        problems="; the build or the app failed"
    elif ! grep -q '^compat-jvm: ' "$log"; then
        problems="; the app printed no compat-jvm line"
    fi
    result "$name" "$stack jvm: Gradle $3, Kotlin $kgp" "$problems" "run: $(sed -n 's/^compat-jvm: //p' "$log")" "$start"
}

# run_kmp <floor|current> <kgp> <ktor> <Debug|Release> <gradle>: the iOS simulator framework links.
run_kmp() {
    local stack=$1 kgp=$2 ktor=$3 build=$4 name="$1-kmp-$4" start=$SECONDS problems="" enabled=""
    # Kotlin/Native links iOS frameworks on a Mac only; another host skips these runs.
    if [ "$(uname -s)" != Darwin ]; then
        echo "SKIP $name: links an iOS framework, which needs a Mac"
        return
    fi
    local log="$logs/$name.log" task=":shared:link${4}FrameworkIosSimulatorArm64"
    # A Debug build gets Hopi's runtimes; a Release link, none, and the iOS release guard.
    [ "$build" = Debug ] && enabled="-Phopi.enabled=true"
    # --rerun: the link runs even when up to date, so its e: lines are this run's (the link task
    # only: --rerun-tasks would also rerun the Kotlin/Native distribution's provisioning).
    if ! build_fixture "$stack" kmp -Pkgp="$kgp" -Pktor="$ktor" $enabled "$task" --rerun > "$log" 2>&1; then
        problems="; the build failed"
    elif grep -q '^e: ' "$log"; then
        problems="; the build printed an e: line"
    fi
    result "$name" "$stack kmp: Gradle $5, Kotlin $kgp, Ktor $ktor" "$problems" "${task#:shared:}${enabled:+ $enabled}" "$start"
}

# run_old_kotlin: the floor's Gradle with a Kotlin Gradle plugin below io.github.felipekoga.hopi's floor for a KMP
# module; the plugin must refuse it before Kotlin/Native downloads anything (its data directory goes
# to build/compat-home/konan, and ~/.konan must not change).
run_old_kotlin() {
    local name=floor-kmp-old-kotlin start=$SECONDS problems="" touched
    local log="$logs/$name.log" marker="$logs/.$name.started"
    : > "$marker"
    if build_fixture floor kmp -Pkgp="$old_kgp" -Pkonan.data.dir="$floor_home/konan" help > "$log" 2>&1; then
        problems="; the build configured"
    elif ! grep -q 'io.github.felipekoga.hopi needs the Kotlin Gradle plugin 2.2.0 or newer' "$log"; then
        problems="; the build failed without io.github.felipekoga.hopi's message"
    fi
    touched=$(find "$HOME/.konan" -newer "$marker" 2>/dev/null | head -1)
    [ -z "$touched" ] || problems="$problems; ~/.konan changed ($touched)"
    rm -f "$marker"
    result "$name" "floor kmp: Gradle $floor_gradle, Kotlin $old_kgp" "$problems" \
        "help: refused with io.github.felipekoga.hopi's message; ~/.konan unchanged" "$start"
}

if [ "$only" != current ]; then
    run_android floor "$floor_agp" "$floor_kgp" "$floor_gradle"
    run_jvm floor "$floor_kgp" "$floor_gradle"
    run_kmp floor "$floor_kgp_ios" "$floor_ktor" Debug "$floor_gradle"
    run_kmp floor "$floor_kgp_ios" "$floor_ktor" Release "$floor_gradle"
    run_old_kotlin
fi
if [ "$only" != floor ]; then
    run_android current "$current_agp" "$current_kgp" "$current_gradle"
    run_jvm current "$current_kgp" "$current_gradle"
    run_kmp current "$current_kgp" "$current_ktor" Debug "$current_gradle"
    run_kmp current "$current_kgp" "$current_ktor" Release "$current_gradle"
fi

echo "$passed passed, $failed failed (logs: build/compat)"
[ "$failed" -eq 0 ]
