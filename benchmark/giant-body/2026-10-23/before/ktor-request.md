### App-perceived request time with a giant body (median / p95 ms; alloc = bytes allocated by all threads per request)

| transport | body | arm | median / p95 (ms) | Δ median vs no plugin (ms) | alloc (MB) | Δ alloc vs no plugin (MB) | Δ alloc / cap | captured |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MockEngine, Content-Length | 256 KB | no plugin | 0.34 / 0.75 | - | 0.5 | - | - | - |
| MockEngine, Content-Length | 256 KB | cap 256 KB | 0.44 / 0.88 | 0.11 | 1.3 | 0.8 | 3.03x | 262131 B |
| MockEngine, Content-Length | 256 KB | cap 8 MB | 0.44 / 0.86 | 0.11 | 1.5 | 1.0 | 4.03x | 262131 B |
| MockEngine, Content-Length | 256 KB | cap 16 MB | 0.49 / 1.21 | 0.15 | 1.5 | 1.0 | 4.03x | 262131 B |
| MockEngine, Content-Length | 1 MB | no plugin | 0.47 / 0.99 | - | 2.0 | - | - | - |
| MockEngine, Content-Length | 1 MB | cap 256 KB | 0.68 / 1.57 | 0.21 | 2.3 | 0.3 | 1.07x | 256 KB (truncated) |
| MockEngine, Content-Length | 1 MB | cap 8 MB | 0.85 / 2.59 | 0.38 | 6.0 | 4.0 | 4.01x | 1048607 B |
| MockEngine, Content-Length | 1 MB | cap 16 MB | 0.66 / 2.07 | 0.19 | 6.0 | 4.0 | 4.01x | 1048607 B |
| MockEngine, Content-Length | 5 MB | no plugin | 1.69 / 2.65 | - | 12.9 | - | - | - |
| MockEngine, Content-Length | 5 MB | cap 256 KB | 1.52 / 2.64 | -0.17 | 13.2 | 0.3 | 1.07x | 256 KB (truncated) |
| MockEngine, Content-Length | 5 MB | cap 8 MB | 2.53 / 5.76 | 0.84 | 31.9 | 18.9 | 3.79x | 5242847 B |
| MockEngine, Content-Length | 5 MB | cap 16 MB | 2.63 / 4.80 | 0.94 | 33.9 | 20.9 | 4.19x | 5242847 B |
| MockEngine, Content-Length | 20 MB | no plugin | 11.04 / 15.05 | - | 88.3 | - | - | - |
| MockEngine, Content-Length | 20 MB | cap 256 KB | 10.38 / 23.46 | -0.66 | 88.5 | 0.3 | 1.07x | 256 KB (truncated) |
| MockEngine, Content-Length | 20 MB | cap 8 MB | 12.66 / 16.08 | 1.62 | 96.3 | 8.1 | 1.01x | 8 MB (truncated) |
| MockEngine, Content-Length | 20 MB | cap 16 MB | 18.76 / 42.42 | 7.72 | 104.4 | 16.1 | 1.01x | 16 MB (truncated) |
| MockEngine, Content-Length | 50 MB | no plugin | 26.25 / 32.11 | - | 238.9 | - | - | - |
| MockEngine, Content-Length | 50 MB | cap 256 KB | 23.75 / 32.42 | -2.50 | 239.1 | 0.3 | 1.07x | 256 KB (truncated) |
| MockEngine, Content-Length | 50 MB | cap 8 MB | 26.04 / 45.63 | -0.21 | 246.9 | 8.1 | 1.01x | 8 MB (truncated) |
| MockEngine, Content-Length | 50 MB | cap 16 MB | 34.86 / 73.62 | 8.62 | 255.0 | 16.1 | 1.01x | 16 MB (truncated) |
| MockEngine, no length | 256 KB | no plugin | 0.13 / 0.35 | - | 0.5 | - | - | - |
| MockEngine, no length | 256 KB | cap 256 KB | 0.19 / 0.42 | 0.06 | 1.0 | 0.5 | 2.06x | 262131 B |
| MockEngine, no length | 256 KB | cap 8 MB | 0.19 / 0.42 | 0.06 | 1.0 | 0.5 | 2.06x | 262131 B |
| MockEngine, no length | 256 KB | cap 16 MB | 0.27 / 0.39 | 0.13 | 1.0 | 0.5 | 2.06x | 262131 B |
| MockEngine, no length | 1 MB | no plugin | 0.28 / 0.65 | - | 2.0 | - | - | - |
| MockEngine, no length | 1 MB | cap 256 KB | 0.35 / 0.75 | 0.07 | 2.3 | 0.3 | 1.07x | 256 KB (truncated) |
| MockEngine, no length | 1 MB | cap 8 MB | 0.49 / 2.19 | 0.21 | 5.0 | 3.0 | 3.02x | 1048607 B |
| MockEngine, no length | 1 MB | cap 16 MB | 0.46 / 2.35 | 0.19 | 5.0 | 3.0 | 3.02x | 1048607 B |
| MockEngine, no length | 5 MB | no plugin | 1.35 / 2.95 | - | 12.9 | - | - | - |
| MockEngine, no length | 5 MB | cap 256 KB | 1.38 / 2.70 | 0.03 | 13.2 | 0.3 | 1.07x | 256 KB (truncated) |
| MockEngine, no length | 5 MB | cap 8 MB | 2.26 / 3.94 | 0.91 | 26.0 | 13.0 | 2.61x | 5242847 B |
| MockEngine, no length | 5 MB | cap 16 MB | 2.22 / 3.75 | 0.87 | 26.0 | 13.0 | 2.61x | 5242847 B |
| MockEngine, no length | 20 MB | no plugin | 9.69 / 13.14 | - | 88.3 | - | - | - |
| MockEngine, no length | 20 MB | cap 256 KB | 9.69 / 13.96 | 0.00 | 88.5 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, no length | 20 MB | cap 8 MB | 12.10 / 17.50 | 2.41 | 96.3 | 8.1 | 1.01x | 8 MB (truncated) |
| MockEngine, no length | 20 MB | cap 16 MB | 19.94 / 38.12 | 10.25 | 104.4 | 16.1 | 1.01x | 16 MB (truncated) |
| MockEngine, no length | 50 MB | no plugin | 25.08 / 41.83 | - | 238.9 | - | - | - |
| MockEngine, no length | 50 MB | cap 256 KB | 25.01 / 31.20 | -0.07 | 239.1 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, no length | 50 MB | cap 8 MB | 25.98 / 81.07 | 0.90 | 246.9 | 8.1 | 1.01x | 8 MB (truncated) |
| MockEngine, no length | 50 MB | cap 16 MB | 37.15 / 79.65 | 12.07 | 255.0 | 16.1 | 1.01x | 16 MB (truncated) |
| socket, Content-Length | 256 KB | no plugin | 0.74 / 2.79 | - | 0.6 | - | - | - |
| socket, Content-Length | 256 KB | cap 256 KB | 0.79 / 1.59 | 0.05 | 1.3 | 0.8 | 3.02x | 262131 B |
| socket, Content-Length | 256 KB | cap 8 MB | 0.74 / 1.06 | 0.01 | 1.6 | 1.0 | 4.02x | 262131 B |
| socket, Content-Length | 256 KB | cap 16 MB | 0.76 / 1.35 | 0.02 | 1.6 | 1.0 | 4.02x | 262131 B |
| socket, Content-Length | 1 MB | no plugin | 1.17 / 2.64 | - | 2.1 | - | - | - |
| socket, Content-Length | 1 MB | cap 256 KB | 1.29 / 2.01 | 0.12 | 2.5 | 0.4 | 1.41x | 256 KB (truncated) |
| socket, Content-Length | 1 MB | cap 8 MB | 1.57 / 2.92 | 0.40 | 6.1 | 4.0 | 4.01x | 1048607 B |
| socket, Content-Length | 1 MB | cap 16 MB | 1.55 / 2.81 | 0.38 | 6.1 | 4.0 | 4.01x | 1048607 B |
| socket, Content-Length | 5 MB | no plugin | 3.95 / 5.06 | - | 13.2 | - | - | - |
| socket, Content-Length | 5 MB | cap 256 KB | 3.56 / 5.55 | -0.39 | 13.4 | 0.2 | 0.91x | 256 KB (truncated) |
| socket, Content-Length | 5 MB | cap 8 MB | 5.10 / 7.63 | 1.16 | 32.1 | 19.0 | 3.79x | 5242847 B |
| socket, Content-Length | 5 MB | cap 16 MB | 4.96 / 6.45 | 1.02 | 34.1 | 20.9 | 4.19x | 5242847 B |
| socket, Content-Length | 20 MB | no plugin | 18.60 / 22.75 | - | 89.1 | - | - | - |
| socket, Content-Length | 20 MB | cap 256 KB | 17.20 / 21.44 | -1.40 | 89.1 | -0.0 | -0.07x | 256 KB (truncated) |
| socket, Content-Length | 20 MB | cap 8 MB | 16.13 / 19.35 | -2.47 | 104.4 | 15.3 | 1.91x | 8 MB (truncated) |
| socket, Content-Length | 20 MB | cap 16 MB | 16.91 / 20.47 | -1.69 | 120.5 | 31.4 | 1.96x | 16 MB (truncated) |
| socket, Content-Length | 50 MB | no plugin | 45.28 / 54.44 | - | 241.0 | - | - | - |
| socket, Content-Length | 50 MB | cap 256 KB | 41.72 / 47.81 | -3.56 | 240.4 | -0.6 | -2.37x | 256 KB (truncated) |
| socket, Content-Length | 50 MB | cap 8 MB | 42.74 / 44.27 | -2.54 | 255.7 | 14.7 | 1.84x | 8 MB (truncated) |
| socket, Content-Length | 50 MB | cap 16 MB | 42.55 / 54.22 | -2.73 | 271.8 | 30.8 | 1.93x | 16 MB (truncated) |
| socket, chunked | 256 KB | no plugin | 0.47 / 1.98 | - | 0.8 | - | - | - |
| socket, chunked | 256 KB | cap 256 KB | 0.47 / 0.93 | 0.00 | 1.4 | 0.5 | 2.15x | 262131 B |
| socket, chunked | 256 KB | cap 8 MB | 0.50 / 1.67 | 0.03 | 1.4 | 0.5 | 2.14x | 262131 B |
| socket, chunked | 256 KB | cap 16 MB | 0.46 / 0.87 | -0.01 | 1.4 | 0.5 | 2.11x | 262131 B |
| socket, chunked | 1 MB | no plugin | 1.16 / 1.85 | - | 3.2 | - | - | - |
| socket, chunked | 1 MB | cap 256 KB | 1.08 / 1.72 | -0.08 | 3.4 | 0.3 | 1.03x | 256 KB (truncated) |
| socket, chunked | 1 MB | cap 8 MB | 1.38 / 2.51 | 0.21 | 7.9 | 4.7 | 4.75x | 1048607 B |
| socket, chunked | 1 MB | cap 16 MB | 1.38 / 2.09 | 0.22 | 7.9 | 4.8 | 4.75x | 1048607 B |
| socket, chunked | 5 MB | no plugin | 5.69 / 6.82 | - | 18.6 | - | - | - |
| socket, chunked | 5 MB | cap 256 KB | 4.90 / 6.40 | -0.79 | 18.7 | 0.2 | 0.64x | 256 KB (truncated) |
| socket, chunked | 5 MB | cap 8 MB | 5.65 / 6.94 | -0.05 | 39.3 | 20.7 | 4.14x | 5242847 B |
| socket, chunked | 5 MB | cap 16 MB | 5.44 / 6.67 | -0.26 | 39.3 | 20.7 | 4.14x | 5242847 B |
| socket, chunked | 20 MB | no plugin | 24.00 / 29.39 | - | 110.8 | - | - | - |
| socket, chunked | 20 MB | cap 256 KB | 19.20 / 23.07 | -4.80 | 110.5 | -0.3 | -1.05x | 256 KB (truncated) |
| socket, chunked | 20 MB | cap 8 MB | 19.76 / 28.54 | -4.24 | 125.9 | 15.1 | 1.89x | 8 MB (truncated) |
| socket, chunked | 20 MB | cap 16 MB | 23.81 / 32.07 | -0.19 | 142.2 | 31.4 | 1.96x | 16 MB (truncated) |
| socket, chunked | 50 MB | no plugin | 70.73 / 82.53 | - | 295.4 | - | - | - |
| socket, chunked | 50 MB | cap 256 KB | 60.02 / 74.02 | -10.71 | 294.1 | -1.3 | -5.16x | 256 KB (truncated) |
| socket, chunked | 50 MB | cap 8 MB | 55.01 / 72.74 | -15.71 | 309.6 | 14.2 | 1.78x | 8 MB (truncated) |
| socket, chunked | 50 MB | cap 16 MB | 59.26 / 66.46 | -11.47 | 325.6 | 30.3 | 1.89x | 16 MB (truncated) |
| socket, chunked, streamed (execute {}) | 256 KB | no plugin | 0.34 / 0.80 | - | 0.3 | - | - | - |
| socket, chunked, streamed (execute {}) | 256 KB | cap 256 KB | 0.36 / 0.73 | 0.02 | 0.9 | 0.6 | 2.26x | 262131 B |
| socket, chunked, streamed (execute {}) | 256 KB | cap 8 MB | 0.34 / 0.74 | -0.00 | 0.9 | 0.6 | 2.26x | 262131 B |
| socket, chunked, streamed (execute {}) | 256 KB | cap 16 MB | 0.34 / 0.86 | 0.00 | 0.9 | 0.5 | 2.20x | 262131 B |
| socket, chunked, streamed (execute {}) | 1 MB | no plugin | 0.70 / 1.30 | - | 1.2 | - | - | - |
| socket, chunked, streamed (execute {}) | 1 MB | cap 256 KB | 0.73 / 1.34 | 0.03 | 1.5 | 0.3 | 1.30x | 256 KB (truncated) |
| socket, chunked, streamed (execute {}) | 1 MB | cap 8 MB | 0.94 / 1.77 | 0.24 | 5.9 | 4.8 | 4.77x | 1048607 B |
| socket, chunked, streamed (execute {}) | 1 MB | cap 16 MB | 0.91 / 2.13 | 0.22 | 5.9 | 4.7 | 4.71x | 1048607 B |
| socket, chunked, streamed (execute {}) | 5 MB | no plugin | 2.54 / 3.20 | - | 5.5 | - | - | - |
| socket, chunked, streamed (execute {}) | 5 MB | cap 256 KB | 2.54 / 3.24 | -0.00 | 5.8 | 0.3 | 1.17x | 256 KB (truncated) |
| socket, chunked, streamed (execute {}) | 5 MB | cap 8 MB | 3.26 / 4.61 | 0.72 | 26.1 | 20.6 | 4.13x | 5242847 B |
| socket, chunked, streamed (execute {}) | 5 MB | cap 16 MB | 2.95 / 4.71 | 0.41 | 26.1 | 20.6 | 4.12x | 5242847 B |
| socket, chunked, streamed (execute {}) | 20 MB | no plugin | 8.89 / 9.18 | - | 21.8 | - | - | - |
| socket, chunked, streamed (execute {}) | 20 MB | cap 256 KB | 9.30 / 11.39 | 0.41 | 22.2 | 0.3 | 1.33x | 256 KB (truncated) |
| socket, chunked, streamed (execute {}) | 20 MB | cap 8 MB | 9.31 / 13.82 | 0.42 | 37.5 | 15.7 | 1.96x | 8 MB (truncated) |
| socket, chunked, streamed (execute {}) | 20 MB | cap 16 MB | 9.50 / 11.48 | 0.61 | 53.6 | 31.8 | 1.99x | 16 MB (truncated) |
| socket, chunked, streamed (execute {}) | 50 MB | no plugin | 22.23 / 25.02 | - | 54.5 | - | - | - |
| socket, chunked, streamed (execute {}) | 50 MB | cap 256 KB | 23.52 / 29.48 | 1.30 | 55.0 | 0.4 | 1.71x | 256 KB (truncated) |
| socket, chunked, streamed (execute {}) | 50 MB | cap 8 MB | 23.21 / 29.25 | 0.98 | 70.3 | 15.7 | 1.96x | 8 MB (truncated) |
| socket, chunked, streamed (execute {}) | 50 MB | cap 16 MB | 24.97 / 32.82 | 2.74 | 86.6 | 32.0 | 2.00x | 16 MB (truncated) |
| MockEngine, request body (POST ByteArray) | 256 KB | no plugin | 0.06 / 0.10 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 256 KB | cap 256 KB | 0.09 / 0.13 | 0.03 | 0.3 | 0.3 | 1.06x | 262131 B |
| MockEngine, request body (POST ByteArray) | 256 KB | cap 8 MB | 0.08 / 0.14 | 0.02 | 0.3 | 0.3 | 1.06x | 262131 B |
| MockEngine, request body (POST ByteArray) | 256 KB | cap 16 MB | 0.08 / 0.11 | 0.02 | 0.3 | 0.3 | 1.06x | 262131 B |
| MockEngine, request body (POST ByteArray) | 1 MB | no plugin | 0.06 / 0.10 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 1 MB | cap 256 KB | 0.08 / 0.14 | 0.02 | 0.3 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, request body (POST ByteArray) | 1 MB | cap 8 MB | 0.10 / 0.16 | 0.04 | 1.0 | 1.0 | 1.01x | 1048607 B |
| MockEngine, request body (POST ByteArray) | 1 MB | cap 16 MB | 0.10 / 0.18 | 0.04 | 1.0 | 1.0 | 1.01x | 1048607 B |
| MockEngine, request body (POST ByteArray) | 5 MB | no plugin | 0.06 / 0.10 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 5 MB | cap 256 KB | 0.07 / 0.11 | 0.02 | 0.3 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, request body (POST ByteArray) | 5 MB | cap 8 MB | 0.18 / 0.58 | 0.13 | 5.0 | 5.0 | 1.00x | 5242847 B |
| MockEngine, request body (POST ByteArray) | 5 MB | cap 16 MB | 0.19 / 0.29 | 0.13 | 5.0 | 5.0 | 1.00x | 5242847 B |
| MockEngine, request body (POST ByteArray) | 20 MB | no plugin | 0.06 / 0.09 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 20 MB | cap 256 KB | 0.07 / 0.11 | 0.02 | 0.3 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, request body (POST ByteArray) | 20 MB | cap 8 MB | 0.90 / 1.17 | 0.84 | 8.0 | 8.0 | 1.00x | 8 MB (truncated) |
| MockEngine, request body (POST ByteArray) | 20 MB | cap 16 MB | 0.74 / 7.38 | 0.69 | 16.0 | 16.0 | 1.00x | 16 MB (truncated) |
| MockEngine, request body (POST ByteArray) | 50 MB | no plugin | 0.06 / 0.13 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 50 MB | cap 256 KB | 0.11 / 0.17 | 0.05 | 0.3 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, request body (POST ByteArray) | 50 MB | cap 8 MB | 0.65 / 1.05 | 0.60 | 8.0 | 8.0 | 1.00x | 8 MB (truncated) |
| MockEngine, request body (POST ByteArray) | 50 MB | cap 16 MB | 1.22 / 2.08 | 1.16 | 16.0 | 16.0 | 1.00x | 16 MB (truncated) |

- Arms run interleaved in 4 rounds per cell, each arm with its own client; a new client every 20 requests bounds MockEngine's history.
- 'Δ alloc / cap' divides the extra allocation by the bytes the plugin may keep (min(cap, body)): a constant means the cost is bounded by the cap, not by the body.
- Allocation is summed over all live threads (JDK 17 has no total counter); engine and dispatcher threads live for the whole run.
