### App-perceived request time with a giant body (median / p95 ms; alloc = bytes allocated by all threads per request)

| transport | body | arm | median / p95 (ms) | Δ median vs no plugin (ms) | alloc (MB) | Δ alloc vs no plugin (MB) | Δ alloc / cap | captured |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MockEngine, Content-Length | 256 KB | no plugin | 0.35 / 0.75 | - | 0.5 | - | - | - |
| MockEngine, Content-Length | 256 KB | cap 256 KB | 0.43 / 0.76 | 0.08 | 0.8 | 0.3 | 1.03x | 262131 B |
| MockEngine, Content-Length | 256 KB | cap 8 MB | 0.42 / 0.61 | 0.07 | 0.8 | 0.3 | 1.03x | 262131 B |
| MockEngine, Content-Length | 256 KB | cap 16 MB | 0.44 / 1.15 | 0.09 | 0.8 | 0.3 | 1.03x | 262131 B |
| MockEngine, Content-Length | 1 MB | no plugin | 0.45 / 1.56 | - | 2.0 | - | - | - |
| MockEngine, Content-Length | 1 MB | cap 256 KB | 0.57 / 1.30 | 0.12 | 2.3 | 0.3 | 1.04x | 256 KB (truncated) |
| MockEngine, Content-Length | 1 MB | cap 8 MB | 0.55 / 0.94 | 0.10 | 3.0 | 1.0 | 1.01x | 1048607 B |
| MockEngine, Content-Length | 1 MB | cap 16 MB | 0.56 / 1.18 | 0.10 | 3.0 | 1.0 | 1.01x | 1048607 B |
| MockEngine, Content-Length | 5 MB | no plugin | 1.45 / 2.86 | - | 12.9 | - | - | - |
| MockEngine, Content-Length | 5 MB | cap 256 KB | 1.50 / 2.83 | 0.05 | 13.2 | 0.3 | 1.04x | 256 KB (truncated) |
| MockEngine, Content-Length | 5 MB | cap 8 MB | 1.88 / 3.61 | 0.43 | 18.9 | 5.9 | 1.19x | 5242847 B |
| MockEngine, Content-Length | 5 MB | cap 16 MB | 1.84 / 3.81 | 0.39 | 18.9 | 5.9 | 1.19x | 5242847 B |
| MockEngine, Content-Length | 20 MB | no plugin | 9.30 / 14.14 | - | 88.3 | - | - | - |
| MockEngine, Content-Length | 20 MB | cap 256 KB | 9.58 / 29.60 | 0.27 | 88.5 | 0.3 | 1.03x | 256 KB (truncated) |
| MockEngine, Content-Length | 20 MB | cap 8 MB | 10.92 / 12.06 | 1.62 | 96.3 | 8.1 | 1.01x | 8 MB (truncated) |
| MockEngine, Content-Length | 20 MB | cap 16 MB | 16.80 / 20.29 | 7.50 | 104.4 | 16.1 | 1.01x | 16 MB (truncated) |
| MockEngine, Content-Length | 50 MB | no plugin | 23.13 / 51.01 | - | 238.9 | - | - | - |
| MockEngine, Content-Length | 50 MB | cap 256 KB | 24.13 / 32.06 | 1.01 | 239.1 | 0.3 | 1.03x | 256 KB (truncated) |
| MockEngine, Content-Length | 50 MB | cap 8 MB | 26.57 / 46.51 | 3.45 | 246.9 | 8.1 | 1.01x | 8 MB (truncated) |
| MockEngine, Content-Length | 50 MB | cap 16 MB | 36.73 / 44.05 | 13.60 | 255.0 | 16.1 | 1.01x | 16 MB (truncated) |
| MockEngine, no length | 256 KB | no plugin | 0.12 / 0.20 | - | 0.5 | - | - | - |
| MockEngine, no length | 256 KB | cap 256 KB | 0.17 / 0.22 | 0.05 | 1.0 | 0.5 | 2.06x | 262131 B |
| MockEngine, no length | 256 KB | cap 8 MB | 0.17 / 0.26 | 0.05 | 1.0 | 0.5 | 2.06x | 262131 B |
| MockEngine, no length | 256 KB | cap 16 MB | 0.17 / 0.26 | 0.05 | 1.0 | 0.5 | 2.06x | 262131 B |
| MockEngine, no length | 1 MB | no plugin | 0.36 / 0.97 | - | 2.0 | - | - | - |
| MockEngine, no length | 1 MB | cap 256 KB | 0.42 / 0.99 | 0.06 | 2.3 | 0.3 | 1.07x | 256 KB (truncated) |
| MockEngine, no length | 1 MB | cap 8 MB | 0.57 / 1.77 | 0.20 | 5.0 | 3.0 | 3.02x | 1048607 B |
| MockEngine, no length | 1 MB | cap 16 MB | 0.57 / 2.20 | 0.21 | 5.0 | 3.0 | 3.02x | 1048607 B |
| MockEngine, no length | 5 MB | no plugin | 1.42 / 3.36 | - | 12.9 | - | - | - |
| MockEngine, no length | 5 MB | cap 256 KB | 1.43 / 2.55 | 0.01 | 13.2 | 0.3 | 1.07x | 256 KB (truncated) |
| MockEngine, no length | 5 MB | cap 8 MB | 3.59 / 6.44 | 2.17 | 26.0 | 13.0 | 2.61x | 5242847 B |
| MockEngine, no length | 5 MB | cap 16 MB | 2.71 / 7.55 | 1.29 | 26.0 | 13.0 | 2.61x | 5242847 B |
| MockEngine, no length | 20 MB | no plugin | 7.73 / 10.43 | - | 88.3 | - | - | - |
| MockEngine, no length | 20 MB | cap 256 KB | 7.52 / 11.58 | -0.21 | 88.5 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, no length | 20 MB | cap 8 MB | 10.28 / 15.42 | 2.56 | 96.3 | 8.1 | 1.01x | 8 MB (truncated) |
| MockEngine, no length | 20 MB | cap 16 MB | 18.69 / 21.32 | 10.97 | 104.4 | 16.1 | 1.01x | 16 MB (truncated) |
| MockEngine, no length | 50 MB | no plugin | 23.23 / 27.86 | - | 238.9 | - | - | - |
| MockEngine, no length | 50 MB | cap 256 KB | 26.49 / 33.18 | 3.26 | 239.1 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, no length | 50 MB | cap 8 MB | 25.50 / 35.59 | 2.28 | 246.9 | 8.1 | 1.01x | 8 MB (truncated) |
| MockEngine, no length | 50 MB | cap 16 MB | 38.32 / 41.04 | 15.10 | 255.0 | 16.1 | 1.01x | 16 MB (truncated) |
| socket, Content-Length | 256 KB | no plugin | 0.61 / 1.51 | - | 0.6 | - | - | - |
| socket, Content-Length | 256 KB | cap 256 KB | 0.67 / 1.27 | 0.06 | 0.8 | 0.3 | 1.00x | 262131 B |
| socket, Content-Length | 256 KB | cap 8 MB | 0.64 / 1.00 | 0.03 | 0.8 | 0.3 | 1.01x | 262131 B |
| socket, Content-Length | 256 KB | cap 16 MB | 0.62 / 0.94 | 0.01 | 0.8 | 0.3 | 1.01x | 262131 B |
| socket, Content-Length | 1 MB | no plugin | 1.10 / 1.94 | - | 2.1 | - | - | - |
| socket, Content-Length | 1 MB | cap 256 KB | 0.94 / 1.61 | -0.15 | 2.3 | 0.2 | 0.95x | 256 KB (truncated) |
| socket, Content-Length | 1 MB | cap 8 MB | 1.09 / 1.84 | -0.01 | 3.1 | 1.0 | 1.00x | 1048607 B |
| socket, Content-Length | 1 MB | cap 16 MB | 1.10 / 1.67 | 0.01 | 3.1 | 1.0 | 1.01x | 1048607 B |
| socket, Content-Length | 5 MB | no plugin | 3.97 / 5.61 | - | 13.2 | - | - | - |
| socket, Content-Length | 5 MB | cap 256 KB | 3.55 / 9.41 | -0.42 | 13.4 | 0.2 | 0.70x | 256 KB (truncated) |
| socket, Content-Length | 5 MB | cap 8 MB | 4.54 / 7.31 | 0.57 | 19.1 | 6.0 | 1.19x | 5242847 B |
| socket, Content-Length | 5 MB | cap 16 MB | 4.45 / 5.98 | 0.48 | 19.1 | 6.0 | 1.19x | 5242847 B |
| socket, Content-Length | 20 MB | no plugin | 18.24 / 20.61 | - | 89.2 | - | - | - |
| socket, Content-Length | 20 MB | cap 256 KB | 16.11 / 18.91 | -2.13 | 89.1 | -0.1 | -0.53x | 256 KB (truncated) |
| socket, Content-Length | 20 MB | cap 8 MB | 15.68 / 18.28 | -2.56 | 96.9 | 7.7 | 0.96x | 8 MB (truncated) |
| socket, Content-Length | 20 MB | cap 16 MB | 16.86 / 19.65 | -1.39 | 105.0 | 15.7 | 0.98x | 16 MB (truncated) |
| socket, Content-Length | 50 MB | no plugin | 44.92 / 62.65 | - | 241.2 | - | - | - |
| socket, Content-Length | 50 MB | cap 256 KB | 40.07 / 44.04 | -4.85 | 240.5 | -0.7 | -2.81x | 256 KB (truncated) |
| socket, Content-Length | 50 MB | cap 8 MB | 39.50 / 41.83 | -5.42 | 248.3 | 7.1 | 0.89x | 8 MB (truncated) |
| socket, Content-Length | 50 MB | cap 16 MB | 39.59 / 45.03 | -5.33 | 256.4 | 15.2 | 0.95x | 16 MB (truncated) |
| socket, chunked | 256 KB | no plugin | 0.57 / 1.21 | - | 0.8 | - | - | - |
| socket, chunked | 256 KB | cap 256 KB | 0.58 / 1.06 | 0.01 | 1.4 | 0.5 | 2.10x | 262131 B |
| socket, chunked | 256 KB | cap 8 MB | 0.57 / 1.03 | 0.00 | 1.4 | 0.5 | 2.11x | 262131 B |
| socket, chunked | 256 KB | cap 16 MB | 0.63 / 1.02 | 0.06 | 1.4 | 0.5 | 2.17x | 262131 B |
| socket, chunked | 1 MB | no plugin | 1.24 / 2.29 | - | 3.2 | - | - | - |
| socket, chunked | 1 MB | cap 256 KB | 1.19 / 2.13 | -0.04 | 3.4 | 0.3 | 1.05x | 256 KB (truncated) |
| socket, chunked | 1 MB | cap 8 MB | 1.28 / 2.48 | 0.04 | 7.9 | 4.8 | 4.76x | 1048607 B |
| socket, chunked | 1 MB | cap 16 MB | 1.28 / 2.76 | 0.05 | 7.9 | 4.8 | 4.76x | 1048607 B |
| socket, chunked | 5 MB | no plugin | 5.89 / 8.84 | - | 18.6 | - | - | - |
| socket, chunked | 5 MB | cap 256 KB | 4.89 / 6.27 | -1.00 | 18.8 | 0.2 | 0.68x | 256 KB (truncated) |
| socket, chunked | 5 MB | cap 8 MB | 5.68 / 8.15 | -0.21 | 39.3 | 20.7 | 4.14x | 5242847 B |
| socket, chunked | 5 MB | cap 16 MB | 5.86 / 7.06 | -0.03 | 39.3 | 20.7 | 4.14x | 5242847 B |
| socket, chunked | 20 MB | no plugin | 24.55 / 27.73 | - | 111.0 | - | - | - |
| socket, chunked | 20 MB | cap 256 KB | 19.42 / 21.90 | -5.13 | 110.8 | -0.3 | -1.12x | 256 KB (truncated) |
| socket, chunked | 20 MB | cap 8 MB | 19.41 / 23.06 | -5.14 | 126.2 | 15.2 | 1.90x | 8 MB (truncated) |
| socket, chunked | 20 MB | cap 16 MB | 20.90 / 26.78 | -3.65 | 142.3 | 31.3 | 1.96x | 16 MB (truncated) |
| socket, chunked | 50 MB | no plugin | 62.72 / 88.06 | - | 295.9 | - | - | - |
| socket, chunked | 50 MB | cap 256 KB | 51.63 / 62.34 | -11.09 | 294.6 | -1.3 | -5.12x | 256 KB (truncated) |
| socket, chunked | 50 MB | cap 8 MB | 52.82 / 57.24 | -9.90 | 310.2 | 14.2 | 1.78x | 8 MB (truncated) |
| socket, chunked | 50 MB | cap 16 MB | 53.12 / 63.86 | -9.59 | 326.2 | 30.3 | 1.89x | 16 MB (truncated) |
| socket, chunked, streamed (execute {}) | 256 KB | no plugin | 0.30 / 0.66 | - | 0.3 | - | - | - |
| socket, chunked, streamed (execute {}) | 256 KB | cap 256 KB | 0.33 / 0.71 | 0.04 | 0.9 | 0.6 | 2.26x | 262131 B |
| socket, chunked, streamed (execute {}) | 256 KB | cap 8 MB | 0.34 / 0.78 | 0.04 | 0.9 | 0.5 | 2.17x | 262131 B |
| socket, chunked, streamed (execute {}) | 256 KB | cap 16 MB | 0.32 / 0.70 | 0.02 | 0.9 | 0.5 | 2.20x | 262131 B |
| socket, chunked, streamed (execute {}) | 1 MB | no plugin | 0.70 / 1.14 | - | 1.2 | - | - | - |
| socket, chunked, streamed (execute {}) | 1 MB | cap 256 KB | 0.73 / 1.32 | 0.03 | 1.5 | 0.3 | 1.27x | 256 KB (truncated) |
| socket, chunked, streamed (execute {}) | 1 MB | cap 8 MB | 1.09 / 2.04 | 0.39 | 5.8 | 4.7 | 4.66x | 1048607 B |
| socket, chunked, streamed (execute {}) | 1 MB | cap 16 MB | 1.41 / 2.16 | 0.71 | 5.9 | 4.8 | 4.76x | 1048607 B |
| socket, chunked, streamed (execute {}) | 5 MB | no plugin | 2.67 / 3.86 | - | 5.6 | - | - | - |
| socket, chunked, streamed (execute {}) | 5 MB | cap 256 KB | 2.74 / 3.43 | 0.07 | 5.9 | 0.3 | 1.22x | 256 KB (truncated) |
| socket, chunked, streamed (execute {}) | 5 MB | cap 8 MB | 3.49 / 5.40 | 0.82 | 26.2 | 20.6 | 4.12x | 5242847 B |
| socket, chunked, streamed (execute {}) | 5 MB | cap 16 MB | 2.92 / 4.10 | 0.25 | 26.2 | 20.6 | 4.12x | 5242847 B |
| socket, chunked, streamed (execute {}) | 20 MB | no plugin | 8.97 / 9.41 | - | 22.1 | - | - | - |
| socket, chunked, streamed (execute {}) | 20 MB | cap 256 KB | 9.32 / 11.20 | 0.35 | 22.4 | 0.3 | 1.29x | 256 KB (truncated) |
| socket, chunked, streamed (execute {}) | 20 MB | cap 8 MB | 9.41 / 10.79 | 0.44 | 37.7 | 15.6 | 1.96x | 8 MB (truncated) |
| socket, chunked, streamed (execute {}) | 20 MB | cap 16 MB | 9.48 / 11.40 | 0.51 | 53.8 | 31.7 | 1.98x | 16 MB (truncated) |
| socket, chunked, streamed (execute {}) | 50 MB | no plugin | 22.43 / 23.18 | - | 55.1 | - | - | - |
| socket, chunked, streamed (execute {}) | 50 MB | cap 256 KB | 22.56 / 23.04 | 0.12 | 55.5 | 0.4 | 1.52x | 256 KB (truncated) |
| socket, chunked, streamed (execute {}) | 50 MB | cap 8 MB | 22.86 / 24.06 | 0.43 | 70.8 | 15.7 | 1.96x | 8 MB (truncated) |
| socket, chunked, streamed (execute {}) | 50 MB | cap 16 MB | 22.93 / 24.65 | 0.50 | 86.9 | 31.8 | 1.99x | 16 MB (truncated) |
| MockEngine, request body (POST ByteArray) | 256 KB | no plugin | 0.07 / 0.13 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 256 KB | cap 256 KB | 0.09 / 0.17 | 0.03 | 0.3 | 0.3 | 1.06x | 262131 B |
| MockEngine, request body (POST ByteArray) | 256 KB | cap 8 MB | 0.09 / 0.16 | 0.02 | 0.3 | 0.3 | 1.06x | 262131 B |
| MockEngine, request body (POST ByteArray) | 256 KB | cap 16 MB | 0.09 / 0.15 | 0.02 | 0.3 | 0.3 | 1.06x | 262131 B |
| MockEngine, request body (POST ByteArray) | 1 MB | no plugin | 0.05 / 0.11 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 1 MB | cap 256 KB | 0.06 / 0.10 | 0.02 | 0.3 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, request body (POST ByteArray) | 1 MB | cap 8 MB | 0.08 / 0.17 | 0.03 | 1.0 | 1.0 | 1.01x | 1048607 B |
| MockEngine, request body (POST ByteArray) | 1 MB | cap 16 MB | 0.08 / 0.34 | 0.03 | 1.0 | 1.0 | 1.01x | 1048607 B |
| MockEngine, request body (POST ByteArray) | 5 MB | no plugin | 0.05 / 0.09 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 5 MB | cap 256 KB | 0.06 / 0.11 | 0.02 | 0.3 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, request body (POST ByteArray) | 5 MB | cap 8 MB | 0.17 / 0.25 | 0.12 | 5.0 | 5.0 | 1.00x | 5242847 B |
| MockEngine, request body (POST ByteArray) | 5 MB | cap 16 MB | 0.18 / 0.57 | 0.14 | 5.0 | 5.0 | 1.00x | 5242847 B |
| MockEngine, request body (POST ByteArray) | 20 MB | no plugin | 0.06 / 0.17 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 20 MB | cap 256 KB | 0.09 / 0.18 | 0.03 | 0.3 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, request body (POST ByteArray) | 20 MB | cap 8 MB | 0.88 / 14.87 | 0.82 | 8.0 | 8.0 | 1.00x | 8 MB (truncated) |
| MockEngine, request body (POST ByteArray) | 20 MB | cap 16 MB | 2.05 / 18.58 | 1.99 | 16.0 | 16.0 | 1.00x | 16 MB (truncated) |
| MockEngine, request body (POST ByteArray) | 50 MB | no plugin | 0.07 / 0.11 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 50 MB | cap 256 KB | 0.10 / 0.15 | 0.03 | 0.3 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, request body (POST ByteArray) | 50 MB | cap 8 MB | 0.76 / 1.42 | 0.68 | 8.0 | 8.0 | 1.00x | 8 MB (truncated) |
| MockEngine, request body (POST ByteArray) | 50 MB | cap 16 MB | 1.53 / 4.64 | 1.45 | 16.0 | 16.0 | 1.00x | 16 MB (truncated) |

- Arms run interleaved in 4 rounds per cell, each arm with its own client; a new client every 20 requests bounds MockEngine's history.
- 'Δ alloc / cap' divides the extra allocation by the bytes the plugin may keep (min(cap, body)): a constant means the cost is bounded by the cap, not by the body.
- Allocation is summed over all live threads (JDK 17 has no total counter); engine and dispatcher threads live for the whole run.
