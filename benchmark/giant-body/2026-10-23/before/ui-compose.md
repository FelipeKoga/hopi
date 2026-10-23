### Detail screen, Response tab, 400x800 dp headless (ms; frame = composition + layout)

| body | format (off main) | pieces | longest piece | first frame | first draw | scroll frames | frame median / p95 / max | jump to end |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| pretty 1 MB | 55.82 | 956 | 1273 | 250.58 | 6.90 | 22 | 1.12 / 10.35 / 13.39 | 7.53 |
| minified 1 MB (pretty-printed) | 10.35 | 1378 | 1273 | 13.36 | 0.88 | 22 | 1.03 / 7.42 / 7.43 | 3.02 |
| minified 1 MB of 1280 KB (truncated, one line) | 11.86 | 256 | 4096 | 12.93 | 0.89 | 22 | 0.85 / 7.02 / 7.19 | 3.22 |
| one 1 MB string (one line) | 18.52 | 258 | 4096 | 11.25 | 0.83 | 22 | 0.67 / 6.91 / 7.84 | 3.43 |
| pretty 8 MB | 53.43 | 7531 | 1291 | 9.05 | 0.73 | 22 | 0.63 / 4.90 / 5.43 | 2.56 |
| minified 8 MB (pretty-printed) | 52.63 | 10784 | 1291 | 13.13 | 1.33 | 22 | 0.67 / 5.09 / 7.78 | 3.22 |
| minified 8 MB of 10 MB (truncated, one line) | 427.04 | 2048 | 4096 | 8.69 | 0.80 | 22 | 0.60 / 5.83 / 5.92 | 2.54 |
| one 8 MB string (one line) | 414.92 | 2050 | 4096 | 8.24 | 0.77 | 22 | 0.50 / 6.42 / 7.22 | 2.74 |
| pretty 16 MB | 72.47 | 15031 | 1291 | 6.59 | 0.66 | 22 | 0.47 / 4.29 / 4.81 | 1.84 |
| minified 16 MB (pretty-printed) | 86.53 | 21431 | 1309 | 6.53 | 0.68 | 22 | 0.50 / 3.78 / 4.34 | 1.98 |
| minified 16 MB of 20 MB (truncated, one line) | 1628.63 | 4096 | 4096 | 9.25 | 0.75 | 22 | 0.56 / 5.33 / 5.34 | 2.25 |
| one 16 MB string (one line) | 1634.93 | 4098 | 4096 | 8.57 | 0.74 | 22 | 0.53 / 5.59 / 5.89 | 2.64 |

- Scroll: one animated ScrollBy of 40 000 px through the semantics action, stepped frame by frame until the offset stops moving; only frames that moved are counted.
- Headless JVM numbers: a lower bound of the work, not a device frame time; the emulator numbers in ARCHITECTURE.md are the device reference.
