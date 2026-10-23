### Detail body formatting off the main thread (median / p95 ms; allocation per call as a multiple of the captured size)

| shape | captured | splitText(raw) | splitText(pretty) | pieces | longest piece | DetailFormatter body view | alloc | view chars | view retained heap (MB) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| minified records | 256 KB | 0.49 / 0.50 | 0.32 / 0.93 | 348 | 1273 | 1.74 / 2.26 | 10.12x | 429701 | 0.4 |
| minified records | 1 MB | 6.97 / 7.60 | 0.92 / 0.98 | 1378 | 1273 | 4.77 / 5.94 | 10.10x | 1712566 | 1.7 |
| minified records | 5 MB | 155.65 / 161.11 | 3.27 / 5.07 | 6756 | 1291 | 23.03 / 27.85 | 10.08x | 8498581 | 8.5 |
| minified records | 16 MB | 1612.05 / 1613.59 | 12.11 / 13.34 | 21431 | 1309 | 76.36 / 96.46 | 10.07x | 27104597 | 26.9 |
| minified records | 16 MB of 20 MB | 1629.97 / 1637.94 | 1618.13 / 1630.48 | 4096 | 4096 | 1690.19 / 1697.77 | 7.76x | 16777216 | 16.3 |
| pretty records | 256 KB | 0.11 / 0.15 | 0.14 / 0.18 | 242 | 1273 | 1.49 / 1.70 | 6.58x | 296945 | 0.3 |
| pretty records | 1 MB | 0.36 / 0.50 | 0.44 / 0.60 | 956 | 1273 | 4.04 / 4.64 | 6.57x | 1186594 | 1.2 |
| pretty records | 5 MB | 2.19 / 2.37 | 2.39 / 2.79 | 4718 | 1291 | 20.74 / 21.89 | 6.57x | 5924489 | 6.0 |
| pretty records | 16 MB | 7.92 / 8.14 | 7.35 / 8.48 | 15031 | 1291 | 67.53 / 80.43 | 10.57x | 18948513 | 18.9 |
| pretty records | 16 MB of 20 MB | 6.30 / 6.48 | 7.23 / 8.12 | 10932 | 1539 | 66.04 / 75.12 | 5.30x | 16766285 | 16.6 |
| deeply nested | 256 KB | 0.40 / 0.41 | 0.39 / 0.42 | 64 | 4096 | 0.88 / 0.97 | 5.27x | 262144 | 0.3 |
| deeply nested | 1 MB | 6.13 / 6.49 | 6.15 / 6.37 | 256 | 4096 | 7.34 / 7.95 | 5.26x | 1048576 | 1.0 |
| deeply nested | 5 MB | 154.68 / 158.08 | 153.66 / 157.37 | 1280 | 4096 | 160.91 / 162.93 | 5.26x | 5242880 | 5.1 |
| deeply nested | 16 MB | 1613.78 / 1624.55 | 1617.27 / 1624.68 | 4096 | 4096 | 1650.33 / 1652.83 | 5.26x | 16777216 | 16.3 |
| deeply nested | 16 MB of 20 MB | 1613.02 / 1615.08 | 1614.92 / 1627.47 | 4096 | 4096 | 1657.36 / 1660.37 | 5.26x | 16777216 | 16.3 |
| one huge string | 256 KB | 0.43 / 0.44 | 0.50 / 0.53 | 66 | 4096 | 0.93 / 1.38 | 6.27x | 262148 | 0.3 |
| one huge string | 1 MB | 7.05 / 7.63 | 6.88 / 7.09 | 258 | 4096 | 8.80 / 9.26 | 6.27x | 1048580 | 1.0 |
| one huge string | 5 MB | 152.43 / 173.24 | 149.84 / 155.76 | 1282 | 4096 | 158.18 / 160.90 | 6.27x | 5242884 | 5.1 |
| one huge string | 16 MB | 1581.82 / 1604.87 | 1575.04 / 1581.27 | 4098 | 4096 | 1594.95 / 1637.50 | 6.27x | 16777220 | 16.3 |
| one huge string | 16 MB of 20 MB | 1596.73 / 1602.86 | 1585.38 / 1591.66 | 4096 | 4096 | 1620.68 / 1639.83 | 5.26x | 16777216 | 16.3 |
| unicode CJK/emoji | 256 KB | 0.31 / 3.30 | 0.68 / 0.70 | 94 | 1513 | 1.12 / 1.60 | 10.26x | 141171 | 0.3 |
| unicode CJK/emoji | 1 MB | 2.44 / 4.50 | 0.27 / 0.30 | 375 | 1513 | 2.17 / 2.61 | 10.26x | 564625 | 1.1 |
| unicode CJK/emoji | 5 MB | 54.12 / 54.71 | 1.24 / 2.15 | 1871 | 1513 | 10.35 / 10.97 | 10.25x | 2823065 | 5.5 |
| unicode CJK/emoji | 16 MB | 551.15 / 558.92 | 4.33 / 4.43 | 5986 | 1513 | 32.17 / 33.00 | 10.25x | 9033800 | 17.5 |
| unicode CJK/emoji | 16 MB of 20 MB | 551.75 / 594.31 | 552.29 / 559.14 | 1740 | 4096 | 580.02 / 605.06 | 8.27x | 7124574 | 13.7 |
| number array | 256 KB | 0.41 / 0.43 | 0.25 / 0.29 | 563 | 671 | 1.31 / 3.26 | 9.73x | 369466 | 0.4 |
| number array | 1 MB | 6.24 / 6.72 | 0.93 / 1.00 | 2172 | 703 | 4.84 / 5.28 | 9.69x | 1463296 | 1.5 |
| number array | 5 MB | 157.56 / 159.34 | 4.51 / 5.65 | 10420 | 703 | 23.79 / 24.55 | 9.65x | 7233072 | 7.4 |
| number array | 16 MB | 1627.21 / 1649.20 | 15.87 / 17.64 | 32069 | 735 | 76.30 / 87.16 | 9.62x | 22902317 | 23.5 |
| number array | 16 MB of 20 MB | 1630.45 / 1632.97 | 1628.28 / 1630.98 | 4096 | 4096 | 1704.00 / 1721.00 | 7.76x | 16777216 | 16.3 |

- splitText(pretty) runs on prettyPrintJson's output, which is the raw text when that is invalid (truncated) or nested beyond 256.
- A case slower than 20 s per call runs once and is marked '(1 run)'.
- View retained heap: heap still reachable through the view after a full GC (pieces plus the per-piece flags), minus the heap before; the captured bytes are not counted.
