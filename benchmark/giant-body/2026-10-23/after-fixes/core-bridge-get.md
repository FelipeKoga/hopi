### Bridge `get` of one transaction with a giant response body, through a real BridgeSession (median / p95 ms; allocation over all threads; transient peak heap)

| shape | captured | bridgeDetail() | alloc | session get | bytes sent | alloc | peak heap delta (MB) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| minified records | 256 KB | 0.87 / 4.49 | 3.53x | 1.11 / 3.78 | 0.3 | 1.95x | 0.0 |
| minified records | 1 MB | 3.19 / 3.56 | 3.53x | 3.55 / 3.68 | 1.2 | 1.24x | 2.0 |
| minified records | 5 MB | 15.68 / 16.58 | 3.52x | 16.81 / 17.28 | 5.9 | 1.05x | 5.0 |
| minified records | 16 MB | 49.62 / 52.91 | 3.52x | 53.48 / 54.28 | 19.0 | 1.02x | 18.0 |
| minified records | 16 MB of 20 MB | 49.25 / 50.05 | 3.52x | 53.66 / 55.35 | 19.0 | 1.02x | 18.0 |
| pretty records | 256 KB | 0.99 / 1.10 | 3.51x | 0.95 / 1.04 | 0.3 | 1.94x | 0.0 |
| pretty records | 1 MB | 2.99 / 3.42 | 3.51x | 3.42 / 3.53 | 1.2 | 1.24x | 2.0 |
| pretty records | 5 MB | 16.70 / 17.24 | 3.51x | 16.64 / 16.89 | 5.9 | 1.05x | 6.0 |
| pretty records | 16 MB | 48.18 / 49.93 | 3.51x | 52.14 / 52.59 | 18.8 | 1.02x | 18.0 |
| pretty records | 16 MB of 20 MB | 48.09 / 49.75 | 3.51x | 52.66 / 54.00 | 18.8 | 1.02x | 18.0 |
| deeply nested | 256 KB | 0.61 / 0.64 | 3.34x | 0.74 / 0.90 | 0.3 | 1.62x | 0.0 |
| deeply nested | 1 MB | 2.37 / 2.76 | 3.33x | 2.71 / 2.93 | 1.0 | 1.15x | 2.0 |
| deeply nested | 5 MB | 11.67 / 11.93 | 3.33x | 13.70 / 15.40 | 5.0 | 1.03x | 6.0 |
| deeply nested | 16 MB | 36.72 / 37.20 | 3.33x | 44.44 / 46.60 | 16.0 | 1.01x | 18.0 |
| deeply nested | 16 MB of 20 MB | 36.96 / 38.31 | 3.33x | 44.74 / 50.37 | 16.0 | 1.01x | 18.0 |
| one huge string | 256 KB | 0.62 / 0.65 | 3.34x | 0.75 / 0.86 | 0.3 | 1.62x | 0.0 |
| one huge string | 1 MB | 2.32 / 2.71 | 3.33x | 2.75 / 2.99 | 1.0 | 1.15x | 2.0 |
| one huge string | 5 MB | 11.55 / 11.83 | 3.33x | 14.89 / 15.78 | 5.0 | 1.03x | 6.0 |
| one huge string | 16 MB | 36.89 / 38.14 | 3.33x | 44.91 / 49.79 | 16.0 | 1.01x | 18.0 |
| one huge string | 16 MB of 20 MB | 36.98 / 38.26 | 3.33x | 47.28 / 50.48 | 16.0 | 1.01x | 18.0 |
| unicode CJK/emoji | 256 KB | 2.88 / 16.10 | 8.69x | 0.96 / 1.17 | 0.3 | 4.70x | 1.0 |
| unicode CJK/emoji | 1 MB | 3.31 / 53.89 | 8.69x | 9.75 / 14.75 | 1.0 | 3.63x | 4.0 |
| unicode CJK/emoji | 5 MB | 28.98 / 54.50 | 8.69x | 40.35 / 92.29 | 5.2 | 3.35x | 18.9 |
| unicode CJK/emoji | 16 MB | 84.69 / 99.29 | 8.69x | 90.45 / 119.17 | 16.7 | 3.30x | 55.0 |
| unicode CJK/emoji | 16 MB of 20 MB | 119.93 / 177.02 | 8.69x | 97.75 / 133.05 | 16.7 | 3.30x | 55.0 |
| number array | 256 KB | 1.64 / 3.39 | 3.34x | 1.98 / 3.25 | 0.3 | 1.62x | 0.0 |
| number array | 1 MB | 4.35 / 15.92 | 3.33x | 4.86 / 7.62 | 1.0 | 1.16x | 1.0 |
| number array | 5 MB | 24.83 / 37.84 | 3.33x | 33.01 / 46.66 | 5.0 | 1.03x | 5.0 |
| number array | 16 MB | 86.95 / 101.76 | 3.33x | 60.64 / 89.48 | 16.0 | 1.01x | 18.0 |
| number array | 16 MB of 20 MB | 52.37 / 72.32 | 3.33x | 53.92 / 60.54 | 16.0 | 1.01x | 18.0 |
| binary (base64) | 256 KB | 0.40 / 0.71 | 8.01x | 0.51 / 1.26 | 0.3 | 3.28x | 0.0 |
| binary (base64) | 1 MB | 0.88 / 1.37 | 8.00x | 1.66 / 1.74 | 1.3 | 2.82x | 2.0 |
| binary (base64) | 5 MB | 4.46 / 6.13 | 8.00x | 7.39 / 8.29 | 6.7 | 2.70x | 13.0 |
| binary (base64) | 16 MB | 13.76 / 14.07 | 8.00x | 25.15 / 28.96 | 21.3 | 2.68x | 43.0 |
| binary (base64) | 16 MB of 20 MB | 13.61 / 13.75 | 8.00x | 22.57 / 23.70 | 21.3 | 2.68x | 43.0 |

- 'session get' runs BridgeSession.run for one `get` line and waits until the writer closed the connection; bytes are counted, not kept.
- Peak heap delta is the sum of the heap pools' peaks after a reset minus their use at the reset: an upper bound of the transient.
