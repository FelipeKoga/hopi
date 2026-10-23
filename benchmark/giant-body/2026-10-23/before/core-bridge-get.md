### Bridge `get` of one transaction with a giant response body, through a real BridgeSession (median / p95 ms; allocation over all threads; transient peak heap)

| shape | captured | bridgeDetail() | alloc | session get | bytes sent | alloc | peak heap delta (MB) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| minified records | 256 KB | 1.11 / 4.79 | 5.53x | 1.40 / 1.51 | 0.3 | 8.03x | 1.0 |
| minified records | 1 MB | 4.34 / 6.13 | 5.53x | 4.47 / 5.19 | 1.2 | 7.94x | 7.4 |
| minified records | 5 MB | 19.02 / 20.24 | 5.52x | 21.23 / 22.70 | 5.9 | 7.90x | 25.6 |
| minified records | 16 MB | 61.35 / 73.90 | 5.52x | 66.36 / 82.29 | 19.0 | 7.89x | 80.0 |
| minified records | 16 MB of 20 MB | 60.95 / 61.47 | 5.52x | 66.38 / 68.93 | 19.0 | 7.89x | 80.0 |
| pretty records | 256 KB | 1.37 / 1.55 | 5.51x | 1.33 / 1.52 | 0.3 | 7.98x | 1.0 |
| pretty records | 1 MB | 3.72 / 4.16 | 5.51x | 4.18 / 4.66 | 1.2 | 7.89x | 7.4 |
| pretty records | 5 MB | 18.98 / 19.21 | 5.51x | 20.75 / 22.41 | 5.9 | 7.86x | 25.7 |
| pretty records | 16 MB | 61.87 / 65.40 | 9.51x | 66.90 / 68.07 | 18.8 | 11.85x | 138.0 |
| pretty records | 16 MB of 20 MB | 60.03 / 69.14 | 5.51x | 65.27 / 74.05 | 18.8 | 7.85x | 130.0 |
| deeply nested | 256 KB | 0.77 / 1.27 | 5.34x | 0.94 / 1.03 | 0.3 | 7.45x | 1.0 |
| deeply nested | 1 MB | 3.12 / 3.57 | 5.33x | 3.44 / 3.96 | 1.0 | 7.36x | 11.4 |
| deeply nested | 5 MB | 15.45 / 16.03 | 5.33x | 16.93 / 17.06 | 5.0 | 7.34x | 25.7 |
| deeply nested | 16 MB | 49.51 / 60.40 | 5.33x | 53.42 / 56.44 | 16.0 | 7.34x | 124.0 |
| deeply nested | 16 MB of 20 MB | 48.76 / 49.27 | 5.33x | 53.35 / 53.99 | 16.0 | 7.34x | 74.0 |
| one huge string | 256 KB | 0.84 / 0.89 | 5.34x | 1.07 / 1.17 | 0.3 | 7.45x | 1.0 |
| one huge string | 1 MB | 3.05 / 3.48 | 5.33x | 3.41 / 3.93 | 1.0 | 7.36x | 11.6 |
| one huge string | 5 MB | 15.43 / 15.61 | 5.33x | 16.87 / 17.06 | 5.0 | 7.34x | 24.9 |
| one huge string | 16 MB | 48.87 / 52.87 | 5.33x | 53.25 / 53.47 | 16.0 | 7.34x | 124.0 |
| one huge string | 16 MB of 20 MB | 49.01 / 49.89 | 5.33x | 53.24 / 53.72 | 16.0 | 7.34x | 124.0 |
| unicode CJK/emoji | 256 KB | 1.92 / 3.69 | 8.69x | 1.13 / 1.22 | 0.3 | 12.21x | 3.0 |
| unicode CJK/emoji | 1 MB | 2.72 / 3.18 | 8.69x | 3.55 / 4.00 | 1.0 | 12.11x | 15.9 |
| unicode CJK/emoji | 5 MB | 13.82 / 14.90 | 8.69x | 17.64 / 17.84 | 5.2 | 12.09x | 60.7 |
| unicode CJK/emoji | 16 MB | 44.42 / 52.85 | 8.69x | 55.50 / 57.13 | 16.7 | 12.08x | 144.0 |
| unicode CJK/emoji | 16 MB of 20 MB | 43.66 / 43.98 | 8.69x | 55.79 / 61.04 | 16.7 | 12.08x | 160.0 |
| number array | 256 KB | 1.05 / 1.10 | 5.34x | 1.22 / 1.35 | 0.3 | 7.45x | 1.0 |
| number array | 1 MB | 3.66 / 4.22 | 5.33x | 4.00 / 4.46 | 1.0 | 7.36x | 11.4 |
| number array | 5 MB | 18.28 / 18.66 | 5.33x | 20.08 / 20.67 | 5.0 | 7.34x | 25.6 |
| number array | 16 MB | 58.91 / 62.55 | 5.33x | 63.39 / 72.69 | 16.0 | 7.34x | 74.0 |
| number array | 16 MB of 20 MB | 58.98 / 60.13 | 5.33x | 63.30 / 64.30 | 16.0 | 7.34x | 124.0 |
| binary (base64) | 256 KB | 0.49 / 0.62 | 10.01x | 0.55 / 0.66 | 0.3 | 12.79x | 4.0 |
| binary (base64) | 1 MB | 0.79 / 1.28 | 10.00x | 1.23 / 1.91 | 1.3 | 12.70x | 15.4 |
| binary (base64) | 5 MB | 3.91 / 5.43 | 10.00x | 5.70 / 6.40 | 6.7 | 12.67x | 56.7 |
| binary (base64) | 16 MB | 12.84 / 22.37 | 10.00x | 18.58 / 21.48 | 21.3 | 12.67x | 176.0 |
| binary (base64) | 16 MB of 20 MB | 12.66 / 12.95 | 10.00x | 18.47 / 20.00 | 21.3 | 12.67x | 176.0 |

- 'session get' runs BridgeSession.run for one `get` line and waits until the writer closed the connection; bytes are counted, not kept.
- Peak heap delta is the sum of the heap pools' peaks after a reset minus their use at the reset: an upper bound of the transient.
