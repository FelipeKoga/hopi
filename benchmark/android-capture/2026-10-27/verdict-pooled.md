| stack | metric | noop mean | before - noop | after - noop | after - before | noise noop2 - noop |
|---|---|---|---|---|---|---|
| okhttp | cpuUs | 278 | +33, +28, +9, +16, +11, +30, +17, +16 | +37, +6, +7, +53, +22, +27, +26, +18 | +4, -22, -2, +37, +11, -3, +9, +2 | +39, -11, -9, -4, -15, -3, -2, +0 |
| okhttp | medianUs | 1086 | +100, -23, +7, +8, +40, +34, +48, +36 | +114, -77, +3, +32, +24, +24, +49, +30 | +14, -54, -4, +24, -16, -10, +1, -6 | +102, -33, -24, -7, +13, +1, +38, +16 |
| okhttp | p90Us | 1297 | -108, +38, +20, -22, +60, +105, +59, +86 | -44, -57, +36, +143, +37, +77, +85, +80 | +64, -95, +16, +165, -23, -28, +26, -6 | +45, -16, +16, -21, -10, -6, +25, +36 |
| urlconnection | cpuUs | 117 | +15, +27, +21, +24, +26, +26, +19, +27 | +16, +21, +22, +36, +25, +23, +22, +25 | +1, -6, +1, +12, -1, -3, +3, -2 | -15, +0, -3, +3, +1, +0, -2, +0 |
| urlconnection | medianUs | 955 | -68, +15, +10, +15, +20, +33, +15, +0 | +16, -55, +9, -24, +7, +16, +15, +19 | +84, -70, -1, -39, -13, -17, +0, +19 | -19, -14, -5, -8, +10, +11, +18, -3 |
| urlconnection | p90Us | 1167 | -496, +17, +20, +30, +60, +44, +21, -5 | -409, -52, +37, +28, +17, +59, +17, +23 | +87, -69, +17, -2, -43, +15, -4, +28 | -480, -20, +21, +3, +27, +20, +12, -10 |

| verdict | stack | after - before per round | median | band N | inside | result |
|---|---|---|---|---|---|---|
| Q1 calling-thread cpuUs | okhttp | +4, -22, -2, +37, +11, -3, +9, +2 | +3.0 | -15..+39 | 7/8 | PASS |
| Q1 calling-thread cpuUs | urlconnection | +1, -6, +1, +12, -1, -3, +3, -2 | +0.0 | -15..+3 | 7/8 | PASS |
| Q2 medianUs | okhttp | +14, -54, -4, +24, -16, -10, +1, -6 | -5.0 | -33..+102 | 7/8 | PASS |
| Q2 medianUs | urlconnection | +84, -70, -1, -39, -13, -17, +0, +19 | -7.0 | -19..+18 | 4/8 | FAIL |

| record | stack | per round | median of rounds | 30 us line |
|---|---|---|---|---|
| before - noop calling-thread cpuUs | okhttp | +33, +28, +9, +16, +11, +30, +17, +16 | +16.5 | 7/8 rounds under |
| before - noop calling-thread cpuUs | urlconnection | +15, +27, +21, +24, +26, +26, +19, +27 | +25.0 | 8/8 rounds under |
| after - noop calling-thread cpuUs | okhttp | +37, +6, +7, +53, +22, +27, +26, +18 | +24.0 | 6/8 rounds under |
| after - noop calling-thread cpuUs | urlconnection | +16, +21, +22, +36, +25, +23, +22, +25 | +22.5 | 7/8 rounds under |

| round | process CPU us/request before - noop | after - noop | noise noop2 - noop | hopi-store slices/request before | after | guest load per arm |
|---|---|---|---|---|---|---|
| 0 | +270 | +95 | -12 | n/a | n/a | noop=8.4 before=2.62 after=2.08 noop2=1.65 |
| 1 | +260 | +212 | +18 | n/a | n/a | noop=2.56 before=2.02 after=1.59 noop2=1.76 |
| 2 | +185 | +85 | -22 | n/a | n/a | noop=1.26 before=0.9 after=0.79 noop2=0.79 |
| 3 | +128 | +105 | +35 | n/a | n/a | noop=0.63 before=0.45 after=0.38 noop2=0.82 |
| 4 | +202 | +105 | -12 | n/a | n/a | noop=0.31 before=0.22 after=0.22 noop2=0.28 |
| 5 | +135 | +48 | -58 | n/a | n/a | noop=0.2 before=0.3 after=0.33 noop2=0.37 |
| 6 | +102 | +210 | -45 | n/a | n/a | noop=0.32 before=0.27 after=0.29 noop2=0.38 |
| 7 | +102 | +115 | +15 | n/a | n/a | noop=0.27 before=0.4 after=0.38 noop2=0.28 |
