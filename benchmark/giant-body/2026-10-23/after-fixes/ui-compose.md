### Detail screen, Response tab, 400x800 dp headless (ms; frame = composition + layout)

| body | format (off main) | pieces | longest piece | first frame | first draw | scroll frames | frame median / p95 / max | jump to end |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| pretty 1 MB | 4.38 | 956 | 1273 | 11.90 | 0.70 | 22 | 0.70 / 5.74 / 6.65 | 2.62 |
| minified 1 MB (pretty-printed) | 3.82 | 1378 | 1273 | 9.14 | 0.73 | 22 | 0.74 / 5.42 / 5.46 | 2.74 |
| minified 1 MB of 1280 KB (truncated, one line) | 1.56 | 256 | 4096 | 11.97 | 0.93 | 22 | 0.68 / 6.00 / 6.29 | 2.87 |
| one 1 MB string (one line) | 1.35 | 258 | 4096 | 9.40 | 0.74 | 22 | 0.54 / 5.95 / 6.57 | 3.16 |
| pretty 8 MB | 28.79 | 7531 | 1291 | 7.73 | 0.67 | 22 | 0.50 / 4.29 / 4.73 | 2.14 |
| minified 8 MB (pretty-printed) | 34.31 | 10784 | 1291 | 6.79 | 0.68 | 22 | 0.49 / 4.04 / 4.21 | 1.99 |
| minified 8 MB of 10 MB (truncated, one line) | 4.57 | 2048 | 4096 | 8.12 | 0.75 | 22 | 0.56 / 4.98 / 5.27 | 2.42 |
| one 8 MB string (one line) | 9.70 | 2050 | 4096 | 7.30 | 0.69 | 22 | 0.51 / 5.45 / 6.19 | 2.58 |
| pretty 16 MB | 49.44 | 15031 | 1291 | 6.26 | 0.64 | 22 | 0.45 / 3.85 / 4.21 | 1.68 |
| minified 16 MB (pretty-printed) | 68.63 | 21431 | 1309 | 6.67 | 0.71 | 22 | 0.41 / 3.73 / 3.92 | 1.84 |
| minified 16 MB of 20 MB (truncated, one line) | 9.63 | 4096 | 4096 | 6.71 | 0.69 | 22 | 0.50 / 5.05 / 5.18 | 2.13 |
| one 16 MB string (one line) | 18.79 | 4098 | 4096 | 6.89 | 0.70 | 22 | 0.54 / 5.52 / 5.98 | 2.33 |

- Scroll: one animated ScrollBy of 40 000 px through the semantics action, stepped frame by frame until the offset stops moving; only frames that moved are counted.
- Headless JVM numbers: a lower bound of the work, not a device frame time; the emulator numbers in ARCHITECTURE.md are the device reference.
