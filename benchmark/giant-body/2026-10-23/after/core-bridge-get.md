### Bridge `get` of one transaction with a giant response body, through a real BridgeSession (median / p95 ms; allocation over all threads; transient peak heap)

| shape | captured | bridgeDetail() | alloc | session get | bytes sent | alloc | peak heap delta (MB) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| minified records | 256 KB | 1.05 / 1.14 | 3.53x | 1.16 / 2.59 | 0.3 | 1.95x | 0.0 |
| minified records | 1 MB | 3.27 / 3.86 | 3.53x | 4.16 / 5.58 | 1.2 | 1.24x | 2.0 |
| minified records | 5 MB | 15.24 / 15.89 | 3.52x | 16.86 / 17.83 | 5.9 | 1.05x | 5.0 |
| minified records | 16 MB | 49.12 / 52.10 | 3.52x | 53.27 / 54.66 | 19.0 | 1.02x | 18.4 |
| minified records | 16 MB of 20 MB | 49.24 / 52.47 | 3.52x | 53.62 / 56.82 | 19.0 | 1.02x | 18.0 |
| pretty records | 256 KB | 0.98 / 1.11 | 3.51x | 0.96 / 1.07 | 0.3 | 1.94x | 0.0 |
| pretty records | 1 MB | 2.99 / 3.45 | 3.51x | 3.41 / 3.62 | 1.2 | 1.24x | 2.0 |
| pretty records | 5 MB | 16.79 / 17.91 | 3.51x | 16.97 / 25.93 | 5.9 | 1.05x | 6.0 |
| pretty records | 16 MB | 48.00 / 48.63 | 3.51x | 52.26 / 53.96 | 18.8 | 1.02x | 18.0 |
| pretty records | 16 MB of 20 MB | 48.17 / 48.97 | 3.51x | 52.17 / 52.66 | 18.8 | 1.02x | 18.0 |
| deeply nested | 256 KB | 0.60 / 0.63 | 3.34x | 0.73 / 0.84 | 0.3 | 1.62x | 0.0 |
| deeply nested | 1 MB | 2.50 / 2.83 | 3.33x | 2.66 / 3.00 | 1.0 | 1.15x | 2.0 |
| deeply nested | 5 MB | 12.42 / 14.33 | 3.33x | 13.53 / 14.41 | 5.0 | 1.03x | 6.0 |
| deeply nested | 16 MB | 37.09 / 40.22 | 3.33x | 43.55 / 48.23 | 16.0 | 1.01x | 18.0 |
| deeply nested | 16 MB of 20 MB | 36.92 / 37.57 | 3.33x | 44.81 / 47.94 | 16.0 | 1.01x | 18.0 |
| one huge string | 256 KB | 0.61 / 0.66 | 3.34x | 0.75 / 0.81 | 0.3 | 1.62x | 0.0 |
| one huge string | 1 MB | 2.46 / 2.76 | 3.33x | 2.75 / 2.92 | 1.0 | 1.15x | 2.0 |
| one huge string | 5 MB | 11.67 / 12.19 | 3.33x | 15.15 / 16.37 | 5.0 | 1.03x | 6.0 |
| one huge string | 16 MB | 36.79 / 38.53 | 3.33x | 47.45 / 52.42 | 16.0 | 1.01x | 18.0 |
| one huge string | 16 MB of 20 MB | 36.99 / 38.35 | 3.33x | 46.95 / 51.61 | 16.0 | 1.01x | 18.0 |
| unicode CJK/emoji | 256 KB | 1.78 / 3.64 | 8.69x | 0.89 / 0.97 | 0.3 | 4.70x | 1.0 |
| unicode CJK/emoji | 1 MB | 2.38 / 2.81 | 8.69x | 3.03 / 3.39 | 1.0 | 3.63x | 4.0 |
| unicode CJK/emoji | 5 MB | 15.89 / 17.20 | 8.69x | 16.52 / 20.66 | 5.2 | 3.35x | 18.7 |
| unicode CJK/emoji | 16 MB | 38.16 / 38.67 | 8.69x | 47.07 / 47.83 | 16.7 | 3.30x | 55.0 |
| unicode CJK/emoji | 16 MB of 20 MB | 38.41 / 39.45 | 8.69x | 46.97 / 47.31 | 16.7 | 3.30x | 55.0 |
| number array | 256 KB | 0.77 / 0.82 | 3.34x | 0.91 / 1.01 | 0.3 | 1.62x | 0.0 |
| number array | 1 MB | 3.02 / 3.41 | 3.33x | 3.42 / 3.52 | 1.0 | 1.15x | 1.0 |
| number array | 5 MB | 14.73 / 16.31 | 3.33x | 16.70 / 17.23 | 5.0 | 1.03x | 5.0 |
| number array | 16 MB | 46.46 / 46.87 | 3.33x | 54.95 / 62.43 | 16.0 | 1.01x | 18.0 |
| number array | 16 MB of 20 MB | 46.65 / 47.84 | 3.33x | 53.55 / 57.01 | 16.0 | 1.01x | 18.0 |
| binary (base64) | 256 KB | 0.41 / 0.74 | 8.01x | 0.51 / 1.21 | 0.3 | 3.28x | 0.0 |
| binary (base64) | 1 MB | 0.91 / 1.39 | 8.00x | 1.67 / 1.88 | 1.3 | 2.82x | 2.0 |
| binary (base64) | 5 MB | 11.13 / 19.43 | 8.00x | 8.69 / 9.93 | 6.7 | 2.70x | 13.0 |
| binary (base64) | 16 MB | 16.07 / 24.47 | 8.00x | 23.49 / 25.78 | 21.3 | 2.68x | 43.0 |
| binary (base64) | 16 MB of 20 MB | 15.09 / 15.42 | 8.00x | 23.55 / 27.33 | 21.3 | 2.68x | 43.0 |

- 'session get' runs BridgeSession.run for one `get` line and waits until the writer closed the connection; bytes are counted, not kept.
- Peak heap delta is the sum of the heap pools' peaks after a reset minus their use at the reset: an upper bound of the transient.
