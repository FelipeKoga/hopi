### App-perceived request time with a giant body (median / p95 ms; alloc = bytes allocated by all threads per request)

| transport | body | arm | median / p95 (ms) | Δ median vs no plugin (ms) | alloc (MB) | Δ alloc vs no plugin (MB) | Δ alloc / cap | captured |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MockEngine, Content-Length | 256 KB | no plugin | 0.32 / 0.76 | - | 0.5 | - | - | - |
| MockEngine, Content-Length | 256 KB | cap 256 KB | 0.41 / 0.76 | 0.09 | 0.8 | 0.3 | 1.03x | 262131 B |
| MockEngine, Content-Length | 256 KB | cap 8 MB | 0.40 / 0.63 | 0.08 | 0.8 | 0.3 | 1.03x | 262131 B |
| MockEngine, Content-Length | 256 KB | cap 16 MB | 0.39 / 0.75 | 0.07 | 0.8 | 0.3 | 1.03x | 262131 B |
| MockEngine, Content-Length | 1 MB | no plugin | 0.51 / 1.12 | - | 2.0 | - | - | - |
| MockEngine, Content-Length | 1 MB | cap 256 KB | 0.49 / 1.10 | -0.02 | 2.3 | 0.3 | 1.07x | 256 KB (truncated) |
| MockEngine, Content-Length | 1 MB | cap 8 MB | 0.54 / 1.03 | 0.03 | 3.0 | 1.0 | 1.01x | 1048607 B |
| MockEngine, Content-Length | 1 MB | cap 16 MB | 0.62 / 0.91 | 0.12 | 3.0 | 1.0 | 1.01x | 1048607 B |
| MockEngine, Content-Length | 5 MB | no plugin | 1.37 / 3.10 | - | 12.9 | - | - | - |
| MockEngine, Content-Length | 5 MB | cap 256 KB | 1.52 / 3.10 | 0.14 | 13.2 | 0.3 | 1.07x | 256 KB (truncated) |
| MockEngine, Content-Length | 5 MB | cap 8 MB | 2.02 / 3.96 | 0.65 | 18.9 | 5.9 | 1.19x | 5242847 B |
| MockEngine, Content-Length | 5 MB | cap 16 MB | 2.16 / 3.79 | 0.79 | 18.9 | 5.9 | 1.19x | 5242847 B |
| MockEngine, Content-Length | 20 MB | no plugin | 9.51 / 14.91 | - | 88.3 | - | - | - |
| MockEngine, Content-Length | 20 MB | cap 256 KB | 9.67 / 11.84 | 0.16 | 88.5 | 0.3 | 1.07x | 256 KB (truncated) |
| MockEngine, Content-Length | 20 MB | cap 8 MB | 11.16 / 12.39 | 1.65 | 96.3 | 8.1 | 1.01x | 8 MB (truncated) |
| MockEngine, Content-Length | 20 MB | cap 16 MB | 17.61 / 19.95 | 8.10 | 104.4 | 16.1 | 1.01x | 16 MB (truncated) |
| MockEngine, Content-Length | 50 MB | no plugin | 23.43 / 29.47 | - | 238.9 | - | - | - |
| MockEngine, Content-Length | 50 MB | cap 256 KB | 27.80 / 39.27 | 4.37 | 239.1 | 0.3 | 1.07x | 256 KB (truncated) |
| MockEngine, Content-Length | 50 MB | cap 8 MB | 28.26 / 35.63 | 4.83 | 246.9 | 8.1 | 1.01x | 8 MB (truncated) |
| MockEngine, Content-Length | 50 MB | cap 16 MB | 37.04 / 65.40 | 13.61 | 255.0 | 16.1 | 1.01x | 16 MB (truncated) |
| MockEngine, no length | 256 KB | no plugin | 0.12 / 0.20 | - | 0.5 | - | - | - |
| MockEngine, no length | 256 KB | cap 256 KB | 0.16 / 0.23 | 0.04 | 1.0 | 0.5 | 2.07x | 262131 B |
| MockEngine, no length | 256 KB | cap 8 MB | 0.16 / 0.23 | 0.04 | 1.0 | 0.5 | 2.07x | 262131 B |
| MockEngine, no length | 256 KB | cap 16 MB | 0.17 / 0.23 | 0.05 | 1.0 | 0.5 | 2.07x | 262131 B |
| MockEngine, no length | 1 MB | no plugin | 0.29 / 0.63 | - | 2.0 | - | - | - |
| MockEngine, no length | 1 MB | cap 256 KB | 0.36 / 0.78 | 0.07 | 2.3 | 0.3 | 1.07x | 256 KB (truncated) |
| MockEngine, no length | 1 MB | cap 8 MB | 0.54 / 1.62 | 0.25 | 5.0 | 3.0 | 3.02x | 1048607 B |
| MockEngine, no length | 1 MB | cap 16 MB | 0.48 / 1.97 | 0.19 | 5.0 | 3.0 | 3.02x | 1048607 B |
| MockEngine, no length | 5 MB | no plugin | 1.40 / 2.90 | - | 12.9 | - | - | - |
| MockEngine, no length | 5 MB | cap 256 KB | 1.52 / 3.80 | 0.12 | 13.2 | 0.3 | 1.07x | 256 KB (truncated) |
| MockEngine, no length | 5 MB | cap 8 MB | 2.92 / 8.63 | 1.52 | 26.0 | 13.0 | 2.61x | 5242847 B |
| MockEngine, no length | 5 MB | cap 16 MB | 2.40 / 4.44 | 1.00 | 26.0 | 13.0 | 2.61x | 5242847 B |
| MockEngine, no length | 20 MB | no plugin | 7.29 / 10.24 | - | 88.3 | - | - | - |
| MockEngine, no length | 20 MB | cap 256 KB | 7.34 / 9.76 | 0.05 | 88.5 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, no length | 20 MB | cap 8 MB | 9.26 / 11.39 | 1.98 | 96.3 | 8.1 | 1.01x | 8 MB (truncated) |
| MockEngine, no length | 20 MB | cap 16 MB | 19.05 / 22.15 | 11.77 | 104.4 | 16.1 | 1.01x | 16 MB (truncated) |
| MockEngine, no length | 50 MB | no plugin | 22.98 / 26.81 | - | 238.9 | - | - | - |
| MockEngine, no length | 50 MB | cap 256 KB | 24.22 / 33.31 | 1.24 | 239.1 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, no length | 50 MB | cap 8 MB | 24.82 / 32.93 | 1.84 | 246.9 | 8.1 | 1.01x | 8 MB (truncated) |
| MockEngine, no length | 50 MB | cap 16 MB | 34.91 / 44.94 | 11.94 | 255.0 | 16.1 | 1.01x | 16 MB (truncated) |
| socket, Content-Length | 256 KB | no plugin | 0.66 / 1.59 | - | 0.6 | - | - | - |
| socket, Content-Length | 256 KB | cap 256 KB | 0.66 / 1.08 | -0.01 | 0.8 | 0.3 | 1.04x | 262131 B |
| socket, Content-Length | 256 KB | cap 8 MB | 0.64 / 0.96 | -0.02 | 0.8 | 0.3 | 1.02x | 262131 B |
| socket, Content-Length | 256 KB | cap 16 MB | 0.60 / 0.98 | -0.07 | 0.8 | 0.3 | 1.02x | 262131 B |
| socket, Content-Length | 1 MB | no plugin | 1.06 / 1.97 | - | 2.1 | - | - | - |
| socket, Content-Length | 1 MB | cap 256 KB | 0.98 / 1.60 | -0.08 | 2.4 | 0.3 | 1.12x | 256 KB (truncated) |
| socket, Content-Length | 1 MB | cap 8 MB | 1.13 / 1.83 | 0.06 | 3.1 | 1.0 | 1.01x | 1048607 B |
| socket, Content-Length | 1 MB | cap 16 MB | 1.12 / 1.76 | 0.06 | 3.1 | 1.0 | 1.01x | 1048607 B |
| socket, Content-Length | 5 MB | no plugin | 4.17 / 5.85 | - | 13.2 | - | - | - |
| socket, Content-Length | 5 MB | cap 256 KB | 3.79 / 7.24 | -0.38 | 13.4 | 0.2 | 0.88x | 256 KB (truncated) |
| socket, Content-Length | 5 MB | cap 8 MB | 4.70 / 6.46 | 0.53 | 19.1 | 6.0 | 1.19x | 5242847 B |
| socket, Content-Length | 5 MB | cap 16 MB | 4.59 / 6.43 | 0.42 | 19.1 | 6.0 | 1.19x | 5242847 B |
| socket, Content-Length | 20 MB | no plugin | 19.61 / 22.89 | - | 89.2 | - | - | - |
| socket, Content-Length | 20 MB | cap 256 KB | 17.30 / 20.48 | -2.31 | 89.2 | -0.0 | -0.18x | 256 KB (truncated) |
| socket, Content-Length | 20 MB | cap 8 MB | 16.81 / 21.02 | -2.80 | 104.5 | 15.3 | 1.91x | 8 MB (truncated) |
| socket, Content-Length | 20 MB | cap 16 MB | 17.62 / 25.80 | -1.99 | 120.6 | 31.4 | 1.96x | 16 MB (truncated) |
| socket, Content-Length | 50 MB | no plugin | 46.27 / 49.42 | - | 241.2 | - | - | - |
| socket, Content-Length | 50 MB | cap 256 KB | 42.62 / 46.41 | -3.65 | 240.6 | -0.6 | -2.25x | 256 KB (truncated) |
| socket, Content-Length | 50 MB | cap 8 MB | 42.74 / 60.55 | -3.53 | 255.9 | 14.7 | 1.84x | 8 MB (truncated) |
| socket, Content-Length | 50 MB | cap 16 MB | 42.72 / 45.69 | -3.54 | 272.0 | 30.8 | 1.93x | 16 MB (truncated) |
| socket, chunked | 256 KB | no plugin | 0.51 / 0.99 | - | 0.8 | - | - | - |
| socket, chunked | 256 KB | cap 256 KB | 0.49 / 1.00 | -0.02 | 1.4 | 0.5 | 2.17x | 262131 B |
| socket, chunked | 256 KB | cap 8 MB | 0.57 / 0.98 | 0.06 | 1.4 | 0.5 | 2.11x | 262131 B |
| socket, chunked | 256 KB | cap 16 MB | 0.60 / 1.01 | 0.08 | 1.4 | 0.5 | 2.11x | 262131 B |
| socket, chunked | 1 MB | no plugin | 1.43 / 2.56 | - | 3.2 | - | - | - |
| socket, chunked | 1 MB | cap 256 KB | 1.29 / 5.19 | -0.15 | 3.5 | 0.3 | 1.05x | 256 KB (truncated) |
| socket, chunked | 1 MB | cap 8 MB | 1.85 / 5.65 | 0.41 | 7.9 | 4.8 | 4.75x | 1048607 B |
| socket, chunked | 1 MB | cap 16 MB | 1.71 / 5.11 | 0.27 | 7.9 | 4.8 | 4.76x | 1048607 B |
| socket, chunked | 5 MB | no plugin | 5.83 / 7.58 | - | 18.6 | - | - | - |
| socket, chunked | 5 MB | cap 256 KB | 4.82 / 6.41 | -1.00 | 18.8 | 0.2 | 0.66x | 256 KB (truncated) |
| socket, chunked | 5 MB | cap 8 MB | 6.24 / 12.27 | 0.41 | 39.3 | 20.7 | 4.14x | 5242847 B |
| socket, chunked | 5 MB | cap 16 MB | 6.17 / 12.10 | 0.34 | 39.3 | 20.7 | 4.14x | 5242847 B |
| socket, chunked | 20 MB | no plugin | 23.09 / 26.16 | - | 111.0 | - | - | - |
| socket, chunked | 20 MB | cap 256 KB | 18.87 / 24.29 | -4.23 | 110.8 | -0.3 | -1.09x | 256 KB (truncated) |
| socket, chunked | 20 MB | cap 8 MB | 19.19 / 20.48 | -3.90 | 126.1 | 15.1 | 1.89x | 8 MB (truncated) |
| socket, chunked | 20 MB | cap 16 MB | 19.76 / 24.47 | -3.33 | 142.3 | 31.3 | 1.96x | 16 MB (truncated) |
| socket, chunked | 50 MB | no plugin | 60.53 / 62.77 | - | 295.7 | - | - | - |
| socket, chunked | 50 MB | cap 256 KB | 50.69 / 52.39 | -9.84 | 294.6 | -1.1 | -4.50x | 256 KB (truncated) |
| socket, chunked | 50 MB | cap 8 MB | 51.43 / 54.28 | -9.10 | 310.1 | 14.4 | 1.80x | 8 MB (truncated) |
| socket, chunked | 50 MB | cap 16 MB | 50.62 / 52.89 | -9.91 | 326.2 | 30.5 | 1.91x | 16 MB (truncated) |
| socket, chunked, streamed (execute {}) | 256 KB | no plugin | 0.31 / 0.70 | - | 0.3 | - | - | - |
| socket, chunked, streamed (execute {}) | 256 KB | cap 256 KB | 0.34 / 0.64 | 0.04 | 0.9 | 0.6 | 2.26x | 262131 B |
| socket, chunked, streamed (execute {}) | 256 KB | cap 8 MB | 0.33 / 0.68 | 0.02 | 0.9 | 0.6 | 2.23x | 262131 B |
| socket, chunked, streamed (execute {}) | 256 KB | cap 16 MB | 0.33 / 0.70 | 0.03 | 0.9 | 0.6 | 2.26x | 262131 B |
| socket, chunked, streamed (execute {}) | 1 MB | no plugin | 0.68 / 1.12 | - | 1.2 | - | - | - |
| socket, chunked, streamed (execute {}) | 1 MB | cap 256 KB | 0.73 / 1.12 | 0.05 | 1.5 | 0.3 | 1.26x | 256 KB (truncated) |
| socket, chunked, streamed (execute {}) | 1 MB | cap 8 MB | 0.89 / 2.63 | 0.21 | 5.9 | 4.7 | 4.71x | 1048607 B |
| socket, chunked, streamed (execute {}) | 1 MB | cap 16 MB | 0.86 / 2.72 | 0.18 | 5.8 | 4.6 | 4.59x | 1048607 B |
| socket, chunked, streamed (execute {}) | 5 MB | no plugin | 2.83 / 3.44 | - | 5.6 | - | - | - |
| socket, chunked, streamed (execute {}) | 5 MB | cap 256 KB | 2.81 / 4.66 | -0.03 | 5.9 | 0.3 | 1.20x | 256 KB (truncated) |
| socket, chunked, streamed (execute {}) | 5 MB | cap 8 MB | 3.20 / 5.09 | 0.37 | 26.2 | 20.6 | 4.12x | 5242847 B |
| socket, chunked, streamed (execute {}) | 5 MB | cap 16 MB | 2.96 / 4.56 | 0.13 | 26.2 | 20.6 | 4.12x | 5242847 B |
| socket, chunked, streamed (execute {}) | 20 MB | no plugin | 8.75 / 9.47 | - | 22.1 | - | - | - |
| socket, chunked, streamed (execute {}) | 20 MB | cap 256 KB | 9.05 / 10.87 | 0.30 | 22.4 | 0.3 | 1.28x | 256 KB (truncated) |
| socket, chunked, streamed (execute {}) | 20 MB | cap 8 MB | 9.27 / 10.77 | 0.52 | 37.7 | 15.6 | 1.96x | 8 MB (truncated) |
| socket, chunked, streamed (execute {}) | 20 MB | cap 16 MB | 9.59 / 11.35 | 0.84 | 53.8 | 31.8 | 1.98x | 16 MB (truncated) |
| socket, chunked, streamed (execute {}) | 50 MB | no plugin | 22.00 / 27.43 | - | 55.1 | - | - | - |
| socket, chunked, streamed (execute {}) | 50 MB | cap 256 KB | 22.54 / 26.88 | 0.55 | 55.6 | 0.4 | 1.75x | 256 KB (truncated) |
| socket, chunked, streamed (execute {}) | 50 MB | cap 8 MB | 23.32 / 26.81 | 1.33 | 70.8 | 15.7 | 1.96x | 8 MB (truncated) |
| socket, chunked, streamed (execute {}) | 50 MB | cap 16 MB | 23.31 / 24.75 | 1.31 | 86.9 | 31.8 | 1.99x | 16 MB (truncated) |
| MockEngine, request body (POST ByteArray) | 256 KB | no plugin | 0.06 / 0.11 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 256 KB | cap 256 KB | 0.09 / 0.12 | 0.02 | 0.3 | 0.3 | 1.06x | 262131 B |
| MockEngine, request body (POST ByteArray) | 256 KB | cap 8 MB | 0.08 / 0.15 | 0.02 | 0.3 | 0.3 | 1.06x | 262131 B |
| MockEngine, request body (POST ByteArray) | 256 KB | cap 16 MB | 0.08 / 0.13 | 0.02 | 0.3 | 0.3 | 1.06x | 262131 B |
| MockEngine, request body (POST ByteArray) | 1 MB | no plugin | 0.06 / 0.16 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 1 MB | cap 256 KB | 0.08 / 0.14 | 0.02 | 0.3 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, request body (POST ByteArray) | 1 MB | cap 8 MB | 0.10 / 0.16 | 0.04 | 1.0 | 1.0 | 1.01x | 1048607 B |
| MockEngine, request body (POST ByteArray) | 1 MB | cap 16 MB | 0.09 / 0.30 | 0.04 | 1.0 | 1.0 | 1.01x | 1048607 B |
| MockEngine, request body (POST ByteArray) | 5 MB | no plugin | 0.06 / 0.09 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 5 MB | cap 256 KB | 0.07 / 0.12 | 0.02 | 0.3 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, request body (POST ByteArray) | 5 MB | cap 8 MB | 0.19 / 0.51 | 0.14 | 5.0 | 5.0 | 1.00x | 5242847 B |
| MockEngine, request body (POST ByteArray) | 5 MB | cap 16 MB | 0.19 / 0.28 | 0.14 | 5.0 | 5.0 | 1.00x | 5242847 B |
| MockEngine, request body (POST ByteArray) | 20 MB | no plugin | 0.06 / 0.07 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 20 MB | cap 256 KB | 0.08 / 0.14 | 0.02 | 0.3 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, request body (POST ByteArray) | 20 MB | cap 8 MB | 0.27 / 0.33 | 0.21 | 8.0 | 8.0 | 1.00x | 8 MB (truncated) |
| MockEngine, request body (POST ByteArray) | 20 MB | cap 16 MB | 0.53 / 7.04 | 0.47 | 16.0 | 16.0 | 1.00x | 16 MB (truncated) |
| MockEngine, request body (POST ByteArray) | 50 MB | no plugin | 0.07 / 0.29 | - | 0.0 | - | - | - |
| MockEngine, request body (POST ByteArray) | 50 MB | cap 256 KB | 0.08 / 0.30 | 0.01 | 0.3 | 0.3 | 1.06x | 256 KB (truncated) |
| MockEngine, request body (POST ByteArray) | 50 MB | cap 8 MB | 3.93 / 16.21 | 3.86 | 8.0 | 8.0 | 1.00x | 8 MB (truncated) |
| MockEngine, request body (POST ByteArray) | 50 MB | cap 16 MB | 7.88 / 31.01 | 7.81 | 16.0 | 16.0 | 1.00x | 16 MB (truncated) |

- Arms run interleaved in 4 rounds per cell, each arm with its own client; a new client every 20 requests bounds MockEngine's history.
- 'Δ alloc / cap' divides the extra allocation by the bytes the plugin may keep (min(cap, body)): a constant means the cost is bounded by the cap, not by the body.
- Allocation is summed over all live threads (JDK 17 has no total counter); engine and dispatcher threads live for the whole run.
