### Core formatting per captured body (median / p95 ms, allocated bytes per call as a multiple of the captured size)

| shape | captured | textOrNull | alloc | isJson | prettyPrintJson | alloc | pretty out/in | toCurl (request body) | alloc | toCurl, 100k chars (copy, share) | alloc |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| minified records | 256 KB | 0.08 / 0.09 | 1.00x | 0.00 / 0.00 | 1.52 / 5.52 | 3.40x | 1.64x | 0.21 / 1.80 | 4.01x | 0.48 / 2.42 | 2.89x |
| minified records | 1 MB | 0.22 / 0.64 | 1.00x | 0.00 / 0.00 | 2.00 / 2.49 | 3.39x | 1.63x | 0.57 / 1.08 | 4.00x | 0.53 / 0.57 | 0.85x |
| minified records | 5 MB | 1.06 / 1.25 | 1.00x | 0.00 / 0.00 | 10.03 / 10.91 | 3.37x | 1.62x | 2.66 / 3.22 | 4.00x | 0.49 / 0.52 | 0.17x |
| minified records | 16 MB | 3.74 / 4.35 | 1.00x | 0.00 / 0.01 | 32.46 / 33.08 | 3.37x | 1.62x | 9.16 / 11.96 | 4.00x | 0.49 / 0.57 | 0.05x |
| minified records | 16 MB of 20 MB | 3.38 / 3.96 | 1.00x | 0.00 / 0.00 | 31.36 / 32.31 | 1.75x | unchanged | 9.07 / 9.22 | 4.00x | 0.49 / 0.51 | 0.05x |
| pretty records | 256 KB | 0.07 / 0.08 | 1.00x | 0.00 / 0.00 | 0.81 / 0.83 | 2.89x | 1.13x | 0.19 / 0.21 | 4.01x | 0.42 / 0.45 | 2.89x |
| pretty records | 1 MB | 0.20 / 0.23 | 1.00x | 0.00 / 0.00 | 1.69 / 1.89 | 2.88x | 1.13x | 0.57 / 0.98 | 4.00x | 0.48 / 0.54 | 0.85x |
| pretty records | 5 MB | 1.05 / 1.19 | 1.00x | 0.00 / 0.00 | 8.40 / 9.15 | 2.88x | 1.13x | 2.65 / 3.15 | 4.00x | 0.43 / 0.48 | 0.17x |
| pretty records | 16 MB | 3.37 / 4.42 | 1.00x | 0.00 / 0.00 | 27.19 / 27.76 | 2.88x | 1.13x | 9.06 / 9.12 | 4.00x | 0.43 / 0.43 | 0.05x |
| pretty records | 16 MB of 20 MB | 3.36 / 3.91 | 1.00x | 0.00 / 0.00 | 26.34 / 27.30 | 1.75x | unchanged | 9.07 / 9.19 | 4.00x | 0.43 / 0.43 | 0.05x |
| deeply nested | 256 KB | 0.05 / 0.05 | 1.00x | 0.00 / 0.00 | 0.16 / 0.20 | 2.32x | unchanged | 0.14 / 0.19 | 4.00x | 0.41 / 0.43 | 2.89x |
| deeply nested | 1 MB | 0.20 / 0.24 | 1.00x | 0.00 / 0.00 | 0.15 / 0.17 | 1.89x | unchanged | 0.53 / 1.05 | 4.00x | 0.43 / 0.46 | 0.85x |
| deeply nested | 5 MB | 1.02 / 1.14 | 1.00x | 0.00 / 0.00 | 0.23 / 0.26 | 1.78x | unchanged | 2.66 / 3.26 | 4.00x | 0.43 / 0.47 | 0.17x |
| deeply nested | 16 MB | 3.40 / 4.02 | 1.00x | 0.00 / 0.00 | 0.45 / 0.95 | 1.76x | unchanged | 8.95 / 9.15 | 4.00x | 0.49 / 0.56 | 0.05x |
| deeply nested | 16 MB of 20 MB | 3.38 / 3.91 | 1.00x | 0.00 / 0.00 | 0.46 / 0.95 | 1.76x | unchanged | 9.05 / 9.22 | 4.00x | 0.48 / 0.51 | 0.05x |
| one huge string | 256 KB | 0.07 / 0.08 | 1.00x | 0.00 / 0.00 | 0.19 / 0.21 | 2.76x | 1.00x | 0.14 / 0.15 | 4.00x | 0.40 / 0.44 | 2.89x |
| one huge string | 1 MB | 0.20 / 0.23 | 1.00x | 0.00 / 0.00 | 0.56 / 0.61 | 2.75x | 1.00x | 0.54 / 0.59 | 4.00x | 0.48 / 0.50 | 0.85x |
| one huge string | 5 MB | 1.04 / 1.09 | 1.00x | 0.00 / 0.00 | 2.86 / 3.35 | 2.75x | 1.00x | 2.68 / 3.36 | 4.00x | 0.44 / 0.47 | 0.17x |
| one huge string | 16 MB | 3.57 / 4.09 | 1.00x | 0.00 / 0.00 | 9.20 / 9.82 | 2.75x | 1.00x | 8.97 / 9.79 | 4.00x | 0.43 / 0.46 | 0.05x |
| one huge string | 16 MB of 20 MB | 3.36 / 3.96 | 1.00x | 0.00 / 0.00 | 8.40 / 9.00 | 1.75x | unchanged | 9.05 / 9.26 | 4.00x | 0.43 / 0.43 | 0.05x |
| unicode CJK/emoji | 256 KB | 0.21 / 0.26 | 3.27x | 0.00 / 0.00 | 0.15 / 0.21 | 3.85x | 1.27x | 0.34 / 0.40 | 7.11x | 0.94 / 1.84 | 7.82x |
| unicode CJK/emoji | 1 MB | 0.86 / 0.93 | 3.27x | 0.00 / 0.00 | 0.61 / 0.67 | 3.85x | 1.27x | 1.11 / 1.61 | 7.10x | 0.93 / 1.02 | 2.38x |
| unicode CJK/emoji | 5 MB | 4.29 / 4.73 | 3.27x | 0.00 / 0.00 | 3.06 / 3.58 | 3.85x | 1.27x | 5.50 / 6.04 | 7.10x | 0.94 / 0.99 | 0.48x |
| unicode CJK/emoji | 16 MB | 13.76 / 14.26 | 3.27x | 0.00 / 0.00 | 10.21 / 10.38 | 3.85x | 1.27x | 18.21 / 19.46 | 7.10x | 1.05 / 1.09 | 0.15x |
| unicode CJK/emoji | 16 MB of 20 MB | 14.07 / 15.24 | 3.27x | 0.00 / 0.00 | 9.70 / 9.91 | 2.23x | unchanged | 18.32 / 19.12 | 7.10x | 0.92 / 1.40 | 0.15x |
| number array | 256 KB | 0.05 / 0.06 | 1.00x | 0.00 / 0.00 | 0.75 / 2.37 | 3.17x | 1.41x | 0.14 / 0.15 | 4.01x | 0.57 / 0.64 | 2.89x |
| number array | 1 MB | 0.20 / 0.22 | 1.00x | 0.00 / 0.00 | 2.89 / 3.03 | 3.15x | 1.40x | 0.53 / 1.02 | 4.00x | 0.61 / 0.64 | 0.85x |
| number array | 5 MB | 1.02 / 1.05 | 1.00x | 0.00 / 0.00 | 14.12 / 14.60 | 3.13x | 1.38x | 2.64 / 3.15 | 4.00x | 0.61 / 0.63 | 0.17x |
| number array | 16 MB | 3.38 / 3.91 | 1.00x | 0.00 / 0.00 | 44.35 / 44.69 | 3.12x | 1.37x | 9.07 / 9.60 | 4.00x | 0.67 / 0.69 | 0.05x |
| number array | 16 MB of 20 MB | 3.38 / 3.99 | 1.00x | 0.00 / 0.00 | 43.18 / 43.76 | 1.75x | unchanged | 9.02 / 9.06 | 4.00x | 0.66 / 0.70 | 0.05x |

- textOrNull = strict UTF-8 decode plus the NUL scan; allocation is per call on the calling thread (exact).
- 'unchanged' means prettyPrintJson returned its input: invalid (truncated) JSON or nesting beyond 256.
- toCurl, 100k chars: the bound the UI's copy cURL and share use (maxChars = 100 000); it decodes and quotes only what fits.
