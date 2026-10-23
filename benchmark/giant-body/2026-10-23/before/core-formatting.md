### Core formatting per captured body (median / p95 ms, allocated bytes per call as a multiple of the captured size)

| shape | captured | textOrNull | alloc | isJson | prettyPrintJson | alloc | pretty out/in | toCurl (request body) | alloc |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| minified records | 256 KB | 0.34 / 0.37 | 3.00x | 0.00 / 0.00 | 1.67 / 2.33 | 5.39x | 1.64x | 0.43 / 1.61 | 9.01x |
| minified records | 1 MB | 1.21 / 2.30 | 3.00x | 0.00 / 0.00 | 2.69 / 3.76 | 5.38x | 1.63x | 1.51 / 3.28 | 9.00x |
| minified records | 5 MB | 5.69 / 6.52 | 3.00x | 0.00 / 0.00 | 13.07 / 13.59 | 5.37x | 1.62x | 6.85 / 7.52 | 9.00x |
| minified records | 16 MB | 18.52 / 19.34 | 3.00x | 0.00 / 0.01 | 42.68 / 46.93 | 5.37x | 1.62x | 25.66 / 56.33 | 9.00x |
| minified records | 16 MB of 20 MB | 18.09 / 19.12 | 3.00x | 0.00 / 0.00 | 41.19 / 42.77 | 3.75x | unchanged | 22.32 / 22.62 | 9.00x |
| pretty records | 256 KB | 0.34 / 0.38 | 3.00x | 0.00 / 0.00 | 0.92 / 1.00 | 2.38x | 1.13x | 0.55 / 0.92 | 9.01x |
| pretty records | 1 MB | 1.20 / 1.43 | 3.00x | 0.00 / 0.00 | 2.13 / 2.19 | 2.38x | 1.13x | 1.49 / 2.13 | 9.00x |
| pretty records | 5 MB | 5.68 / 6.29 | 3.00x | 0.00 / 0.00 | 10.56 / 10.74 | 2.38x | 1.13x | 6.84 / 7.52 | 9.00x |
| pretty records | 16 MB | 20.08 / 22.85 | 7.00x | 0.00 / 0.00 | 34.31 / 38.66 | 2.38x | 1.13x | 24.12 / 24.29 | 13.00x |
| pretty records | 16 MB of 20 MB | 18.22 / 18.69 | 3.00x | 0.00 / 0.00 | 33.30 / 33.57 | 1.25x | unchanged | 22.24 / 22.63 | 9.00x |
| deeply nested | 256 KB | 0.32 / 0.36 | 3.00x | 0.00 / 0.00 | 0.20 / 0.25 | 1.25x | unchanged | 0.50 / 0.66 | 8.00x |
| deeply nested | 1 MB | 1.13 / 1.17 | 3.00x | 0.00 / 0.00 | 0.17 / 0.19 | 1.25x | unchanged | 1.33 / 1.40 | 8.00x |
| deeply nested | 5 MB | 5.68 / 6.20 | 3.00x | 0.00 / 0.00 | 0.20 / 0.24 | 1.25x | unchanged | 6.60 / 7.12 | 8.00x |
| deeply nested | 16 MB | 18.21 / 18.72 | 3.00x | 0.00 / 0.00 | 0.34 / 0.41 | 1.25x | unchanged | 21.89 / 22.12 | 8.00x |
| deeply nested | 16 MB of 20 MB | 18.37 / 19.53 | 3.00x | 0.00 / 0.00 | 0.35 / 0.43 | 1.25x | unchanged | 22.28 / 22.65 | 9.00x |
| one huge string | 256 KB | 0.33 / 0.44 | 3.00x | 0.00 / 0.00 | 0.19 / 0.21 | 2.25x | 1.00x | 0.46 / 0.74 | 8.00x |
| one huge string | 1 MB | 1.15 / 1.30 | 3.00x | 0.00 / 0.00 | 0.56 / 0.66 | 2.25x | 1.00x | 1.34 / 1.88 | 8.00x |
| one huge string | 5 MB | 5.69 / 5.80 | 3.00x | 0.00 / 0.00 | 2.78 / 3.30 | 2.25x | 1.00x | 6.63 / 7.22 | 8.00x |
| one huge string | 16 MB | 18.24 / 20.03 | 3.00x | 0.00 / 0.00 | 9.04 / 9.55 | 2.25x | 1.00x | 21.76 / 22.88 | 8.00x |
| one huge string | 16 MB of 20 MB | 18.18 / 19.22 | 3.00x | 0.00 / 0.01 | 8.17 / 8.99 | 1.25x | unchanged | 22.09 / 22.77 | 9.00x |
| unicode CJK/emoji | 256 KB | 0.42 / 0.82 | 3.27x | 0.00 / 0.00 | 0.38 / 5.67 | 5.33x | 1.27x | 0.70 / 0.93 | 10.08x |
| unicode CJK/emoji | 1 MB | 1.48 / 1.59 | 3.27x | 0.00 / 0.00 | 0.99 / 1.05 | 5.33x | 1.27x | 1.65 / 1.75 | 10.07x |
| unicode CJK/emoji | 5 MB | 6.99 / 7.39 | 3.27x | 0.00 / 0.00 | 4.14 / 4.63 | 5.33x | 1.27x | 8.22 / 8.76 | 10.07x |
| unicode CJK/emoji | 16 MB | 22.54 / 25.05 | 3.27x | 0.00 / 0.00 | 13.44 / 13.95 | 5.33x | 1.27x | 26.83 / 27.63 | 10.07x |
| unicode CJK/emoji | 16 MB of 20 MB | 22.24 / 22.96 | 3.27x | 0.00 / 0.00 | 12.89 / 13.27 | 3.72x | unchanged | 26.71 / 26.95 | 10.07x |
| number array | 256 KB | 0.32 / 0.75 | 3.00x | 0.00 / 0.00 | 0.78 / 2.38 | 5.16x | 1.41x | 0.55 / 0.85 | 9.01x |
| number array | 1 MB | 1.18 / 1.29 | 3.00x | 0.00 / 0.00 | 2.94 / 3.14 | 5.15x | 1.40x | 1.34 / 1.89 | 9.00x |
| number array | 5 MB | 5.54 / 6.18 | 3.00x | 0.00 / 0.00 | 14.19 / 14.97 | 5.13x | 1.38x | 6.68 / 7.25 | 9.00x |
| number array | 16 MB | 17.74 / 19.05 | 3.00x | 0.00 / 0.00 | 44.28 / 44.84 | 5.12x | 1.37x | 21.94 / 22.45 | 9.00x |
| number array | 16 MB of 20 MB | 17.70 / 18.46 | 3.00x | 0.00 / 0.00 | 43.54 / 43.96 | 3.75x | unchanged | 21.70 / 22.08 | 9.00x |

- textOrNull = strict UTF-8 decode plus the NUL scan; allocation is per call on the calling thread (exact).
- 'unchanged' means prettyPrintJson returned its input: invalid (truncated) JSON or nesting beyond 256.
