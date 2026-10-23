### Core formatting per captured body (median / p95 ms, allocated bytes per call as a multiple of the captured size)

| shape | captured | textOrNull | alloc | isJson | prettyPrintJson | alloc | pretty out/in | toCurl (request body) | alloc |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| minified records | 256 KB | 0.08 / 0.52 | 1.00x | 0.00 / 0.00 | 1.27 / 10.79 | 5.39x | 1.64x | 0.10 / 0.53 | 4.01x |
| minified records | 1 MB | 0.22 / 0.72 | 1.00x | 0.00 / 0.00 | 3.92 / 4.30 | 5.38x | 1.63x | 0.39 / 0.97 | 4.00x |
| minified records | 5 MB | 1.31 / 1.35 | 1.00x | 0.00 / 0.01 | 18.49 / 20.90 | 5.37x | 1.62x | 4.23 / 5.48 | 4.00x |
| minified records | 16 MB | 5.48 / 6.79 | 1.00x | 0.00 / 0.00 | 65.29 / 68.97 | 5.37x | 1.62x | 18.59 / 22.98 | 4.00x |
| minified records | 16 MB of 20 MB | 4.14 / 4.72 | 1.00x | 0.00 / 0.00 | 46.37 / 50.89 | 3.75x | unchanged | 6.34 / 8.00 | 4.00x |
| pretty records | 256 KB | 0.05 / 0.09 | 1.00x | 0.00 / 0.00 | 0.94 / 1.03 | 2.38x | 1.13x | 0.26 / 0.32 | 4.01x |
| pretty records | 1 MB | 0.20 / 0.36 | 1.00x | 0.00 / 0.00 | 2.28 / 2.74 | 2.38x | 1.13x | 0.38 / 0.64 | 4.00x |
| pretty records | 5 MB | 1.04 / 1.18 | 1.00x | 0.00 / 0.00 | 11.05 / 11.73 | 2.38x | 1.13x | 1.79 / 2.66 | 4.00x |
| pretty records | 16 MB | 3.88 / 4.36 | 1.00x | 0.00 / 0.00 | 35.49 / 39.40 | 2.38x | 1.13x | 6.60 / 9.66 | 4.00x |
| pretty records | 16 MB of 20 MB | 3.58 / 4.47 | 1.00x | 0.00 / 0.00 | 34.99 / 36.55 | 1.25x | unchanged | 5.82 / 6.81 | 4.00x |
| deeply nested | 256 KB | 0.07 / 0.11 | 1.00x | 0.00 / 0.00 | 0.18 / 0.20 | 1.25x | unchanged | 0.25 / 0.28 | 4.00x |
| deeply nested | 1 MB | 0.20 / 0.21 | 1.00x | 0.00 / 0.00 | 0.14 / 0.15 | 1.25x | unchanged | 0.36 / 0.45 | 4.00x |
| deeply nested | 5 MB | 1.02 / 1.17 | 1.00x | 0.00 / 0.00 | 0.20 / 0.21 | 1.25x | unchanged | 1.78 / 2.57 | 4.00x |
| deeply nested | 16 MB | 3.39 / 3.67 | 1.00x | 0.00 / 0.00 | 0.37 / 1.04 | 1.25x | unchanged | 6.63 / 7.59 | 4.00x |
| deeply nested | 16 MB of 20 MB | 4.62 / 4.80 | 1.00x | 0.00 / 0.00 | 0.38 / 0.97 | 1.25x | unchanged | 7.89 / 12.13 | 4.00x |
| one huge string | 256 KB | 0.08 / 0.13 | 1.00x | 0.00 / 0.00 | 0.14 / 0.22 | 2.25x | 1.00x | 0.09 / 0.18 | 4.00x |
| one huge string | 1 MB | 0.21 / 0.54 | 1.00x | 0.00 / 0.00 | 0.62 / 3.84 | 2.25x | 1.00x | 0.39 / 4.49 | 4.00x |
| one huge string | 5 MB | 1.32 / 1.46 | 1.00x | 0.00 / 0.00 | 3.53 / 3.70 | 2.25x | 1.00x | 2.99 / 3.14 | 4.00x |
| one huge string | 16 MB | 4.40 / 4.43 | 1.00x | 0.00 / 0.00 | 11.35 / 11.43 | 2.25x | 1.00x | 7.97 / 9.01 | 4.00x |
| one huge string | 16 MB of 20 MB | 4.13 / 4.61 | 1.00x | 0.00 / 0.00 | 8.95 / 9.39 | 1.25x | unchanged | 10.16 / 13.10 | 4.00x |
| unicode CJK/emoji | 256 KB | 0.27 / 0.33 | 3.27x | 0.00 / 0.00 | 0.27 / 0.35 | 5.33x | 1.27x | 0.29 / 0.41 | 7.11x |
| unicode CJK/emoji | 1 MB | 0.97 / 1.08 | 3.27x | 0.00 / 0.00 | 1.03 / 1.16 | 5.33x | 1.27x | 1.22 / 1.80 | 7.10x |
| unicode CJK/emoji | 5 MB | 4.26 / 4.87 | 3.27x | 0.00 / 0.00 | 4.30 / 5.63 | 5.33x | 1.27x | 5.16 / 6.02 | 7.10x |
| unicode CJK/emoji | 16 MB | 13.64 / 14.50 | 3.27x | 0.00 / 0.00 | 14.20 / 14.60 | 5.33x | 1.27x | 17.22 / 17.44 | 7.10x |
| unicode CJK/emoji | 16 MB of 20 MB | 13.72 / 14.46 | 3.27x | 0.00 / 0.00 | 13.07 / 13.70 | 3.72x | unchanged | 18.51 / 20.75 | 7.10x |
| number array | 256 KB | 0.05 / 0.09 | 1.00x | 0.00 / 0.00 | 0.86 / 2.47 | 5.16x | 1.41x | 0.10 / 0.22 | 4.01x |
| number array | 1 MB | 0.20 / 0.28 | 1.00x | 0.00 / 0.00 | 3.21 / 3.37 | 5.15x | 1.40x | 0.36 / 0.45 | 4.00x |
| number array | 5 MB | 1.03 / 1.30 | 1.00x | 0.00 / 0.00 | 15.59 / 16.64 | 5.13x | 1.38x | 1.88 / 3.59 | 4.00x |
| number array | 16 MB | 3.56 / 5.99 | 1.00x | 0.00 / 0.00 | 51.05 / 54.27 | 5.12x | 1.37x | 6.59 / 7.53 | 4.00x |
| number array | 16 MB of 20 MB | 4.31 / 6.12 | 1.00x | 0.00 / 0.00 | 47.92 / 48.82 | 3.75x | unchanged | 5.94 / 6.95 | 4.00x |

- textOrNull = strict UTF-8 decode plus the NUL scan; allocation is per call on the calling thread (exact).
- 'unchanged' means prettyPrintJson returned its input: invalid (truncated) JSON or nesting beyond 256.
