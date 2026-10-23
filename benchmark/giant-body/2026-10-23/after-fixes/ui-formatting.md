### Detail body formatting off the main thread (median / p95 ms; allocation per call as a multiple of the captured size)

| shape | captured | splitText(raw) | splitText(pretty) | pieces | longest piece | DetailFormatter body view | alloc | view chars | view retained heap (MB) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| minified records | 256 KB | 0.03 / 0.05 | 0.21 / 0.22 | 348 | 1273 | 1.19 / 6.68 | 4.55x | 429701 | 0.4 |
| minified records | 1 MB | 0.10 / 0.80 | 0.73 / 0.84 | 1378 | 1273 | 3.80 / 3.99 | 4.43x | 1712566 | 1.7 |
| minified records | 5 MB | 0.70 / 0.81 | 3.29 / 4.00 | 6756 | 1291 | 20.45 / 22.22 | 4.40x | 8498581 | 8.4 |
| minified records | 16 MB | 3.39 / 10.15 | 13.68 / 14.59 | 21431 | 1309 | 60.30 / 75.82 | 4.39x | 27104597 | 26.9 |
| minified records | 16 MB of 20 MB | 3.31 / 3.50 | 5.67 / 7.47 | 4096 | 4096 | 13.15 / 13.45 | 2.01x | 16777216 | 16.3 |
| pretty records | 256 KB | 0.10 / 0.20 | 0.12 / 0.13 | 242 | 1273 | 1.12 / 1.29 | 3.51x | 296945 | 0.3 |
| pretty records | 1 MB | 0.34 / 1.03 | 0.45 / 0.48 | 956 | 1273 | 2.77 / 3.02 | 3.39x | 1186594 | 1.2 |
| pretty records | 5 MB | 1.70 / 2.05 | 2.28 / 2.99 | 4718 | 1291 | 13.72 / 18.48 | 3.37x | 5924489 | 5.9 |
| pretty records | 16 MB | 5.47 / 6.36 | 8.39 / 8.88 | 15031 | 1291 | 43.85 / 54.30 | 3.37x | 18948513 | 18.8 |
| pretty records | 16 MB of 20 MB | 6.63 / 6.79 | 6.56 / 6.63 | 10932 | 1539 | 14.61 / 16.01 | 2.05x | 16766285 | 16.6 |
| deeply nested | 256 KB | 0.02 / 0.05 | 0.02 / 0.03 | 64 | 4096 | 0.32 / 0.35 | 3.25x | 262144 | 0.3 |
| deeply nested | 1 MB | 0.09 / 0.42 | 0.09 / 0.09 | 256 | 4096 | 0.64 / 1.03 | 2.32x | 1048576 | 1.0 |
| deeply nested | 5 MB | 0.58 / 1.26 | 0.44 / 1.10 | 1280 | 4096 | 2.64 / 3.33 | 2.07x | 5242880 | 5.1 |
| deeply nested | 16 MB | 2.34 / 2.49 | 1.51 / 1.54 | 4096 | 4096 | 7.90 / 8.91 | 2.03x | 16777216 | 16.3 |
| deeply nested | 16 MB of 20 MB | 2.39 / 2.46 | 2.44 / 5.02 | 4096 | 4096 | 8.27 / 8.74 | 2.03x | 16777216 | 16.3 |
| one huge string | 256 KB | 0.02 / 0.02 | 0.02 / 0.03 | 66 | 4096 | 0.27 / 0.31 | 3.17x | 262148 | 0.3 |
| one huge string | 1 MB | 0.09 / 0.10 | 0.09 / 0.10 | 258 | 4096 | 1.09 / 1.17 | 3.06x | 1048580 | 1.0 |
| one huge string | 5 MB | 0.68 / 1.41 | 0.44 / 1.00 | 1282 | 4096 | 5.35 / 5.96 | 3.03x | 5242884 | 5.1 |
| one huge string | 16 MB | 2.35 / 2.36 | 1.57 / 1.75 | 4098 | 4096 | 16.59 / 17.92 | 3.02x | 16777220 | 16.2 |
| one huge string | 16 MB of 20 MB | 2.41 / 2.46 | 1.53 / 2.32 | 4096 | 4096 | 7.86 / 8.49 | 2.01x | 16777216 | 16.3 |
| unicode CJK/emoji | 256 KB | 0.26 / 0.28 | 0.11 / 0.16 | 94 | 1513 | 0.71 / 6.24 | 7.02x | 141171 | 0.3 |
| unicode CJK/emoji | 1 MB | 0.11 / 0.12 | 0.29 / 0.30 | 375 | 1513 | 2.14 / 2.68 | 6.70x | 564625 | 1.1 |
| unicode CJK/emoji | 5 MB | 0.76 / 1.81 | 1.36 / 1.84 | 1871 | 1513 | 10.87 / 11.89 | 6.62x | 2823065 | 5.5 |
| unicode CJK/emoji | 16 MB | 2.84 / 2.91 | 5.15 / 5.84 | 5986 | 1513 | 32.37 / 33.34 | 6.60x | 9033800 | 17.5 |
| unicode CJK/emoji | 16 MB of 20 MB | 4.75 / 8.47 | 3.70 / 3.88 | 1740 | 4096 | 21.35 / 26.64 | 4.55x | 7124574 | 13.7 |
| number array | 256 KB | 0.02 / 0.04 | 0.26 / 0.29 | 563 | 671 | 1.19 / 1.33 | 4.16x | 369466 | 0.4 |
| number array | 1 MB | 0.15 / 0.18 | 1.03 / 1.20 | 2172 | 703 | 4.61 / 4.96 | 4.00x | 1463296 | 1.5 |
| number array | 5 MB | 0.46 / 1.79 | 4.74 / 5.89 | 10420 | 703 | 22.47 / 25.09 | 3.94x | 7233072 | 7.4 |
| number array | 16 MB | 2.49 / 2.62 | 15.03 / 16.57 | 32069 | 735 | 70.34 / 72.02 | 3.90x | 22902317 | 23.3 |
| number array | 16 MB of 20 MB | 2.48 / 2.52 | 2.41 / 2.46 | 4096 | 4096 | 7.59 / 7.90 | 2.01x | 16777216 | 16.3 |

- splitText(pretty) runs on prettyPrintJson's output, which is the raw text when that is invalid (truncated) or nested beyond 256.
- The body view pretty-prints straight into its pieces (PieceWriter), never through prettyPrintJson's string; a truncated body is printed only when it ends a container, else split raw.
- A case slower than 20 s per call runs once and is marked '(1 run)'.
- View retained heap: heap still reachable through the view after a full GC (pieces plus the per-piece flags), minus the heap before; the captured bytes are not counted.
