### App-perceived request time with a giant body (median / p95 ms; alloc = bytes allocated by all threads per request)

| transport | body | arm | median / p95 (ms) | Δ median vs no plugin (ms) | alloc (MB) | Δ alloc vs no plugin (MB) | Δ alloc / cap | captured |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MockEngine, Content-Length | 256 KB | no plugin | 0.34 / 0.92 | - | 0.5 | - | - | - |
| MockEngine, Content-Length | 256 KB | cap 256 KB | 0.43 / 0.95 | 0.09 | 0.8 | 0.3 | 1.03x | 262131 B |
| MockEngine, Content-Length | 256 KB | cap 8 MB | 0.54 / 1.19 | 0.20 | 0.8 | 0.3 | 1.03x | 262131 B |
| MockEngine, Content-Length | 256 KB | cap 16 MB | 0.45 / 0.84 | 0.11 | 0.8 | 0.3 | 1.03x | 262131 B |
| MockEngine, Content-Length | 1 MB | no plugin | 0.42 / 0.67 | - | 2.0 | - | - | - |
| MockEngine, Content-Length | 1 MB | cap 256 KB | 0.48 / 0.80 | 0.05 | 2.3 | 0.3 | 1.03x | 256 KB (truncated) |
| MockEngine, Content-Length | 1 MB | cap 8 MB | 0.49 / 1.49 | 0.06 | 3.0 | 1.0 | 1.01x | 1048607 B |
| MockEngine, Content-Length | 1 MB | cap 16 MB | 0.55 / 1.09 | 0.12 | 3.0 | 1.0 | 1.01x | 1048607 B |
| MockEngine, Content-Length | 5 MB | no plugin | 1.72 / 5.28 | - | 12.9 | - | - | - |
| MockEngine, Content-Length | 5 MB | cap 256 KB | 1.45 / 2.57 | -0.27 | 13.2 | 0.3 | 1.03x | 256 KB (truncated) |
| MockEngine, Content-Length | 5 MB | cap 8 MB | 1.92 / 4.28 | 0.21 | 18.9 | 5.9 | 1.19x | 5242847 B |
| MockEngine, Content-Length | 5 MB | cap 16 MB | 2.02 / 4.79 | 0.30 | 18.9 | 5.9 | 1.19x | 5242847 B |
| MockEngine, Content-Length | 20 MB | no plugin | 9.96 / 13.53 | - | 88.3 | - | - | - |
| MockEngine, Content-Length | 20 MB | cap 256 KB | 9.79 / 12.16 | -0.16 | 88.5 | 0.3 | 1.03x | 256 KB (truncated) |
| MockEngine, Content-Length | 20 MB | cap 8 MB | 12.44 / 17.08 | 2.49 | 96.3 | 8.1 | 1.01x | 8 MB (truncated) |
| MockEngine, Content-Length | 20 MB | cap 16 MB | 20.34 / 36.62 | 10.38 | 104.4 | 16.1 | 1.01x | 16 MB (truncated) |
| MockEngine, Content-Length | 50 MB | no plugin | 26.04 / 37.57 | - | 238.9 | - | - | - |
| MockEngine, Content-Length | 50 MB | cap 256 KB | 23.40 / 130.12 | -2.64 | 239.1 | 0.3 | 1.03x | 256 KB (truncated) |
| MockEngine, Content-Length | 50 MB | cap 8 MB | 27.69 / 36.66 | 1.65 | 246.9 | 8.1 | 1.01x | 8 MB (truncated) |
| MockEngine, Content-Length | 50 MB | cap 16 MB | 38.75 / 80.64 | 12.71 | 255.0 | 16.1 | 1.01x | 16 MB (truncated) |
| MockEngine, no length | 256 KB | no plugin | 0.12 / 0.18 | - | 0.5 | - | - | - |
| MockEngine, no length | 256 KB | cap 256 KB | 0.16 / 0.24 | 0.04 | 1.0 | 0.5 | 2.06x | 262131 B |
| MockEngine, no length | 256 KB | cap 8 MB | 0.16 / 0.24 | 0.04 | 1.0 | 0.5 | 2.06x | 262131 B |
| MockEngine, no length | 256 KB | cap 16 MB | 0.16 / 0.25 | 0.04 | 1.0 | 0.5 | 2.06x | 262131 B |
| MockEngine, no length | 1 MB | no plugin | 0.31 / 0.51 | - | 2.0 | - | - | - |
| MockEngine, no length | 1 MB | cap 256 KB | 0.33 / 1.00 | 0.02 | 2.3 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, no length | 1 MB | cap 8 MB | 0.43 / 1.45 | 0.12 | 5.0 | 3.0 | 3.02x | 1048607 B |
| MockEngine, no length | 1 MB | cap 16 MB | 0.44 / 1.79 | 0.13 | 5.0 | 3.0 | 3.02x | 1048607 B |
| MockEngine, no length | 5 MB | no plugin | 1.23 / 2.33 | - | 12.9 | - | - | - |
| MockEngine, no length | 5 MB | cap 256 KB | 1.29 / 4.43 | 0.06 | 13.2 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, no length | 5 MB | cap 8 MB | 2.61 / 6.23 | 1.38 | 26.0 | 13.0 | 2.61x | 5242847 B |
| MockEngine, no length | 5 MB | cap 16 MB | 2.11 / 3.34 | 0.88 | 26.0 | 13.0 | 2.61x | 5242847 B |
| MockEngine, no length | 20 MB | no plugin | 7.71 / 10.96 | - | 88.3 | - | - | - |
| MockEngine, no length | 20 MB | cap 256 KB | 9.22 / 15.01 | 1.51 | 88.5 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, no length | 20 MB | cap 8 MB | 10.59 / 15.27 | 2.87 | 96.3 | 8.1 | 1.01x | 8 MB (truncated) |
| MockEngine, no length | 20 MB | cap 16 MB | 19.16 / 27.23 | 11.45 | 104.4 | 16.1 | 1.01x | 16 MB (truncated) |
| MockEngine, no length | 50 MB | no plugin | 25.34 / 33.75 | - | 238.9 | - | - | - |
| MockEngine, no length | 50 MB | cap 256 KB | 25.82 / 30.77 | 0.48 | 239.1 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, no length | 50 MB | cap 8 MB | 28.38 / 35.20 | 3.05 | 246.9 | 8.1 | 1.01x | 8 MB (truncated) |
| MockEngine, no length | 50 MB | cap 16 MB | 39.25 / 44.20 | 13.91 | 255.0 | 16.1 | 1.01x | 16 MB (truncated) |
| socket, Content-Length | 256 KB | no plugin | 0.67 / 1.73 | - | 0.6 | - | - | - |
| socket, Content-Length | 256 KB | cap 256 KB | 0.70 / 1.07 | 0.03 | 0.8 | 0.3 | 1.03x | 262131 B |
| socket, Content-Length | 256 KB | cap 8 MB | 0.65 / 1.05 | -0.02 | 0.8 | 0.3 | 1.01x | 262131 B |
| socket, Content-Length | 256 KB | cap 16 MB | 0.63 / 1.14 | -0.04 | 0.8 | 0.3 | 1.01x | 262131 B |
| socket, Content-Length | 1 MB | no plugin | 1.04 / 1.79 | - | 2.1 | - | - | - |
| socket, Content-Length | 1 MB | cap 256 KB | 1.02 / 1.63 | -0.02 | 2.4 | 0.3 | 1.04x | 256 KB (truncated) |
| socket, Content-Length | 1 MB | cap 8 MB | 1.13 / 2.63 | 0.10 | 3.1 | 1.0 | 1.01x | 1048607 B |
| socket, Content-Length | 1 MB | cap 16 MB | 1.14 / 2.81 | 0.10 | 3.1 | 1.0 | 1.01x | 1048607 B |
| socket, Content-Length | 5 MB | no plugin | 4.01 / 5.44 | - | 13.2 | - | - | - |
| socket, Content-Length | 5 MB | cap 256 KB | 4.01 / 6.47 | 0.01 | 13.4 | 0.2 | 0.97x | 256 KB (truncated) |
| socket, Content-Length | 5 MB | cap 8 MB | 4.60 / 6.39 | 0.60 | 19.1 | 5.9 | 1.19x | 5242847 B |
| socket, Content-Length | 5 MB | cap 16 MB | 4.59 / 6.05 | 0.59 | 19.1 | 5.9 | 1.19x | 5242847 B |
| socket, Content-Length | 20 MB | no plugin | 19.22 / 23.76 | - | 89.2 | - | - | - |
| socket, Content-Length | 20 MB | cap 256 KB | 18.81 / 22.57 | -0.41 | 89.5 | 0.3 | 1.29x | 256 KB (truncated) |
| socket, Content-Length | 20 MB | cap 8 MB | 18.51 / 20.64 | -0.71 | 97.3 | 8.1 | 1.02x | 8 MB (truncated) |
| socket, Content-Length | 20 MB | cap 16 MB | 19.21 / 21.20 | -0.01 | 105.4 | 16.2 | 1.01x | 16 MB (truncated) |
| socket, Content-Length | 50 MB | no plugin | 47.75 / 74.92 | - | 241.2 | - | - | - |
| socket, Content-Length | 50 MB | cap 256 KB | 47.14 / 50.30 | -0.61 | 241.6 | 0.4 | 1.59x | 256 KB (truncated) |
| socket, Content-Length | 50 MB | cap 8 MB | 46.66 / 49.30 | -1.09 | 249.4 | 8.1 | 1.02x | 8 MB (truncated) |
| socket, Content-Length | 50 MB | cap 16 MB | 48.81 / 80.00 | 1.06 | 257.5 | 16.3 | 1.02x | 16 MB (truncated) |
| socket, chunked | 256 KB | no plugin | 0.47 / 1.01 | - | 0.8 | - | - | - |
| socket, chunked | 256 KB | cap 256 KB | 0.48 / 0.98 | 0.01 | 1.6 | 0.8 | 3.00x | 262131 B |
| socket, chunked | 256 KB | cap 8 MB | 0.50 / 0.96 | 0.02 | 1.6 | 0.7 | 2.95x | 262131 B |
| socket, chunked | 256 KB | cap 16 MB | 0.60 / 0.99 | 0.12 | 1.6 | 0.7 | 2.92x | 262131 B |
| socket, chunked | 1 MB | no plugin | 1.36 / 2.53 | - | 3.2 | - | - | - |
| socket, chunked | 1 MB | cap 256 KB | 1.25 / 2.33 | -0.11 | 3.7 | 0.5 | 1.97x | 256 KB (truncated) |
| socket, chunked | 1 MB | cap 8 MB | 1.44 / 2.84 | 0.08 | 8.2 | 5.0 | 4.99x | 1048607 B |
| socket, chunked | 1 MB | cap 16 MB | 1.43 / 2.92 | 0.07 | 8.2 | 5.0 | 4.99x | 1048607 B |
| socket, chunked | 5 MB | no plugin | 5.67 / 6.88 | - | 18.6 | - | - | - |
| socket, chunked | 5 MB | cap 256 KB | 5.63 / 6.92 | -0.04 | 19.1 | 0.5 | 2.00x | 256 KB (truncated) |
| socket, chunked | 5 MB | cap 8 MB | 5.95 / 7.52 | 0.28 | 39.7 | 21.1 | 4.21x | 5242847 B |
| socket, chunked | 5 MB | cap 16 MB | 5.93 / 7.12 | 0.26 | 39.7 | 21.1 | 4.21x | 5242847 B |
| socket, chunked | 20 MB | no plugin | 23.46 / 26.67 | - | 111.0 | - | - | - |
| socket, chunked | 20 MB | cap 256 KB | 23.45 / 26.15 | -0.01 | 111.5 | 0.5 | 2.13x | 256 KB (truncated) |
| socket, chunked | 20 MB | cap 8 MB | 23.26 / 26.84 | -0.19 | 127.1 | 16.1 | 2.01x | 8 MB (truncated) |
| socket, chunked | 20 MB | cap 16 MB | 24.85 / 29.70 | 1.39 | 143.2 | 32.2 | 2.01x | 16 MB (truncated) |
| socket, chunked | 50 MB | no plugin | 60.75 / 71.87 | - | 295.8 | - | - | - |
| socket, chunked | 50 MB | cap 256 KB | 60.88 / 76.84 | 0.13 | 296.3 | 0.5 | 2.19x | 256 KB (truncated) |
| socket, chunked | 50 MB | cap 8 MB | 62.07 / 95.11 | 1.32 | 311.9 | 16.1 | 2.01x | 8 MB (truncated) |
| socket, chunked | 50 MB | cap 16 MB | 63.94 / 77.70 | 3.19 | 328.1 | 32.3 | 2.02x | 16 MB (truncated) |
| socket, chunked, streamed (execute {}) | 256 KB | no plugin | 0.30 / 0.76 | - | 0.3 | - | - | - |
| socket, chunked, streamed (execute {}) | 256 KB | cap 256 KB | 0.32 / 0.69 | 0.02 | 0.9 | 0.6 | 2.32x | 262131 B |
| socket, chunked, streamed (execute {}) | 256 KB | cap 8 MB | 0.32 / 0.61 | 0.02 | 0.9 | 0.6 | 2.44x | 262131 B |
| socket, chunked, streamed (execute {}) | 256 KB | cap 16 MB | 0.32 / 0.76 | 0.02 | 0.9 | 0.6 | 2.44x | 262131 B |
| socket, chunked, streamed (execute {}) | 1 MB | no plugin | 0.68 / 1.23 | - | 1.2 | - | - | - |
| socket, chunked, streamed (execute {}) | 1 MB | cap 256 KB | 0.73 / 1.11 | 0.04 | 1.5 | 0.3 | 1.35x | 256 KB (truncated) |
| socket, chunked, streamed (execute {}) | 1 MB | cap 8 MB | 0.87 / 1.95 | 0.18 | 5.8 | 4.6 | 4.65x | 1048607 B |
| socket, chunked, streamed (execute {}) | 1 MB | cap 16 MB | 0.82 / 1.85 | 0.14 | 5.9 | 4.7 | 4.71x | 1048607 B |
| socket, chunked, streamed (execute {}) | 5 MB | no plugin | 2.58 / 3.75 | - | 5.6 | - | - | - |
| socket, chunked, streamed (execute {}) | 5 MB | cap 256 KB | 2.51 / 3.09 | -0.07 | 5.9 | 0.3 | 1.38x | 256 KB (truncated) |
| socket, chunked, streamed (execute {}) | 5 MB | cap 8 MB | 2.98 / 4.92 | 0.39 | 26.3 | 20.7 | 4.14x | 5242847 B |
| socket, chunked, streamed (execute {}) | 5 MB | cap 16 MB | 2.83 / 4.17 | 0.25 | 26.3 | 20.7 | 4.14x | 5242847 B |
| socket, chunked, streamed (execute {}) | 20 MB | no plugin | 8.71 / 9.13 | - | 22.1 | - | - | - |
| socket, chunked, streamed (execute {}) | 20 MB | cap 256 KB | 8.73 / 9.56 | 0.03 | 22.5 | 0.4 | 1.55x | 256 KB (truncated) |
| socket, chunked, streamed (execute {}) | 20 MB | cap 8 MB | 9.03 / 13.38 | 0.32 | 37.8 | 15.7 | 1.97x | 8 MB (truncated) |
| socket, chunked, streamed (execute {}) | 20 MB | cap 16 MB | 9.36 / 10.05 | 0.66 | 54.0 | 31.9 | 1.99x | 16 MB (truncated) |
| socket, chunked, streamed (execute {}) | 50 MB | no plugin | 21.48 / 21.94 | - | 55.1 | - | - | - |
| socket, chunked, streamed (execute {}) | 50 MB | cap 256 KB | 21.59 / 23.52 | 0.11 | 55.4 | 0.3 | 1.11x | 256 KB (truncated) |
| socket, chunked, streamed (execute {}) | 50 MB | cap 8 MB | 21.81 / 25.96 | 0.33 | 70.9 | 15.7 | 1.97x | 8 MB (truncated) |
| socket, chunked, streamed (execute {}) | 50 MB | cap 16 MB | 22.32 / 26.44 | 0.84 | 86.9 | 31.8 | 1.99x | 16 MB (truncated) |
| MockEngine, request body (POST ByteArray) | 256 KB | no plugin | 0.07 / 0.14 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 256 KB | cap 256 KB | 0.11 / 0.20 | 0.04 | 0.3 | 0.3 | 1.06x | 262131 B |
| MockEngine, request body (POST ByteArray) | 256 KB | cap 8 MB | 0.11 / 0.27 | 0.04 | 0.3 | 0.3 | 1.06x | 262131 B |
| MockEngine, request body (POST ByteArray) | 256 KB | cap 16 MB | 0.11 / 0.23 | 0.04 | 0.3 | 0.3 | 1.06x | 262131 B |
| MockEngine, request body (POST ByteArray) | 1 MB | no plugin | 0.07 / 0.15 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 1 MB | cap 256 KB | 0.09 / 0.14 | 0.02 | 0.3 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, request body (POST ByteArray) | 1 MB | cap 8 MB | 0.12 / 0.27 | 0.05 | 1.0 | 1.0 | 1.01x | 1048607 B |
| MockEngine, request body (POST ByteArray) | 1 MB | cap 16 MB | 0.11 / 0.20 | 0.04 | 1.0 | 1.0 | 1.01x | 1048607 B |
| MockEngine, request body (POST ByteArray) | 5 MB | no plugin | 0.06 / 0.12 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 5 MB | cap 256 KB | 0.09 / 0.27 | 0.03 | 0.3 | 0.3 | 1.05x | 256 KB (truncated) |
| MockEngine, request body (POST ByteArray) | 5 MB | cap 8 MB | 0.24 / 0.66 | 0.17 | 5.0 | 5.0 | 1.00x | 5242847 B |
| MockEngine, request body (POST ByteArray) | 5 MB | cap 16 MB | 0.20 / 0.53 | 0.14 | 5.0 | 5.0 | 1.00x | 5242847 B |
| MockEngine, request body (POST ByteArray) | 20 MB | no plugin | 0.07 / 0.15 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 20 MB | cap 256 KB | 0.08 / 0.29 | 0.00 | 0.3 | 0.3 | 1.05x | 256 KB (truncated) |
| MockEngine, request body (POST ByteArray) | 20 MB | cap 8 MB | 0.36 / 0.94 | 0.29 | 8.0 | 8.0 | 1.00x | 8 MB (truncated) |
| MockEngine, request body (POST ByteArray) | 20 MB | cap 16 MB | 0.89 / 1.99 | 0.82 | 16.0 | 16.0 | 1.00x | 16 MB (truncated) |
| MockEngine, request body (POST ByteArray) | 50 MB | no plugin | 0.06 / 0.07 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 50 MB | cap 256 KB | 0.07 / 0.10 | 0.02 | 0.3 | 0.3 | 1.05x | 256 KB (truncated) |
| MockEngine, request body (POST ByteArray) | 50 MB | cap 8 MB | 0.75 / 0.85 | 0.70 | 8.0 | 8.0 | 1.00x | 8 MB (truncated) |
| MockEngine, request body (POST ByteArray) | 50 MB | cap 16 MB | 1.48 / 1.64 | 1.42 | 16.0 | 16.0 | 1.00x | 16 MB (truncated) |

- Arms run interleaved in 4 rounds per cell, each arm with its own client; a new client every 20 requests bounds MockEngine's history.
- 'Δ alloc / cap' divides the extra allocation by the bytes the plugin may keep (min(cap, body)): a constant means the cost is bounded by the cap, not by the body.
- Allocation is summed over all live threads (JDK 17 has no total counter); engine and dispatcher threads live for the whole run.
