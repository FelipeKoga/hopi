### URLSession capture A/B with an 8 MiB capture cap (E1 R8): iPhone 17 simulator, iOS 27.0

Run 2026-10-29, 11:04 to 11:07 local time, for stage 9 of the runtime-split plan
(`docs/design/api-runtime-split-plan.md`; E1 R8 of `docs/design/runtime-split-evaluation/E1.md`):
giant bodies through URLSession capture past 1 MiB with a raised cap, which no committed run had
measured. Tree: 9a `472fcd6` with 9b's edits (uncommitted). Xcode 27.1 (iphonesimulator SDK 27.1),
simulator `iPhone 17` (iOS 27.0), Apple M2 host with 8 GB; host load (1-minute average, at each
install) 2.3 to 2.8.

Builds, both Debug from that tree: `hopi` (`xcodebuild -project sample/iosApp/iosApp.xcodeproj
-scheme iosApp -configuration Debug build`: Hopi's runtimes, `Hopi.shared.start`) and `noop` (the
same with `HOPI_ENABLED=false`: no runtime, only `hopi-api` and the Ktor plugin's noop twin). Dev
server `python3 sample/devserver/devserver.py` (without `--nodelay`).

```
benchmark/urlsession/e2e/ab.sh <out> <apps> 4 -maxBodyBytes 8388608 -maxTotalBytes 33554432
python3 benchmark/urlsession/e2e/abtable.py <out>
```

Both launches of every round take the two arguments: in the `hopi` build `Hopi.shared.start` keeps
up to 8 MiB of each body (default 256 KiB) in a 32 MiB store (default 16 MiB), and the sample's Ktor
client gets the same caps (every Ktor run's header line reads
`"capBytes":8388608,"storeBytes":33554432`; on iOS URLSession capture records the Darwin engine's
requests). So the 1 and 5 MB bodies are kept whole and the 20 MB body to 8 MiB (a separate launch
the same day with the CLI, `-captureBench 'n=1;mb=1,5,20;runs=1'` and the same arguments: `hopi get`
shows 1048554 and 5242766 bytes kept whole and 8388608 of 20971479 kept, truncated). Per round and
build: `-captureBench 'n=200;mb=1,5,20;runs=5'` (URLSession, an ephemeral session: 200 sequential
small requests after 20 warm-ups, then 5 runs of each size, request plus full read, after one
warm-up each) and `-giant 'mb=1,5,20;runs=5;delay=2'` (Ktor Darwin: fetch plus JSON parse). The
table gives each build's round medians, the median of those per build, Hopi minus noop, and the
noop rounds' spread as the noise band. The raw app logs, `load.txt` and abtable's `summary.json`
are in `ab-cap8mib-iphone17-ios27.0/`.

| metric | noop rounds | Hopi rounds | noop median | Hopi median | Hopi - noop | noop-vs-noop band |
|---|---|---|---|---|---|---|
| small request median (ms) | 0.59, 0.597, 0.608, 0.579 | 0.72, 0.589, 0.602, 0.629 | 0.5935 | 0.6155 | +0.022 | 0.029 (consecutive 0.029) |
| small request p90 (ms) | 1.365, 1.014, 1.001, 0.811 | 1.206, 0.908, 1.018, 1.138 | 1.0075 | 1.078 | +0.0705 | 0.554 (consecutive 0.351) |
| process CPU per small request (us) | 1014, 1088.7, 1087.7, 831 | 1458.9, 1083.6, 1183.8, 1240.7 | 1050.85 | 1212.25 | +161 | 257.7 (consecutive 256.7) |
| URLSession 1 MB request+read (ms) | 6.19, 6.281, 6.493, 6.189 | 4.603, 6.303, 6.871, 3.789 | 6.2355 | 5.453 | -0.783 | 0.304 (consecutive 0.304) |
| URLSession 1 MB process CPU per run (ms) | 2.2, 2.59, 2.68, 1.65 | 2.51, 3.21, 5, 6.04 | 2.395 | 4.105 | +1.71 | 1.03 (consecutive 1.03) |
| URLSession 5 MB request+read (ms) | 13.072, 14.892, 14.627, 14.497 | 14.332, 14.059, 17.363, 22.09 | 14.562 | 15.8475 | +1.29 | 1.82 (consecutive 1.82) |
| URLSession 5 MB process CPU per run (ms) | 8.05, 11.28, 10.1, 8.24 | 16.97, 15.78, 19.55, 18.69 | 9.17 | 17.83 | +8.66 | 3.23 (consecutive 3.23) |
| URLSession 20 MB request+read (ms) | 49.683, 52.147, 53.868, 52.603 | 53.389, 52.817, 52.83, 51.622 | 52.375 | 52.8235 | +0.448 | 4.185 (consecutive 2.464) |
| URLSession 20 MB process CPU per run (ms) | 35.07, 17.77, 18.33, 28.73 | 21.9, 24.47, 19.61, 21.82 | 23.53 | 21.86 | -1.67 | 17.3 (consecutive 17.3) |
| Ktor 1 MB fetch+parse (ms) | 69, 78, 68, 78 | 77, 77, 77, 73 | 73.5 | 77 | +3.5 | 10 (consecutive 10) |
| Ktor 5 MB fetch+parse (ms) | 332, 324, 324, 324 | 326, 324, 326, 328 | 324 | 326 | +2 | 8 (consecutive 8) |
| Ktor 20 MB fetch+parse (ms) | 1226, 1230, 1219, 1222 | 1226, 1238, 1224, 1240 | 1224 | 1232 | +8 | 11 (consecutive 11) |
