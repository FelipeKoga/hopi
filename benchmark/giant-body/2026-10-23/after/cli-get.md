### `hopi get` of one giant response body over the JVM bridge (median / p95 ms; allocation on the CLI's thread as a multiple of the captured size)

| shape | captured | get --json | alloc | get (human) | alloc | human output (MB) |
| --- | --- | --- | --- | --- | --- | --- |
| minified records | 256 KB | 2.92 / 6.46 | 7.82x | 3.92 / 8.97 | 21.44x | 0.4 |
| minified records | 1 MB | 8.72 / 10.12 | 7.10x | 12.46 / 14.20 | 20.66x | 1.6 |
| minified records | 5 MB | 38.24 / 38.85 | 9.70x | 58.51 / 60.72 | 23.19x | 8.1 |
| minified records | 16 MB | 114.81 / 115.67 | 6.87x | 174.90 / 181.40 | 20.32x | 25.9 |
| minified records | 16 MB of 20 MB | 114.51 / 114.90 | 6.87x | 168.02 / 191.68 | 15.62x | 16.0 |
| pretty records | 256 KB | 2.24 / 6.00 | 7.77x | 3.69 / 4.78 | 15.85x | 0.3 |
| pretty records | 1 MB | 7.37 / 9.13 | 7.05x | 10.09 / 10.80 | 15.10x | 1.1 |
| pretty records | 5 MB | 35.53 / 36.38 | 9.63x | 49.98 / 51.65 | 17.66x | 5.7 |
| pretty records | 16 MB | 111.46 / 111.93 | 6.82x | 157.69 / 160.48 | 14.85x | 18.1 |
| pretty records | 16 MB of 20 MB | 112.01 / 114.73 | 6.82x | 155.88 / 158.14 | 13.07x | 16.0 |
| deeply nested | 256 KB | 1.17 / 1.22 | 5.01x | 1.56 / 1.63 | 11.29x | 0.3 |
| deeply nested | 1 MB | 3.89 / 4.35 | 4.27x | 4.75 / 5.32 | 10.52x | 1.0 |
| deeply nested | 5 MB | 18.52 / 19.08 | 5.28x | 22.43 / 22.63 | 11.53x | 5.0 |
| deeply nested | 16 MB | 59.10 / 59.24 | 4.04x | 70.37 / 70.98 | 10.29x | 16.0 |
| deeply nested | 16 MB of 20 MB | 59.21 / 59.42 | 4.04x | 70.74 / 74.05 | 10.29x | 16.0 |
| one huge string | 256 KB | 1.71 / 1.77 | 8.01x | 2.26 / 2.47 | 15.29x | 0.3 |
| one huge string | 1 MB | 6.06 / 6.70 | 7.27x | 7.19 / 7.92 | 14.52x | 1.0 |
| one huge string | 5 MB | 28.39 / 29.68 | 8.28x | 34.58 / 35.29 | 15.53x | 5.0 |
| one huge string | 16 MB | 90.98 / 91.25 | 7.04x | 110.80 / 111.52 | 14.29x | 16.0 |
| one huge string | 16 MB of 20 MB | 90.64 / 91.53 | 5.04x | 108.69 / 109.20 | 11.29x | 16.0 |
| unicode CJK/emoji | 256 KB | 6.44 / 11.51 | 8.15x | 5.32 / 18.66 | 20.52x | 0.3 |
| unicode CJK/emoji | 1 MB | 6.05 / 16.27 | 7.41x | 7.53 / 8.23 | 19.76x | 1.1 |
| unicode CJK/emoji | 5 MB | 28.69 / 32.20 | 8.11x | 36.36 / 36.79 | 20.45x | 5.6 |
| unicode CJK/emoji | 16 MB | 91.88 / 92.89 | 7.18x | 115.10 / 115.74 | 19.52x | 17.8 |
| unicode CJK/emoji | 16 MB of 20 MB | 92.08 / 92.45 | 7.18x | 108.61 / 109.41 | 16.42x | 16.0 |
| number array | 256 KB | 1.58 / 2.04 | 5.01x | 6.30 / 6.46 | 17.26x | 0.4 |
| number array | 1 MB | 5.13 / 5.91 | 4.27x | 9.53 / 11.07 | 16.41x | 1.4 |
| number array | 5 MB | 24.40 / 25.01 | 5.28x | 43.93 / 45.21 | 17.32x | 6.9 |
| number array | 16 MB | 77.83 / 78.59 | 4.04x | 139.57 / 140.11 | 15.99x | 21.9 |
| number array | 16 MB of 20 MB | 78.23 / 78.74 | 4.04x | 130.84 / 135.43 | 12.79x | 16.0 |

- Allocation is the CLI's own thread (the bridge serves from its own threads, measured in :hopi-core's benchmark); stdout is counted, not kept.
