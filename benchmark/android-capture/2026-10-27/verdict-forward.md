| stack | metric | noop mean | before - noop | after - noop | after - before | noise noop2 - noop |
|---|---|---|---|---|---|---|
| okhttp | cpuUs | 280 | +33, +28, +9, +16 | +37, +6, +7, +53 | +4, -22, -2, +37 | +39, -11, -9, -4 |
| okhttp | medianUs | 1100 | +100, -23, +7, +8 | +114, -77, +3, +32 | +14, -54, -4, +24 | +102, -33, -24, -7 |
| okhttp | p90Us | 1339 | -108, +38, +20, -22 | -44, -57, +36, +143 | +64, -95, +16, +165 | +45, -16, +16, -21 |
| urlconnection | cpuUs | 118 | +15, +27, +21, +24 | +16, +21, +22, +36 | +1, -6, +1, +12 | -15, +0, -3, +3 |
| urlconnection | medianUs | 979 | -68, +15, +10, +15 | +16, -55, +9, -24 | +84, -70, -1, -39 | -19, -14, -5, -8 |
| urlconnection | p90Us | 1249 | -496, +17, +20, +30 | -409, -52, +37, +28 | +87, -69, +17, -2 | -480, -20, +21, +3 |

| verdict | stack | after - before per round | median | band N | inside | result |
|---|---|---|---|---|---|---|
| Q1 calling-thread cpuUs | okhttp | +4, -22, -2, +37 | +1.0 | -11..+39 | 3/4 | PASS |
| Q1 calling-thread cpuUs | urlconnection | +1, -6, +1, +12 | +1.0 | -15..+3 | 3/4 | PASS |
| Q2 medianUs | okhttp | +14, -54, -4, +24 | +5.0 | -33..+102 | 3/4 | PASS |
| Q2 medianUs | urlconnection | +84, -70, -1, -39 | -20.0 | -19..+0 | 1/4 | FAIL |

| record | stack | per round | median of rounds | 30 us line |
|---|---|---|---|---|
| before - noop calling-thread cpuUs | okhttp | +33, +28, +9, +16 | +22.0 | 3/4 rounds under |
| before - noop calling-thread cpuUs | urlconnection | +15, +27, +21, +24 | +22.5 | 4/4 rounds under |
| after - noop calling-thread cpuUs | okhttp | +37, +6, +7, +53 | +22.0 | 2/4 rounds under |
| after - noop calling-thread cpuUs | urlconnection | +16, +21, +22, +36 | +21.5 | 3/4 rounds under |

| round | process CPU us/request before - noop | after - noop | noise noop2 - noop | hopi-store slices/request before | after | guest load per arm |
|---|---|---|---|---|---|---|
| 0 | +270 | +95 | -12 | 0.06 | 0.08 | noop=8.4 before=2.62 after=2.08 noop2=1.65 |
| 1 | +260 | +212 | +18 | 0.09 | 0.08 | noop=2.56 before=2.02 after=1.59 noop2=1.76 |
| 2 | +185 | +85 | -22 | 0.06 | 0.08 | noop=1.26 before=0.9 after=0.79 noop2=0.79 |
| 3 | +128 | +105 | +35 | 0.06 | 0.06 | noop=0.63 before=0.45 after=0.38 noop2=0.82 |
