### Store under a flood of large transactions (default 500 entries; body sizes and byte caps as listed)

| body | maxTotalBytes | consumer | submitted | submit median / p95 (µs) | retained entries | store bytes (MB) | retained heap delta (MB) | retained beyond cap (MB) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 256 KB | 16 MB | running | 200 | 3.8 / 14.5 | 64 | 16.0 | 16.0 | 0.0 |
| 1 MB | 16 MB | running | 200 | 2.8 / 12.0 | 16 | 16.0 | 32.0 | 16.0 |
| 8 MB | 32 MB | running | 80 | 2.6 / 11.8 | 4 | 32.0 | 36.0 | 4.0 |
| 16 MB | 32 MB | running | 40 | 4.4 / 20.5 | 2 | 32.0 | 34.0 | 2.0 |
| 20 MB | 32 MB | running | 32 | 5.9 / 8.5 | 1 | 20.0 | 21.0 | -11.0 |
| 8 MB | 32 MB | stalled | 32 | 0.2 / 0.6 | 4 | 32.0 | 288.0 | 256.0 |

- Each case runs in its own frame and waits for its store's consumer to end, so nothing of the previous case is reachable; deltas are against a full GC before the case.
- G1 stores an array of 1 MB or more as a humongous object rounded up to whole 1 MB regions (a 1 MB array takes 2 MB), which is most of the 'beyond cap' column on this JVM; ART has no such rounding.
- 'stalled' blocks the store's consumer thread while submitting: the retained heap is then every queued body (the terminal channel holds up to 4096 snapshots), measured before the consumer is released.
