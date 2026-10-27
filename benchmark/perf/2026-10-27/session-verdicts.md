# Pre-registered verdicts (written 2026-10-27, before any data of this session was read)

Session: AVD `Hopi_API_35` (4 vCPUs, 2 GB, `-no-window -no-snapshot -no-audio`), 150 s settle
after boot, dev server `--nodelay`, `adb reverse tcp:8765 tcp:8765`. APKs: after = HEAD 1c81938,
before = 9356117 (the tree before the fixes), noop = HEAD with `-Psample.hopiCapture=false`.

## 1. Request-thread cost of the fixes (store queued-bytes budget, recorders' terminal guard)

A/B `ab.py`, arms `noop before after noop2`, 4 rounds, spec
`kinds=okhttp,urlconnection;path=/json;n=1000;warmup=1000`, wait 14 s.

- Noise band N = [min, max] over rounds of `noop2 - noop`, 0 included, per stack and metric.
- **Q1 (headline)**: `after - before` calling-thread `cpuUs` inside N in a majority of the 4
  rounds (3 of 4), for each stack. PASS: the fixes add no measurable calling-thread CPU.
- **Q2**: `after - before` `medianUs` inside the `medianUs` band N in a majority of rounds.
- For the record (not a verdict on this change): `after - noop` and `before - noop` calling-thread
  CPU against the 30 µs line (10-25: OkHttp +21.0 median of rounds PASS, `HttpURLConnection` +28.5,
  3 of 4 rounds); what matters is whether this commit moved it.

## 2. Heartbeat idle (`benchmark/perf/idle.py`, 60 s, settle 10 s)

Arms `off=none,hangs=hangs,hb1=hangs:heartbeat=1,hb2=hangs:heartbeat=1,hb3=hangs:heartbeat=1`.

- **H1**: in every heartbeat arm, `hopi-perf-hang` and the main thread each wake 8 per second at
  250 ms: 480 in 60 s, PASS within 430..530 for each thread (10-25: 239 and 245, 4 each).
- **H2**: the printer arm `hangs` and `off`: 0 Hopi wakes, as before.
- Record the watchdog's CPU per minute (10-25: 90 ms at 4 beats per second).

## 3. Heartbeat hang readings (`benchmark/perf/verify.py <out> --steps all,heartbeat`, twice)

The design's bound after the fix: a hang reads at most 125 ms short (`threshold / 2`), and only
one shorter than 1.5 thresholds (375 ms) can be missed.

