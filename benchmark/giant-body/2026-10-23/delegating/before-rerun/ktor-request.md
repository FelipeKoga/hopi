### App-perceived request time with a giant body (median / p95 ms; alloc = bytes allocated by all threads per request)

| transport | body | arm | median / p95 (ms) | Δ median vs no plugin (ms) | alloc (MB) | Δ alloc vs no plugin (MB) | Δ alloc / cap | captured |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MockEngine, Content-Length | 256 KB | no plugin | 0.32 / 0.74 | - | 0.5 | - | - | - |
| MockEngine, Content-Length | 256 KB | cap 256 KB | 0.45 / 0.75 | 0.14 | 0.8 | 0.3 | 1.03x | 262131 B |
| MockEngine, Content-Length | 256 KB | cap 8 MB | 0.40 / 0.70 | 0.09 | 0.8 | 0.3 | 1.03x | 262131 B |
| MockEngine, Content-Length | 256 KB | cap 16 MB | 0.38 / 0.71 | 0.07 | 0.8 | 0.3 | 1.03x | 262131 B |
| MockEngine, Content-Length | 1 MB | no plugin | 0.39 / 0.84 | - | 2.0 | - | - | - |
| MockEngine, Content-Length | 1 MB | cap 256 KB | 0.54 / 1.06 | 0.15 | 2.3 | 0.3 | 1.04x | 256 KB (truncated) |
| MockEngine, Content-Length | 1 MB | cap 8 MB | 0.53 / 0.73 | 0.15 | 3.0 | 1.0 | 1.01x | 1048607 B |
| MockEngine, Content-Length | 1 MB | cap 16 MB | 0.50 / 1.66 | 0.12 | 3.0 | 1.0 | 1.01x | 1048607 B |
| MockEngine, Content-Length | 5 MB | no plugin | 1.42 / 3.17 | - | 12.9 | - | - | - |
| MockEngine, Content-Length | 5 MB | cap 256 KB | 1.45 / 3.00 | 0.02 | 13.2 | 0.3 | 1.04x | 256 KB (truncated) |
| MockEngine, Content-Length | 5 MB | cap 8 MB | 1.91 / 3.75 | 0.48 | 18.9 | 5.9 | 1.19x | 5242847 B |
| MockEngine, Content-Length | 5 MB | cap 16 MB | 1.95 / 3.73 | 0.53 | 18.9 | 5.9 | 1.19x | 5242847 B |
| MockEngine, Content-Length | 20 MB | no plugin | 8.48 / 10.56 | - | 88.3 | - | - | - |
| MockEngine, Content-Length | 20 MB | cap 256 KB | 7.41 / 10.52 | -1.07 | 88.5 | 0.3 | 1.03x | 256 KB (truncated) |
| MockEngine, Content-Length | 20 MB | cap 8 MB | 10.82 / 11.63 | 2.34 | 96.3 | 8.1 | 1.01x | 8 MB (truncated) |
| MockEngine, Content-Length | 20 MB | cap 16 MB | 16.14 / 17.22 | 7.66 | 104.4 | 16.1 | 1.01x | 16 MB (truncated) |
| MockEngine, Content-Length | 50 MB | no plugin | 22.82 / 29.19 | - | 238.9 | - | - | - |
| MockEngine, Content-Length | 50 MB | cap 256 KB | 23.70 / 35.85 | 0.88 | 239.1 | 0.3 | 1.03x | 256 KB (truncated) |
| MockEngine, Content-Length | 50 MB | cap 8 MB | 26.33 / 45.50 | 3.51 | 246.9 | 8.1 | 1.01x | 8 MB (truncated) |
| MockEngine, Content-Length | 50 MB | cap 16 MB | 35.74 / 44.29 | 12.92 | 255.0 | 16.1 | 1.01x | 16 MB (truncated) |
| MockEngine, no length | 256 KB | no plugin | 0.12 / 0.21 | - | 0.5 | - | - | - |
| MockEngine, no length | 256 KB | cap 256 KB | 0.17 / 0.37 | 0.05 | 1.0 | 0.5 | 2.06x | 262131 B |
| MockEngine, no length | 256 KB | cap 8 MB | 0.17 / 0.27 | 0.05 | 1.0 | 0.5 | 2.06x | 262131 B |
| MockEngine, no length | 256 KB | cap 16 MB | 0.17 / 0.38 | 0.05 | 1.0 | 0.5 | 2.06x | 262131 B |
| MockEngine, no length | 1 MB | no plugin | 0.33 / 0.86 | - | 2.0 | - | - | - |
| MockEngine, no length | 1 MB | cap 256 KB | 0.37 / 0.98 | 0.04 | 2.3 | 0.3 | 1.07x | 256 KB (truncated) |
| MockEngine, no length | 1 MB | cap 8 MB | 0.57 / 1.93 | 0.24 | 5.0 | 3.0 | 3.02x | 1048607 B |
| MockEngine, no length | 1 MB | cap 16 MB | 0.61 / 2.32 | 0.28 | 5.0 | 3.0 | 3.02x | 1048607 B |
| MockEngine, no length | 5 MB | no plugin | 1.44 / 3.12 | - | 12.9 | - | - | - |
| MockEngine, no length | 5 MB | cap 256 KB | 1.42 / 4.56 | -0.02 | 13.2 | 0.3 | 1.07x | 256 KB (truncated) |
| MockEngine, no length | 5 MB | cap 8 MB | 2.40 / 4.27 | 0.96 | 26.0 | 13.0 | 2.61x | 5242847 B |
| MockEngine, no length | 5 MB | cap 16 MB | 2.54 / 4.61 | 1.10 | 26.0 | 13.0 | 2.61x | 5242847 B |
| MockEngine, no length | 20 MB | no plugin | 7.04 / 10.38 | - | 88.2 | - | - | - |
| MockEngine, no length | 20 MB | cap 256 KB | 7.46 / 11.88 | 0.41 | 88.5 | 0.3 | 1.31x | 256 KB (truncated) |
| MockEngine, no length | 20 MB | cap 8 MB | 9.79 / 30.42 | 2.74 | 96.2 | 8.1 | 1.01x | 8 MB (truncated) |
| MockEngine, no length | 20 MB | cap 16 MB | 18.92 / 22.59 | 11.88 | 104.3 | 16.1 | 1.01x | 16 MB (truncated) |
| MockEngine, no length | 50 MB | no plugin | 23.16 / 38.90 | - | 238.8 | - | - | - |
| MockEngine, no length | 50 MB | cap 256 KB | 21.35 / 27.00 | -1.81 | 239.1 | 0.3 | 1.32x | 256 KB (truncated) |
| MockEngine, no length | 50 MB | cap 8 MB | 25.37 / 26.94 | 2.21 | 246.9 | 8.1 | 1.02x | 8 MB (truncated) |
| MockEngine, no length | 50 MB | cap 16 MB | 35.47 / 89.94 | 12.32 | 255.0 | 16.2 | 1.01x | 16 MB (truncated) |
| socket, Content-Length | 256 KB | no plugin | 0.67 / 1.66 | - | 0.6 | - | - | - |
| socket, Content-Length | 256 KB | cap 256 KB | 0.69 / 1.15 | 0.02 | 0.8 | 0.3 | 1.03x | 262131 B |
| socket, Content-Length | 256 KB | cap 8 MB | 0.63 / 1.19 | -0.04 | 0.8 | 0.3 | 1.05x | 262131 B |
| socket, Content-Length | 256 KB | cap 16 MB | 0.64 / 1.14 | -0.03 | 0.8 | 0.3 | 1.04x | 262131 B |
| socket, Content-Length | 1 MB | no plugin | 1.11 / 2.76 | - | 2.1 | - | - | - |
| socket, Content-Length | 1 MB | cap 256 KB | 1.05 / 1.71 | -0.06 | 2.4 | 0.2 | 1.00x | 256 KB (truncated) |
| socket, Content-Length | 1 MB | cap 8 MB | 1.23 / 2.25 | 0.12 | 3.1 | 1.0 | 1.01x | 1048607 B |
| socket, Content-Length | 1 MB | cap 16 MB | 1.19 / 2.01 | 0.08 | 3.1 | 1.0 | 1.01x | 1048607 B |
| socket, Content-Length | 5 MB | no plugin | 4.54 / 6.20 | - | 13.2 | - | - | - |
| socket, Content-Length | 5 MB | cap 256 KB | 3.95 / 5.78 | -0.60 | 13.4 | 0.2 | 0.67x | 256 KB (truncated) |
| socket, Content-Length | 5 MB | cap 8 MB | 4.76 / 6.60 | 0.22 | 19.1 | 5.9 | 1.19x | 5242847 B |
| socket, Content-Length | 5 MB | cap 16 MB | 4.67 / 5.95 | 0.12 | 19.1 | 5.9 | 1.19x | 5242847 B |
| socket, Content-Length | 20 MB | no plugin | 17.03 / 20.19 | - | 89.2 | - | - | - |
| socket, Content-Length | 20 MB | cap 256 KB | 14.52 / 17.76 | -2.51 | 89.1 | -0.1 | -0.36x | 256 KB (truncated) |
| socket, Content-Length | 20 MB | cap 8 MB | 14.86 / 17.59 | -2.18 | 96.9 | 7.7 | 0.96x | 8 MB (truncated) |
| socket, Content-Length | 20 MB | cap 16 MB | 15.06 / 17.84 | -1.97 | 104.9 | 15.8 | 0.99x | 16 MB (truncated) |
| socket, Content-Length | 50 MB | no plugin | 50.50 / 63.16 | - | 241.3 | - | - | - |
| socket, Content-Length | 50 MB | cap 256 KB | 38.55 / 51.09 | -11.95 | 240.5 | -0.8 | -3.37x | 256 KB (truncated) |
| socket, Content-Length | 50 MB | cap 8 MB | 36.99 / 50.65 | -13.51 | 248.3 | 7.0 | 0.87x | 8 MB (truncated) |
| socket, Content-Length | 50 MB | cap 16 MB | 52.03 / 66.92 | 1.53 | 256.4 | 15.0 | 0.94x | 16 MB (truncated) |
| socket, chunked | 256 KB | no plugin | 0.46 / 0.88 | - | 0.8 | - | - | - |
| socket, chunked | 256 KB | cap 256 KB | 0.45 / 0.91 | -0.00 | 1.4 | 0.5 | 2.14x | 262131 B |
| socket, chunked | 256 KB | cap 8 MB | 0.44 / 0.89 | -0.01 | 1.4 | 0.5 | 2.14x | 262131 B |
| socket, chunked | 256 KB | cap 16 MB | 0.43 / 0.87 | -0.02 | 1.4 | 0.5 | 2.11x | 262131 B |
| socket, chunked | 1 MB | no plugin | 1.23 / 1.76 | - | 3.2 | - | - | - |
| socket, chunked | 1 MB | cap 256 KB | 1.11 / 2.01 | -0.12 | 3.5 | 0.3 | 1.02x | 256 KB (truncated) |
| socket, chunked | 1 MB | cap 8 MB | 1.26 / 2.29 | 0.04 | 7.9 | 4.8 | 4.75x | 1048607 B |
| socket, chunked | 1 MB | cap 16 MB | 1.33 / 2.21 | 0.11 | 7.9 | 4.8 | 4.75x | 1048607 B |
| socket, chunked | 5 MB | no plugin | 5.73 / 6.70 | - | 18.6 | - | - | - |
| socket, chunked | 5 MB | cap 256 KB | 4.99 / 6.44 | -0.74 | 18.8 | 0.2 | 0.65x | 256 KB (truncated) |
| socket, chunked | 5 MB | cap 8 MB | 5.09 / 7.59 | -0.64 | 39.3 | 20.7 | 4.14x | 5242847 B |
| socket, chunked | 5 MB | cap 16 MB | 5.15 / 7.02 | -0.58 | 39.3 | 20.7 | 4.14x | 5242847 B |
| socket, chunked | 20 MB | no plugin | 23.83 / 28.13 | - | 111.0 | - | - | - |
| socket, chunked | 20 MB | cap 256 KB | 19.83 / 25.05 | -4.01 | 110.7 | -0.2 | -0.93x | 256 KB (truncated) |
| socket, chunked | 20 MB | cap 8 MB | 20.34 / 24.78 | -3.49 | 126.2 | 15.3 | 1.91x | 8 MB (truncated) |
| socket, chunked | 20 MB | cap 16 MB | 19.98 / 24.71 | -3.85 | 142.3 | 31.4 | 1.96x | 16 MB (truncated) |
| socket, chunked | 50 MB | no plugin | 67.20 / 93.63 | - | 296.0 | - | - | - |
| socket, chunked | 50 MB | cap 256 KB | 57.31 / 71.06 | -9.89 | 294.6 | -1.3 | -5.37x | 256 KB (truncated) |
| socket, chunked | 50 MB | cap 8 MB | 54.23 / 65.12 | -12.97 | 310.2 | 14.2 | 1.78x | 8 MB (truncated) |
| socket, chunked | 50 MB | cap 16 MB | 57.19 / 146.52 | -10.01 | 326.3 | 30.4 | 1.90x | 16 MB (truncated) |
| socket, chunked, streamed (execute {}) | 256 KB | no plugin | 0.33 / 0.80 | - | 0.3 | - | - | - |
| socket, chunked, streamed (execute {}) | 256 KB | cap 256 KB | 0.35 / 0.80 | 0.02 | 0.9 | 0.5 | 2.16x | 262131 B |
| socket, chunked, streamed (execute {}) | 256 KB | cap 8 MB | 0.35 / 0.78 | 0.02 | 0.9 | 0.5 | 2.20x | 262131 B |
| socket, chunked, streamed (execute {}) | 256 KB | cap 16 MB | 0.35 / 0.80 | 0.02 | 0.9 | 0.6 | 2.22x | 262131 B |
| socket, chunked, streamed (execute {}) | 1 MB | no plugin | 0.74 / 1.37 | - | 1.2 | - | - | - |
| socket, chunked, streamed (execute {}) | 1 MB | cap 256 KB | 0.75 / 1.41 | 0.00 | 1.5 | 0.3 | 1.23x | 256 KB (truncated) |
| socket, chunked, streamed (execute {}) | 1 MB | cap 8 MB | 0.85 / 1.40 | 0.11 | 5.8 | 4.7 | 4.65x | 1048607 B |
| socket, chunked, streamed (execute {}) | 1 MB | cap 16 MB | 0.85 / 1.76 | 0.11 | 5.8 | 4.6 | 4.59x | 1048607 B |
| socket, chunked, streamed (execute {}) | 5 MB | no plugin | 2.50 / 3.59 | - | 5.6 | - | - | - |
| socket, chunked, streamed (execute {}) | 5 MB | cap 256 KB | 2.43 / 3.04 | -0.08 | 5.9 | 0.3 | 1.18x | 256 KB (truncated) |
| socket, chunked, streamed (execute {}) | 5 MB | cap 8 MB | 2.89 / 4.09 | 0.38 | 26.2 | 20.6 | 4.12x | 5242847 B |
| socket, chunked, streamed (execute {}) | 5 MB | cap 16 MB | 2.80 / 4.72 | 0.29 | 26.2 | 20.6 | 4.12x | 5242847 B |
| socket, chunked, streamed (execute {}) | 20 MB | no plugin | 9.06 / 9.93 | - | 22.1 | - | - | - |
| socket, chunked, streamed (execute {}) | 20 MB | cap 256 KB | 9.02 / 9.54 | -0.04 | 22.4 | 0.3 | 1.24x | 256 KB (truncated) |
| socket, chunked, streamed (execute {}) | 20 MB | cap 8 MB | 9.52 / 19.91 | 0.46 | 37.7 | 15.6 | 1.96x | 8 MB (truncated) |
| socket, chunked, streamed (execute {}) | 20 MB | cap 16 MB | 9.46 / 15.31 | 0.40 | 53.8 | 31.8 | 1.99x | 16 MB (truncated) |
| socket, chunked, streamed (execute {}) | 50 MB | no plugin | 22.12 / 23.71 | - | 55.1 | - | - | - |
| socket, chunked, streamed (execute {}) | 50 MB | cap 256 KB | 22.53 / 23.30 | 0.41 | 55.5 | 0.4 | 1.45x | 256 KB (truncated) |
| socket, chunked, streamed (execute {}) | 50 MB | cap 8 MB | 22.74 / 27.69 | 0.62 | 70.8 | 15.7 | 1.96x | 8 MB (truncated) |
| socket, chunked, streamed (execute {}) | 50 MB | cap 16 MB | 22.94 / 26.15 | 0.82 | 86.9 | 31.8 | 1.99x | 16 MB (truncated) |
| MockEngine, request body (POST ByteArray) | 256 KB | no plugin | 0.06 / 0.11 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 256 KB | cap 256 KB | 0.09 / 0.14 | 0.03 | 0.3 | 0.3 | 1.06x | 262131 B |
| MockEngine, request body (POST ByteArray) | 256 KB | cap 8 MB | 0.08 / 0.18 | 0.02 | 0.3 | 0.3 | 1.06x | 262131 B |
| MockEngine, request body (POST ByteArray) | 256 KB | cap 16 MB | 0.09 / 0.13 | 0.02 | 0.3 | 0.3 | 1.06x | 262131 B |
| MockEngine, request body (POST ByteArray) | 1 MB | no plugin | 0.06 / 0.10 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 1 MB | cap 256 KB | 0.08 / 0.14 | 0.02 | 0.3 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, request body (POST ByteArray) | 1 MB | cap 8 MB | 0.10 / 0.14 | 0.04 | 1.0 | 1.0 | 1.01x | 1048607 B |
| MockEngine, request body (POST ByteArray) | 1 MB | cap 16 MB | 0.10 / 0.15 | 0.04 | 1.0 | 1.0 | 1.01x | 1048607 B |
| MockEngine, request body (POST ByteArray) | 5 MB | no plugin | 0.05 / 0.09 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 5 MB | cap 256 KB | 0.07 / 0.13 | 0.02 | 0.3 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, request body (POST ByteArray) | 5 MB | cap 8 MB | 0.19 / 0.25 | 0.13 | 5.0 | 5.0 | 1.00x | 5242847 B |
| MockEngine, request body (POST ByteArray) | 5 MB | cap 16 MB | 0.20 / 0.59 | 0.14 | 5.0 | 5.0 | 1.00x | 5242847 B |
| MockEngine, request body (POST ByteArray) | 20 MB | no plugin | 0.06 / 0.16 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 20 MB | cap 256 KB | 0.09 / 0.16 | 0.03 | 0.3 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, request body (POST ByteArray) | 20 MB | cap 8 MB | 0.30 / 0.83 | 0.24 | 8.0 | 8.0 | 1.00x | 8 MB (truncated) |
| MockEngine, request body (POST ByteArray) | 20 MB | cap 16 MB | 1.53 / 1.71 | 1.47 | 16.0 | 16.0 | 1.00x | 16 MB (truncated) |
| MockEngine, request body (POST ByteArray) | 50 MB | no plugin | 0.07 / 0.13 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 50 MB | cap 256 KB | 0.10 / 0.22 | 0.03 | 0.3 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, request body (POST ByteArray) | 50 MB | cap 8 MB | 3.79 / 10.88 | 3.73 | 8.0 | 8.0 | 1.00x | 8 MB (truncated) |
| MockEngine, request body (POST ByteArray) | 50 MB | cap 16 MB | 5.71 / 77.38 | 5.65 | 16.0 | 16.0 | 1.00x | 16 MB (truncated) |

- Arms run interleaved in 4 rounds per cell, each arm with its own client; a new client every 20 requests bounds MockEngine's history.
- 'Δ alloc / cap' divides the extra allocation by the bytes the plugin may keep (min(cap, body)): a constant means the cost is bounded by the cap, not by the body.
- Allocation is summed over all live threads (JDK 17 has no total counter); engine and dispatcher threads live for the whole run.
