### App-perceived request time with a giant body (median / p95 ms; alloc = bytes allocated by all threads per request)

| transport | body | arm | median / p95 (ms) | Δ median vs no plugin (ms) | alloc (MB) | Δ alloc vs no plugin (MB) | Δ alloc / cap | captured |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MockEngine, Content-Length | 256 KB | no plugin | 0.32 / 0.79 | - | 0.5 | - | - | - |
| MockEngine, Content-Length | 256 KB | cap 256 KB | 0.41 / 0.71 | 0.10 | 0.8 | 0.3 | 1.03x | 262131 B |
| MockEngine, Content-Length | 256 KB | cap 8 MB | 0.43 / 0.77 | 0.11 | 0.8 | 0.3 | 1.03x | 262131 B |
| MockEngine, Content-Length | 256 KB | cap 16 MB | 0.37 / 0.66 | 0.06 | 0.8 | 0.3 | 1.03x | 262131 B |
| MockEngine, Content-Length | 1 MB | no plugin | 0.44 / 1.38 | - | 2.0 | - | - | - |
| MockEngine, Content-Length | 1 MB | cap 256 KB | 0.64 / 1.57 | 0.19 | 2.3 | 0.3 | 1.04x | 256 KB (truncated) |
| MockEngine, Content-Length | 1 MB | cap 8 MB | 0.57 / 1.41 | 0.12 | 3.0 | 1.0 | 1.01x | 1048607 B |
| MockEngine, Content-Length | 1 MB | cap 16 MB | 0.50 / 1.04 | 0.06 | 3.0 | 1.0 | 1.01x | 1048607 B |
| MockEngine, Content-Length | 5 MB | no plugin | 1.50 / 2.94 | - | 12.9 | - | - | - |
| MockEngine, Content-Length | 5 MB | cap 256 KB | 1.48 / 2.63 | -0.02 | 13.2 | 0.3 | 1.04x | 256 KB (truncated) |
| MockEngine, Content-Length | 5 MB | cap 8 MB | 1.92 / 4.17 | 0.42 | 18.9 | 5.9 | 1.19x | 5242847 B |
| MockEngine, Content-Length | 5 MB | cap 16 MB | 2.03 / 3.76 | 0.53 | 18.9 | 5.9 | 1.19x | 5242847 B |
| MockEngine, Content-Length | 20 MB | no plugin | 9.29 / 14.70 | - | 88.3 | - | - | - |
| MockEngine, Content-Length | 20 MB | cap 256 KB | 9.54 / 11.88 | 0.25 | 88.5 | 0.3 | 1.03x | 256 KB (truncated) |
| MockEngine, Content-Length | 20 MB | cap 8 MB | 9.45 / 14.39 | 0.16 | 96.3 | 8.1 | 1.01x | 8 MB (truncated) |
| MockEngine, Content-Length | 20 MB | cap 16 MB | 16.75 / 20.34 | 7.45 | 104.4 | 16.1 | 1.01x | 16 MB (truncated) |
| MockEngine, Content-Length | 50 MB | no plugin | 23.06 / 33.67 | - | 238.9 | - | - | - |
| MockEngine, Content-Length | 50 MB | cap 256 KB | 25.32 / 60.92 | 2.26 | 239.1 | 0.3 | 1.03x | 256 KB (truncated) |
| MockEngine, Content-Length | 50 MB | cap 8 MB | 26.41 / 73.78 | 3.35 | 246.9 | 8.1 | 1.01x | 8 MB (truncated) |
| MockEngine, Content-Length | 50 MB | cap 16 MB | 34.58 / 74.52 | 11.52 | 255.0 | 16.1 | 1.01x | 16 MB (truncated) |
| MockEngine, no length | 256 KB | no plugin | 0.12 / 0.22 | - | 0.5 | - | - | - |
| MockEngine, no length | 256 KB | cap 256 KB | 0.17 / 0.24 | 0.05 | 1.0 | 0.5 | 2.06x | 262131 B |
| MockEngine, no length | 256 KB | cap 8 MB | 0.18 / 0.27 | 0.06 | 1.0 | 0.5 | 2.06x | 262131 B |
| MockEngine, no length | 256 KB | cap 16 MB | 0.17 / 0.26 | 0.06 | 1.0 | 0.5 | 2.06x | 262131 B |
| MockEngine, no length | 1 MB | no plugin | 0.28 / 0.45 | - | 2.0 | - | - | - |
| MockEngine, no length | 1 MB | cap 256 KB | 0.33 / 0.49 | 0.05 | 2.3 | 0.3 | 1.07x | 256 KB (truncated) |
| MockEngine, no length | 1 MB | cap 8 MB | 0.44 / 1.36 | 0.16 | 5.0 | 3.0 | 3.02x | 1048607 B |
| MockEngine, no length | 1 MB | cap 16 MB | 0.42 / 1.27 | 0.14 | 5.0 | 3.0 | 3.02x | 1048607 B |
| MockEngine, no length | 5 MB | no plugin | 1.32 / 2.34 | - | 12.9 | - | - | - |
| MockEngine, no length | 5 MB | cap 256 KB | 1.35 / 3.01 | 0.03 | 13.2 | 0.3 | 1.07x | 256 KB (truncated) |
| MockEngine, no length | 5 MB | cap 8 MB | 2.18 / 4.11 | 0.86 | 26.0 | 13.0 | 2.61x | 5242847 B |
| MockEngine, no length | 5 MB | cap 16 MB | 2.50 / 4.69 | 1.18 | 26.0 | 13.0 | 2.61x | 5242847 B |
| MockEngine, no length | 20 MB | no plugin | 7.30 / 11.29 | - | 88.3 | - | - | - |
| MockEngine, no length | 20 MB | cap 256 KB | 7.62 / 10.96 | 0.32 | 88.5 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, no length | 20 MB | cap 8 MB | 10.87 / 14.50 | 3.57 | 96.3 | 8.1 | 1.01x | 8 MB (truncated) |
| MockEngine, no length | 20 MB | cap 16 MB | 19.68 / 29.87 | 12.39 | 104.4 | 16.1 | 1.01x | 16 MB (truncated) |
| MockEngine, no length | 50 MB | no plugin | 22.88 / 37.48 | - | 238.9 | - | - | - |
| MockEngine, no length | 50 MB | cap 256 KB | 27.16 / 32.97 | 4.29 | 239.1 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, no length | 50 MB | cap 8 MB | 37.06 / 75.35 | 14.19 | 246.9 | 8.1 | 1.01x | 8 MB (truncated) |
| MockEngine, no length | 50 MB | cap 16 MB | 37.94 / 50.77 | 15.07 | 255.0 | 16.1 | 1.01x | 16 MB (truncated) |
| socket, Content-Length | 256 KB | no plugin | 0.84 / 2.24 | - | 0.6 | - | - | - |
| socket, Content-Length | 256 KB | cap 256 KB | 0.85 / 1.47 | 0.01 | 0.8 | 0.3 | 1.01x | 262131 B |
| socket, Content-Length | 256 KB | cap 8 MB | 0.68 / 1.18 | -0.16 | 0.8 | 0.3 | 1.01x | 262131 B |
| socket, Content-Length | 256 KB | cap 16 MB | 0.77 / 1.38 | -0.07 | 0.8 | 0.3 | 1.01x | 262131 B |
| socket, Content-Length | 1 MB | no plugin | 1.02 / 1.75 | - | 2.1 | - | - | - |
| socket, Content-Length | 1 MB | cap 256 KB | 1.02 / 2.03 | -0.01 | 2.3 | 0.2 | 0.99x | 256 KB (truncated) |
| socket, Content-Length | 1 MB | cap 8 MB | 1.14 / 1.98 | 0.11 | 3.1 | 1.0 | 1.01x | 1048607 B |
| socket, Content-Length | 1 MB | cap 16 MB | 1.10 / 1.80 | 0.08 | 3.1 | 1.0 | 1.01x | 1048607 B |
| socket, Content-Length | 5 MB | no plugin | 4.20 / 5.12 | - | 13.2 | - | - | - |
| socket, Content-Length | 5 MB | cap 256 KB | 3.79 / 4.89 | -0.42 | 13.3 | 0.2 | 0.69x | 256 KB (truncated) |
| socket, Content-Length | 5 MB | cap 8 MB | 4.88 / 6.42 | 0.68 | 19.1 | 6.0 | 1.19x | 5242847 B |
| socket, Content-Length | 5 MB | cap 16 MB | 4.99 / 5.99 | 0.78 | 19.1 | 6.0 | 1.19x | 5242847 B |
| socket, Content-Length | 20 MB | no plugin | 18.95 / 22.80 | - | 89.2 | - | - | - |
| socket, Content-Length | 20 MB | cap 256 KB | 17.63 / 19.52 | -1.32 | 89.1 | -0.1 | -0.54x | 256 KB (truncated) |
| socket, Content-Length | 20 MB | cap 8 MB | 18.07 / 18.88 | -0.88 | 96.9 | 7.7 | 0.96x | 8 MB (truncated) |
| socket, Content-Length | 20 MB | cap 16 MB | 17.65 / 21.12 | -1.31 | 105.0 | 15.7 | 0.98x | 16 MB (truncated) |
| socket, Content-Length | 50 MB | no plugin | 49.06 / 49.94 | - | 241.3 | - | - | - |
| socket, Content-Length | 50 MB | cap 256 KB | 45.01 / 46.11 | -4.06 | 240.5 | -0.7 | -2.97x | 256 KB (truncated) |
| socket, Content-Length | 50 MB | cap 8 MB | 44.63 / 61.20 | -4.43 | 248.3 | 7.1 | 0.88x | 8 MB (truncated) |
| socket, Content-Length | 50 MB | cap 16 MB | 44.64 / 52.27 | -4.43 | 256.4 | 15.1 | 0.95x | 16 MB (truncated) |
| socket, chunked | 256 KB | no plugin | 0.50 / 0.90 | - | 0.8 | - | - | - |
| socket, chunked | 256 KB | cap 256 KB | 0.45 / 0.94 | -0.05 | 1.4 | 0.5 | 2.08x | 262131 B |
| socket, chunked | 256 KB | cap 8 MB | 0.45 / 1.03 | -0.05 | 1.4 | 0.5 | 2.10x | 262131 B |
| socket, chunked | 256 KB | cap 16 MB | 0.44 / 0.88 | -0.06 | 1.4 | 0.5 | 2.11x | 262131 B |
| socket, chunked | 1 MB | no plugin | 1.12 / 2.34 | - | 3.2 | - | - | - |
| socket, chunked | 1 MB | cap 256 KB | 1.08 / 2.35 | -0.04 | 3.5 | 0.3 | 1.03x | 256 KB (truncated) |
| socket, chunked | 1 MB | cap 8 MB | 1.18 / 2.55 | 0.07 | 7.9 | 4.7 | 4.75x | 1048607 B |
| socket, chunked | 1 MB | cap 16 MB | 1.26 / 2.63 | 0.14 | 7.9 | 4.8 | 4.75x | 1048607 B |
| socket, chunked | 5 MB | no plugin | 5.69 / 7.15 | - | 18.6 | - | - | - |
| socket, chunked | 5 MB | cap 256 KB | 4.99 / 6.38 | -0.69 | 18.8 | 0.2 | 0.61x | 256 KB (truncated) |
| socket, chunked | 5 MB | cap 8 MB | 5.95 / 6.74 | 0.26 | 39.3 | 20.7 | 4.14x | 5242847 B |
| socket, chunked | 5 MB | cap 16 MB | 5.81 / 6.87 | 0.13 | 39.3 | 20.7 | 4.14x | 5242847 B |
| socket, chunked | 20 MB | no plugin | 23.65 / 43.70 | - | 111.0 | - | - | - |
| socket, chunked | 20 MB | cap 256 KB | 20.04 / 28.27 | -3.61 | 110.7 | -0.3 | -1.04x | 256 KB (truncated) |
| socket, chunked | 20 MB | cap 8 MB | 20.24 / 21.96 | -3.40 | 126.2 | 15.2 | 1.90x | 8 MB (truncated) |
| socket, chunked | 20 MB | cap 16 MB | 21.86 / 35.21 | -1.78 | 142.4 | 31.4 | 1.96x | 16 MB (truncated) |
| socket, chunked | 50 MB | no plugin | 59.96 / 65.23 | - | 295.7 | - | - | - |
| socket, chunked | 50 MB | cap 256 KB | 50.01 / 67.02 | -9.94 | 294.6 | -1.1 | -4.49x | 256 KB (truncated) |
| socket, chunked | 50 MB | cap 8 MB | 50.37 / 55.23 | -9.59 | 310.2 | 14.4 | 1.80x | 8 MB (truncated) |
| socket, chunked | 50 MB | cap 16 MB | 51.65 / 57.02 | -8.31 | 326.2 | 30.5 | 1.91x | 16 MB (truncated) |
| socket, chunked, streamed (execute {}) | 256 KB | no plugin | 0.30 / 0.67 | - | 0.3 | - | - | - |
| socket, chunked, streamed (execute {}) | 256 KB | cap 256 KB | 0.34 / 0.66 | 0.04 | 0.9 | 0.5 | 2.14x | 262131 B |
| socket, chunked, streamed (execute {}) | 256 KB | cap 8 MB | 0.34 / 0.70 | 0.04 | 0.9 | 0.6 | 2.20x | 262131 B |
| socket, chunked, streamed (execute {}) | 256 KB | cap 16 MB | 0.33 / 0.73 | 0.03 | 0.9 | 0.6 | 2.23x | 262131 B |
| socket, chunked, streamed (execute {}) | 1 MB | no plugin | 0.68 / 1.11 | - | 1.2 | - | - | - |
| socket, chunked, streamed (execute {}) | 1 MB | cap 256 KB | 0.72 / 1.25 | 0.03 | 1.5 | 0.3 | 1.30x | 256 KB (truncated) |
| socket, chunked, streamed (execute {}) | 1 MB | cap 8 MB | 0.86 / 2.59 | 0.18 | 5.8 | 4.6 | 4.64x | 1048607 B |
| socket, chunked, streamed (execute {}) | 1 MB | cap 16 MB | 0.84 / 2.15 | 0.16 | 5.7 | 4.6 | 4.58x | 1048607 B |
| socket, chunked, streamed (execute {}) | 5 MB | no plugin | 3.12 / 5.03 | - | 5.6 | - | - | - |
| socket, chunked, streamed (execute {}) | 5 MB | cap 256 KB | 2.94 / 4.43 | -0.18 | 5.9 | 0.3 | 1.17x | 256 KB (truncated) |
| socket, chunked, streamed (execute {}) | 5 MB | cap 8 MB | 3.62 / 6.51 | 0.50 | 26.2 | 20.6 | 4.12x | 5242847 B |
| socket, chunked, streamed (execute {}) | 5 MB | cap 16 MB | 3.56 / 7.40 | 0.43 | 26.2 | 20.6 | 4.12x | 5242847 B |
| socket, chunked, streamed (execute {}) | 20 MB | no plugin | 8.84 / 9.80 | - | 22.1 | - | - | - |
| socket, chunked, streamed (execute {}) | 20 MB | cap 256 KB | 9.12 / 9.88 | 0.29 | 22.4 | 0.3 | 1.33x | 256 KB (truncated) |
| socket, chunked, streamed (execute {}) | 20 MB | cap 8 MB | 9.38 / 10.73 | 0.54 | 37.7 | 15.6 | 1.96x | 8 MB (truncated) |
| socket, chunked, streamed (execute {}) | 20 MB | cap 16 MB | 10.27 / 11.46 | 1.43 | 53.8 | 31.7 | 1.98x | 16 MB (truncated) |
| socket, chunked, streamed (execute {}) | 50 MB | no plugin | 21.65 / 22.15 | - | 55.1 | - | - | - |
| socket, chunked, streamed (execute {}) | 50 MB | cap 256 KB | 21.75 / 32.09 | 0.10 | 55.5 | 0.4 | 1.42x | 256 KB (truncated) |
| socket, chunked, streamed (execute {}) | 50 MB | cap 8 MB | 23.32 / 26.80 | 1.66 | 70.8 | 15.7 | 1.96x | 8 MB (truncated) |
| socket, chunked, streamed (execute {}) | 50 MB | cap 16 MB | 23.57 / 24.36 | 1.92 | 86.9 | 31.8 | 1.99x | 16 MB (truncated) |
| MockEngine, request body (POST ByteArray) | 256 KB | no plugin | 0.06 / 0.11 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 256 KB | cap 256 KB | 0.08 / 0.14 | 0.02 | 0.3 | 0.3 | 1.06x | 262131 B |
| MockEngine, request body (POST ByteArray) | 256 KB | cap 8 MB | 0.08 / 0.14 | 0.02 | 0.3 | 0.3 | 1.06x | 262131 B |
| MockEngine, request body (POST ByteArray) | 256 KB | cap 16 MB | 0.08 / 0.13 | 0.02 | 0.3 | 0.3 | 1.06x | 262131 B |
| MockEngine, request body (POST ByteArray) | 1 MB | no plugin | 0.05 / 0.17 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 1 MB | cap 256 KB | 0.10 / 0.26 | 0.05 | 0.3 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, request body (POST ByteArray) | 1 MB | cap 8 MB | 0.09 / 0.17 | 0.03 | 1.0 | 1.0 | 1.01x | 1048607 B |
| MockEngine, request body (POST ByteArray) | 1 MB | cap 16 MB | 0.10 / 0.40 | 0.04 | 1.0 | 1.0 | 1.01x | 1048607 B |
| MockEngine, request body (POST ByteArray) | 5 MB | no plugin | 0.05 / 0.11 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 5 MB | cap 256 KB | 0.08 / 0.13 | 0.03 | 0.3 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, request body (POST ByteArray) | 5 MB | cap 8 MB | 0.19 / 0.74 | 0.14 | 5.0 | 5.0 | 1.00x | 5242847 B |
| MockEngine, request body (POST ByteArray) | 5 MB | cap 16 MB | 0.20 / 0.75 | 0.15 | 5.0 | 5.0 | 1.00x | 5242847 B |
| MockEngine, request body (POST ByteArray) | 20 MB | no plugin | 0.05 / 0.13 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 20 MB | cap 256 KB | 0.09 / 0.15 | 0.03 | 0.3 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, request body (POST ByteArray) | 20 MB | cap 8 MB | 1.16 / 5.53 | 1.11 | 8.0 | 8.0 | 1.00x | 8 MB (truncated) |
| MockEngine, request body (POST ByteArray) | 20 MB | cap 16 MB | 2.79 / 23.28 | 2.74 | 16.0 | 16.0 | 1.00x | 16 MB (truncated) |
| MockEngine, request body (POST ByteArray) | 50 MB | no plugin | 0.07 / 0.18 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 50 MB | cap 256 KB | 0.18 / 3.37 | 0.11 | 0.3 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, request body (POST ByteArray) | 50 MB | cap 8 MB | 1.99 / 4.66 | 1.91 | 8.0 | 8.0 | 1.00x | 8 MB (truncated) |
| MockEngine, request body (POST ByteArray) | 50 MB | cap 16 MB | 7.35 / 17.29 | 7.27 | 16.0 | 16.0 | 1.00x | 16 MB (truncated) |

- Arms run interleaved in 4 rounds per cell, each arm with its own client; a new client every 20 requests bounds MockEngine's history.
- 'Δ alloc / cap' divides the extra allocation by the bytes the plugin may keep (min(cap, body)): a constant means the cost is bounded by the cap, not by the body.
- Allocation is summed over all live threads (JDK 17 has no total counter); engine and dispatcher threads live for the whole run.
