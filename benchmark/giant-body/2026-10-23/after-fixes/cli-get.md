### `hopi get` of one giant response body over the JVM bridge (median / p95 ms; allocation on the CLI's thread as a multiple of the captured size)

| shape | captured | get --json | alloc | get (human) | alloc | human output (MB) |
| --- | --- | --- | --- | --- | --- | --- |
| minified records | 256 KB | 3.28 / 14.11 | 7.82x | 3.91 / 9.77 | 19.45x | 0.4 |
| minified records | 1 MB | 7.89 / 12.88 | 7.10x | 11.08 / 12.50 | 18.66x | 1.6 |
| minified records | 5 MB | 35.19 / 36.17 | 9.70x | 51.67 / 54.88 | 21.19x | 8.1 |
| minified records | 16 MB | 104.58 / 104.63 | 6.87x | 152.41 / 159.77 | 18.32x | 25.9 |
| minified records | 16 MB of 20 MB | 103.73 / 105.94 | 6.87x | 145.47 / 168.46 | 13.62x | 16.0 |
| pretty records | 256 KB | 2.14 / 5.84 | 7.77x | 3.73 / 4.62 | 16.36x | 0.3 |
| pretty records | 1 MB | 6.48 / 7.39 | 7.05x | 8.88 / 9.57 | 15.60x | 1.1 |
| pretty records | 5 MB | 31.78 / 32.73 | 9.63x | 43.87 / 46.66 | 18.16x | 5.7 |
| pretty records | 16 MB | 100.71 / 101.36 | 6.82x | 137.98 / 138.27 | 15.35x | 18.1 |
| pretty records | 16 MB of 20 MB | 100.65 / 101.17 | 6.82x | 136.75 / 139.98 | 13.57x | 16.0 |
| deeply nested | 256 KB | 1.20 / 2.36 | 5.01x | 1.58 / 2.20 | 12.38x | 0.3 |
| deeply nested | 1 MB | 3.91 / 4.40 | 4.27x | 4.77 / 6.00 | 11.17x | 1.0 |
| deeply nested | 5 MB | 18.49 / 19.20 | 5.28x | 22.19 / 22.71 | 12.06x | 5.0 |
| deeply nested | 16 MB | 59.24 / 59.48 | 4.04x | 70.58 / 71.40 | 10.80x | 16.0 |
| deeply nested | 16 MB of 20 MB | 59.39 / 60.11 | 4.04x | 71.13 / 81.70 | 10.80x | 16.0 |
| one huge string | 256 KB | 1.47 / 5.76 | 8.01x | 1.98 / 4.44 | 15.79x | 0.3 |
| one huge string | 1 MB | 4.75 / 6.26 | 7.27x | 6.01 / 6.59 | 15.03x | 1.0 |
| one huge string | 5 MB | 22.80 / 23.53 | 8.28x | 29.13 / 31.04 | 16.03x | 5.0 |
| one huge string | 16 MB | 72.37 / 79.10 | 7.04x | 92.66 / 92.90 | 14.79x | 16.0 |
| one huge string | 16 MB of 20 MB | 71.99 / 72.51 | 5.04x | 90.89 / 93.03 | 11.79x | 16.0 |
| unicode CJK/emoji | 256 KB | 5.37 / 6.87 | 8.15x | 4.79 / 11.50 | 19.04x | 0.3 |
| unicode CJK/emoji | 1 MB | 6.32 / 12.52 | 7.41x | 7.13 / 11.09 | 18.28x | 1.1 |
| unicode CJK/emoji | 5 MB | 27.49 / 28.38 | 8.11x | 34.03 / 34.25 | 18.96x | 5.6 |
| unicode CJK/emoji | 16 MB | 90.51 / 100.40 | 7.18x | 107.66 / 108.59 | 18.04x | 17.8 |
| unicode CJK/emoji | 16 MB of 20 MB | 88.02 / 89.99 | 7.18x | 103.97 / 104.90 | 14.94x | 16.0 |
| number array | 256 KB | 1.53 / 1.63 | 5.01x | 6.67 / 9.53 | 15.26x | 0.4 |
| number array | 1 MB | 5.14 / 6.05 | 4.27x | 9.29 / 9.93 | 14.41x | 1.4 |
| number array | 5 MB | 24.38 / 25.83 | 5.28x | 43.43 / 47.13 | 15.32x | 6.9 |
| number array | 16 MB | 78.57 / 79.13 | 4.04x | 137.64 / 139.24 | 13.99x | 21.9 |
| number array | 16 MB of 20 MB | 78.39 / 79.15 | 4.04x | 129.11 / 134.98 | 10.79x | 16.0 |

- Allocation is the CLI's own thread (the bridge serves from its own threads, measured in :hopi-core's benchmark); stdout is counted, not kept.
