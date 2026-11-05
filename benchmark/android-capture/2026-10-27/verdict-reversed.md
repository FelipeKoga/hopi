| stack | metric | noop mean | before - noop | after - noop | after - before | noise noop2 - noop |
|---|---|---|---|---|---|---|
| okhttp | cpuUs | 276 | +11, +30, +17, +16 | +22, +27, +26, +18 | +11, -3, +9, +2 | -15, -3, -2, +0 |
| okhttp | medianUs | 1072 | +40, +34, +48, +36 | +24, +24, +49, +30 | -16, -10, +1, -6 | +13, +1, +38, +16 |
| okhttp | p90Us | 1256 | +60, +105, +59, +86 | +37, +77, +85, +80 | -23, -28, +26, -6 | -10, -6, +25, +36 |
| urlconnection | cpuUs | 115 | +26, +26, +19, +27 | +25, +23, +22, +25 | -1, -3, +3, -2 | +1, +0, -2, +0 |
| urlconnection | medianUs | 932 | +20, +33, +15, +0 | +7, +16, +15, +19 | -13, -17, +0, +19 | +10, +11, +18, -3 |
| urlconnection | p90Us | 1086 | +60, +44, +21, -5 | +17, +59, +17, +23 | -43, +15, -4, +28 | +27, +20, +12, -10 |

| verdict | stack | after - before per round | median | band N | inside | result |
|---|---|---|---|---|---|---|
| Q1 calling-thread cpuUs | okhttp | +11, -3, +9, +2 | +5.5 | -15..+0 | 1/4 | FAIL |
| Q1 calling-thread cpuUs | urlconnection | -1, -3, +3, -2 | -1.5 | -2..+1 | 2/4 | FAIL |
| Q2 medianUs | okhttp | -16, -10, +1, -6 | -8.0 | +0..+38 | 1/4 | FAIL |
| Q2 medianUs | urlconnection | -13, -17, +0, +19 | -6.5 | -3..+18 | 1/4 | FAIL |

| record | stack | per round | median of rounds | 30 us line |
|---|---|---|---|---|
| before - noop calling-thread cpuUs | okhttp | +11, +30, +17, +16 | +16.5 | 4/4 rounds under |
| before - noop calling-thread cpuUs | urlconnection | +26, +26, +19, +27 | +26.0 | 4/4 rounds under |
| after - noop calling-thread cpuUs | okhttp | +22, +27, +26, +18 | +24.0 | 4/4 rounds under |
| after - noop calling-thread cpuUs | urlconnection | +25, +23, +22, +25 | +24.0 | 4/4 rounds under |

| round | process CPU us/request before - noop | after - noop | noise noop2 - noop | hopi-store slices/request before | after | guest load per arm |
|---|---|---|---|---|---|---|
| 0 | +202 | +105 | -12 | 0.07 | 0.09 | noop=0.31 before=0.22 after=0.22 noop2=0.28 |
| 1 | +135 | +48 | -58 | 0.09 | 0.08 | noop=0.2 before=0.3 after=0.33 noop2=0.37 |
| 2 | +102 | +210 | -45 | 0.07 | 0.06 | noop=0.32 before=0.27 after=0.29 noop2=0.38 |
| 3 | +102 | +115 | +15 | 0.06 | 0.06 | noop=0.27 before=0.4 after=0.38 noop2=0.28 |
