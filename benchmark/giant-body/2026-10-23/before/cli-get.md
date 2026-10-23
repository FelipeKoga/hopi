### `hopi get` of one giant response body over the JVM bridge (median / p95 ms; allocation on the CLI's thread as a multiple of the captured size)

| shape | captured | get --json | alloc | get (human) | alloc | human output (MB) |
| --- | --- | --- | --- | --- | --- | --- |
| minified records | 256 KB | 3.35 / 9.19 | 9.31x | 5.43 / 8.52 | 22.93x | 0.4 |
| minified records | 1 MB | 10.84 / 12.07 | 8.66x | 12.87 / 14.10 | 22.22x | 1.6 |
| minified records | 5 MB | 40.08 / 44.54 | 9.03x | 59.62 / 69.62 | 22.52x | 8.1 |
| minified records | 16 MB | 126.54 / 132.13 | 8.45x | 176.88 / 178.20 | 21.90x | 25.9 |
| minified records | 16 MB of 20 MB | 118.40 / 130.01 | 8.45x | 171.94 / 196.62 | 17.20x | 16.0 |
| pretty records | 256 KB | 2.69 / 6.38 | 9.29x | 3.65 / 5.47 | 17.37x | 0.3 |
| pretty records | 1 MB | 7.63 / 8.53 | 8.64x | 10.42 / 10.98 | 16.70x | 1.1 |
| pretty records | 5 MB | 36.23 / 40.21 | 9.02x | 50.90 / 55.91 | 17.05x | 5.7 |
| pretty records | 16 MB | 127.90 / 128.16 | 8.44x | 162.40 / 164.06 | 16.47x | 18.1 |
| pretty records | 16 MB of 20 MB | 115.07 / 115.51 | 8.44x | 159.39 / 163.65 | 14.69x | 16.0 |
| deeply nested | 256 KB | 1.78 / 2.24 | 6.87x | 2.00 / 3.32 | 13.14x | 0.3 |
| deeply nested | 1 MB | 4.74 / 5.26 | 6.22x | 5.65 / 6.17 | 12.47x | 1.0 |
| deeply nested | 5 MB | 22.08 / 22.90 | 5.25x | 25.80 / 26.83 | 11.50x | 5.0 |
| deeply nested | 16 MB | 69.09 / 69.59 | 4.02x | 80.91 / 101.97 | 10.27x | 16.0 |
| deeply nested | 16 MB of 20 MB | 69.24 / 69.75 | 4.02x | 81.10 / 82.54 | 10.27x | 16.0 |
| one huge string | 256 KB | 1.83 / 4.82 | 9.87x | 2.35 / 5.90 | 17.14x | 0.3 |
| one huge string | 1 MB | 5.57 / 6.42 | 9.22x | 6.83 / 7.47 | 16.47x | 1.0 |
| one huge string | 5 MB | 26.23 / 26.87 | 8.25x | 32.69 / 33.67 | 15.50x | 5.0 |
| one huge string | 16 MB | 83.28 / 87.63 | 7.02x | 102.41 / 103.14 | 14.27x | 16.0 |
| one huge string | 16 MB of 20 MB | 82.51 / 82.87 | 5.02x | 100.72 / 101.07 | 11.27x | 16.0 |
| unicode CJK/emoji | 256 KB | 6.18 / 10.29 | 7.80x | 5.00 / 10.21 | 20.18x | 0.3 |
| unicode CJK/emoji | 1 MB | 7.20 / 13.33 | 7.15x | 8.38 / 9.36 | 19.50x | 1.1 |
| unicode CJK/emoji | 5 MB | 34.38 / 34.93 | 7.73x | 41.93 / 55.44 | 20.07x | 5.6 |
| unicode CJK/emoji | 16 MB | 108.75 / 110.04 | 6.95x | 133.52 / 136.38 | 19.29x | 17.8 |
| unicode CJK/emoji | 16 MB of 20 MB | 109.28 / 110.66 | 6.95x | 128.45 / 134.24 | 16.19x | 16.0 |
| number array | 256 KB | 1.64 / 2.14 | 6.87x | 6.40 / 7.28 | 19.11x | 0.4 |
| number array | 1 MB | 5.33 / 6.02 | 6.22x | 9.29 / 10.19 | 18.36x | 1.4 |
| number array | 5 MB | 25.47 / 27.56 | 5.25x | 44.71 / 47.14 | 17.29x | 6.9 |
| number array | 16 MB | 79.15 / 79.34 | 4.02x | 140.80 / 141.52 | 15.97x | 21.9 |
| number array | 16 MB of 20 MB | 79.12 / 82.39 | 4.02x | 135.31 / 137.78 | 12.77x | 16.0 |

- Allocation is the CLI's own thread (the bridge serves from its own threads, measured in :hopi-core's benchmark); stdout is counted, not kept.
