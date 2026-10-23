### Detail screen, Response tab, 400x800 dp headless (ms; frame = composition + layout)

| body | format (off main) | pieces | longest piece | first frame | first draw | scroll frames | frame median / p95 / max | jump to end |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| pretty 1 MB | 3.78 | 956 | 1273 | 11.24 | 0.72 | 22 | 0.70 / 6.16 / 6.83 | 2.43 |
| minified 1 MB (pretty-printed) | 5.53 | 1378 | 1273 | 8.67 | 0.72 | 22 | 0.66 / 5.34 / 5.72 | 2.34 |
| minified 1 MB of 1280 KB (truncated, one line) | 3.83 | 256 | 4096 | 11.74 | 1.02 | 22 | 0.67 / 6.18 / 6.38 | 2.99 |
| one 1 MB string (one line) | 10.53 | 258 | 4096 | 9.66 | 0.80 | 22 | 0.59 / 6.29 / 6.75 | 3.23 |
| pretty 8 MB | 45.18 | 7531 | 1291 | 8.09 | 0.68 | 22 | 0.51 / 4.43 / 4.84 | 2.24 |
| minified 8 MB (pretty-printed) | 43.10 | 10784 | 1291 | 7.01 | 0.76 | 22 | 0.52 / 4.65 / 5.09 | 1.88 |
| minified 8 MB of 10 MB (truncated, one line) | 30.29 | 2048 | 4096 | 7.85 | 0.80 | 22 | 0.52 / 5.14 / 5.37 | 2.59 |
| one 8 MB string (one line) | 10.61 | 2050 | 4096 | 7.38 | 0.72 | 22 | 0.55 / 5.79 / 6.39 | 2.67 |
| pretty 16 MB | 102.41 | 15031 | 1291 | 6.27 | 0.66 | 22 | 0.38 / 3.74 / 4.02 | 1.49 |
| minified 16 MB (pretty-printed) | 74.04 | 21431 | 1309 | 6.47 | 0.62 | 22 | 0.39 / 3.73 / 3.85 | 1.90 |
| minified 16 MB of 20 MB (truncated, one line) | 57.37 | 4096 | 4096 | 6.85 | 0.71 | 22 | 0.53 / 4.66 / 4.92 | 2.28 |
| one 16 MB string (one line) | 18.07 | 4098 | 4096 | 6.66 | 0.70 | 22 | 0.52 / 5.30 / 5.70 | 2.31 |

- Scroll: one animated ScrollBy of 40 000 px through the semantics action, stepped frame by frame until the offset stops moving; only frames that moved are counted.
- Headless JVM numbers: a lower bound of the work, not a device frame time; the emulator numbers in ARCHITECTURE.md are the device reference.
