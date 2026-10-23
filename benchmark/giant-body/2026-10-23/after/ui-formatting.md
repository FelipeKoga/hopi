### Detail body formatting off the main thread (median / p95 ms; allocation per call as a multiple of the captured size)

| shape | captured | splitText(raw) | splitText(pretty) | pieces | longest piece | DetailFormatter body view | alloc | view chars | view retained heap (MB) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| minified records | 256 KB | 0.05 / 0.07 | 0.31 / 0.34 | 348 | 1273 | 1.15 / 1.27 | 8.12x | 429701 | 0.4 |
| minified records | 1 MB | 0.10 / 0.52 | 0.67 / 0.72 | 1378 | 1273 | 3.96 / 5.55 | 8.10x | 1712566 | 1.7 |
| minified records | 5 MB | 0.60 / 0.75 | 3.41 / 3.83 | 6756 | 1291 | 20.12 / 24.99 | 8.08x | 8498581 | 8.5 |
| minified records | 16 MB | 5.11 / 5.53 | 13.65 / 14.65 | 21431 | 1309 | 68.71 / 71.03 | 8.07x | 27104597 | 26.9 |
| minified records | 16 MB of 20 MB | 3.03 / 10.88 | 2.65 / 3.00 | 4096 | 4096 | 56.75 / 69.03 | 5.76x | 16777216 | 16.3 |
| pretty records | 256 KB | 0.09 / 0.10 | 0.12 / 0.13 | 242 | 1273 | 0.76 / 0.80 | 4.58x | 296945 | 0.3 |
| pretty records | 1 MB | 0.38 / 0.44 | 0.46 / 0.52 | 956 | 1273 | 3.01 / 3.61 | 4.57x | 1186594 | 1.2 |
| pretty records | 5 MB | 1.76 / 2.13 | 2.32 / 3.02 | 4718 | 1291 | 15.20 / 18.93 | 4.57x | 5924489 | 6.0 |
| pretty records | 16 MB | 6.22 / 6.52 | 8.64 / 9.42 | 15031 | 1291 | 52.26 / 53.80 | 4.57x | 18948513 | 18.9 |
| pretty records | 16 MB of 20 MB | 6.70 / 7.72 | 7.11 / 7.13 | 10932 | 1539 | 46.33 / 61.39 | 3.30x | 16766285 | 16.6 |
| deeply nested | 256 KB | 0.02 / 0.02 | 0.02 / 0.02 | 64 | 4096 | 0.33 / 0.35 | 3.27x | 262144 | 0.3 |
| deeply nested | 1 MB | 0.10 / 0.46 | 0.09 / 0.14 | 256 | 4096 | 0.66 / 1.16 | 3.26x | 1048576 | 1.0 |
| deeply nested | 5 MB | 0.44 / 1.49 | 0.44 / 1.06 | 1280 | 4096 | 3.21 / 3.48 | 3.26x | 5242880 | 5.1 |
| deeply nested | 16 MB | 1.63 / 1.69 | 1.52 / 1.60 | 4096 | 4096 | 8.96 / 9.93 | 3.26x | 16777216 | 16.3 |
| deeply nested | 16 MB of 20 MB | 2.38 / 2.40 | 2.26 / 5.35 | 4096 | 4096 | 8.10 / 8.76 | 3.26x | 16777216 | 16.3 |
| one huge string | 256 KB | 0.02 / 0.03 | 0.02 / 0.02 | 66 | 4096 | 0.26 / 0.30 | 4.27x | 262148 | 0.3 |
| one huge string | 1 MB | 0.09 / 0.10 | 0.09 / 0.10 | 258 | 4096 | 1.05 / 1.50 | 4.27x | 1048580 | 1.0 |
| one huge string | 5 MB | 0.70 / 1.00 | 0.50 / 1.08 | 1282 | 4096 | 5.23 / 6.02 | 4.27x | 5242884 | 5.1 |
| one huge string | 16 MB | 2.37 / 3.31 | 1.61 / 2.47 | 4098 | 4096 | 17.48 / 19.79 | 4.27x | 16777220 | 16.3 |
| one huge string | 16 MB of 20 MB | 2.38 / 2.43 | 1.48 / 1.58 | 4096 | 4096 | 15.85 / 16.42 | 3.26x | 16777216 | 16.3 |
| unicode CJK/emoji | 256 KB | 0.26 / 0.28 | 0.11 / 0.35 | 94 | 1513 | 1.03 / 1.47 | 10.26x | 141171 | 0.3 |
| unicode CJK/emoji | 1 MB | 0.13 / 0.14 | 0.41 / 0.46 | 375 | 1513 | 2.52 / 3.12 | 10.26x | 564625 | 1.1 |
| unicode CJK/emoji | 5 MB | 0.71 / 1.08 | 1.51 / 2.34 | 1871 | 1513 | 10.46 / 11.34 | 10.25x | 2823065 | 5.5 |
| unicode CJK/emoji | 16 MB | 2.46 / 2.49 | 5.25 / 5.49 | 5986 | 1513 | 32.37 / 32.40 | 10.25x | 9033800 | 17.5 |
| unicode CJK/emoji | 16 MB of 20 MB | 2.58 / 3.95 | 3.41 / 9.66 | 1740 | 4096 | 29.73 / 30.84 | 8.27x | 7124574 | 13.7 |
| number array | 256 KB | 0.02 / 0.02 | 0.26 / 0.28 | 563 | 671 | 1.17 / 2.84 | 7.73x | 369466 | 0.4 |
| number array | 1 MB | 0.15 / 0.18 | 1.13 / 1.16 | 2172 | 703 | 4.26 / 4.80 | 7.69x | 1463296 | 1.5 |
| number array | 5 MB | 0.54 / 1.19 | 5.22 / 6.95 | 10420 | 703 | 20.59 / 22.99 | 7.65x | 7233072 | 7.4 |
| number array | 16 MB | 1.69 / 4.24 | 16.02 / 17.82 | 32069 | 735 | 66.51 / 70.69 | 7.62x | 22902317 | 23.5 |
| number array | 16 MB of 20 MB | 2.44 / 3.32 | 4.19 / 8.31 | 4096 | 4096 | 53.07 / 64.31 | 5.76x | 16777216 | 16.3 |

- splitText(pretty) runs on prettyPrintJson's output, which is the raw text when that is invalid (truncated) or nested beyond 256.
- A case slower than 20 s per call runs once and is marked '(1 run)'.
- View retained heap: heap still reachable through the view after a full GC (pieces plus the per-piece flags), minus the heap before; the captured bytes are not counted.