- **V1**: 1000 ms hang reads in [875, 1021]; 3000 ms hang in [2875, 3021]; 600 ms burn in
  [475, 621] (the unit's own measured duration minus 125, plus 20).
- **V2**: click burn within +-20 (+30 slack as verify.py allows): the input bracket, unchanged.
- **V3**: the 200 ms hang never reported.
- Info only: whether the 300 ms hang (1.2 thresholds) is reported.
- verify.py's own +-20 duration checks fail by design on heartbeat runs; its exit code is not the
  verdict. Every non-hang check (jank, dialog, CPU, memory) is compared with 10-25's
  `android-verify-heartbeat.json` (22 of 26) for regressions.

## 4. Printer regression guard (`verify.py <out> --steps all`, once)

- **P1**: hang checks as 10-25's `android-verify-printer.json` (every hang within +-20).

## 5. Desktop rules panel against the emulator

`./gradlew :hopi-desktop:test --tests '*DeviceRulesPanelUiTest*' --tests '*DeviceViewerUiTest*'
-Phopi.desktop.device=android`, after.apk installed, plain dev server up.

- **D1**: every check passes (10-25: 41/41 for the rules panel); one retry if the environment
  (JVM memory, adb) fails, reported as such.

## Addendum (written 2026-10-27 after reading section 1's results, before the follow-up ran)

Section 1 gave Q1 PASS on both stacks, Q2 PASS on OkHttp and Q2 FAIL on `HttpURLConnection`
(`after - before` +84, -70, -1, -39 against a noop band of -19..+0), and a process CPU per request
lower for `after` than for `before` in every round. In section 1 `after` always ran right after
`before`. Follow-up, order reversed: `AB_ARMS="noop after before noop2"`, 4 rounds, same spec and
wait (`ab-gap0-rev/`). It is reported beside section 1, never in place of it:

- **R1**: Q1 and Q2 as in section 1, on the reversed run alone.
- **R2**: Q1 and Q2 over the 8 rounds of both orders (`ab-gap0-pooled/`, the reversed rounds
  numbered 4 to 7), the noise band from the 8 rounds of `noop2 - noop`.
- The process CPU difference is called an order effect if it reverses sign or vanishes in the
  reversed run, a difference of the builds if `after` stays lower in a majority of its rounds.

## Results (written after the runs)

Raw data: `benchmark/android-capture/2026-10-27/` (`ab-forward.ndjson`, `ab-reversed.ndjson`, the
three `verdict-*.md` tables) and this directory (`android-idle-heartbeat.ndjson`,
`android-verify-heartbeat-1.json`, `-2.json`, `android-verify-printer.json`). The desktop checks
stayed in `build/evidence/fixes-1027/android/desktop/`, as the earlier desktop runs did.

1. **A/B, first order** (`verdict-forward.md`): Q1 PASS on both stacks (after - before
   calling-thread CPU +4, -22, -2, +37 µs on OkHttp and +1, -6, +1, +12 on `HttpURLConnection`,
   median +1.0 each, 3 of 4 rounds inside N); Q2 PASS on OkHttp (3 of 4), FAIL on
   `HttpURLConnection` (+84, -70, -1, -39 against -19..+0, 1 of 4). For the record, after - noop:
   OkHttp +22.0 µs median (2 of 4 rounds under 30), `HttpURLConnection` +21.5 (3 of 4).
   **Addendum, swapped order** (`verdict-reversed.md`, guest load 0.2 to 0.4, quieter than the first
   run): R1 FAIL on every line (OkHttp CPU +11, -3, +9, +2 against -15..+0; `HttpURLConnection`
   CPU -1, -3, +3, -2 against -2..+1; wall -16, -10, +1, -6 against +0..+38 and -13, -17, +0, +19
   against -3..+18). **Pooled 8 rounds** (`verdict-pooled.md`): R2 Q1 PASS on both (7 of 8;
   medians +3.0 and +0.0 µs), Q2 PASS on OkHttp (7 of 8, median -5.0), FAIL on `HttpURLConnection`
   (4 of 8, median -7.0, scattered both ways). The pooled OkHttp CPU band's +39 edge comes from
   the session's first block (noop, guest load 8.4, 267 µs), so on OkHttp a cost of up to about
   10 µs is not ruled out (mean of the 8 rounds +4.5). Process CPU per request, after - before:
   lower in all 4 rounds of the first order and in 2 of 4 of the second (-97.5, -87.5, +107.5,
   +12.5 µs): neither clause of the addendum's rule fires (no reversal, the median still -37.5;
   not vanished, 3 of 4 outside that run's -57.5..+15 band; not a majority), so it is
   inconclusive. By schedstat the difference is in the JIT pool and the main thread (launch work
   after each install), Hopi's threads within ±22 ms a block.
2. **Idle**: H1 PASS (the watchdog 470, 470 and 471 wakes and the main thread 475, 477 and 476 in
   60 s; watchdog CPU 140, 110 and 160 ms); H2 PASS (nothing started and the printer: 0 wakes on
   Hopi's threads, 0 on the main thread).
3. **Heartbeat lab, 2 runs**: V1 PASS (1 s hang 917 and 938 ms against 1003 and 1001 in the unit;
   3 s hang 2950 and 2949 against 3002 and 3004; 600 ms burn 556 and 561 against 601), V2 PASS
   (605 and 604 ms), V3 PASS; the 300 ms hang was missed in both runs (info). 22 and 21 of 26 by
   `verify.py`'s own ±20 ms bounds; no other check regressed against 2026-10-25 (the 20 ms jank
   step read 2 janky frames, then 4, as on 2026-10-25).
4. **Printer**: P1 PASS, 28 of 28 (hangs 311, 1004, 3001 and 602 ms; the click burn 603 ms).
5. **Desktop: D1 FAIL as registered** (both classes, 53 of 54 counted checks).
   `DeviceRulesPanelUiTest` 36 of 36 PASS, and its info check "the newest row is in view after a
   burst on an untouched list" read yes (2026-10-25: no). `DeviceViewerUiTest` 17 of 18: "5 MB body
   opens" FAIL (captured 262144 of 5242766), which fails the same way on the before build
   (`giant-list.txt`: `source` okhttp, `resHeld` 262144 on both), so not a regression of the
   fixes; "5 MB body renders in the Response tab" passed on that truncated body (it checks no
   size), and "request overhead, 500 POST /items" passed with no figure (no result line in
   `ui.py dump` on either build). Deviations from the registered command: the two classes ran in
   separate Gradle invocations (the rules test's KDoc asks to run it alone), both with
   `-Phopi.desktop.evidence`, and the viewer run's dev server logged into the evidence directory,
   not to `build/evidence/desktop/devserver.log`, which the viewer test reads for its POST count;
   a 2026-10-23 log sat there, so each of its 6 blocks waited out 3 minutes. All of it is in
   ROADMAP.md, "Desktop viewer".
