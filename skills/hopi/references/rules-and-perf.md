# Rules and performance: details

## Rules

The first enabled rule that matches a request wins (higher `--priority` first, then the newest).
Only the layer that records the request applies it, so a rule never applies twice. Rules live in
the app's memory only. An app restart removes them. `hopi clear` keeps them; `hopi rules clear`
removes them.

### Commands

```text
mock add  --match M --status N [--header 'K: V']... [--body B] [--from <id>] [--delay D]
mock add  --from <id> [--status N] ...        record <id>'s method and URL, any query
fail add  --match M --with timeout|reset|unknown-host|cancel [--after D]
delay add --match M [--before-request D] [--before-response D]
edit add  --match M [--method X] [--path P] [--set-query k=v]... [--remove-query k]...
          [--set-header 'K: V']... [--add-header 'K: V']... [--remove-header K]... [--body B]
          [--status N] [--set-response-header 'K: V']... [--add-response-header 'K: V']...
          [--remove-response-header K]... [--response-body B]
          [--host H --port N --allow-other-origin]   only with the user's agreement
rules ls | rm <id>... | clear | enable <id>... | disable <id>... | reset <id>...
rules set <id>... [--times N|none] [--priority N]
```

Every `add` also takes `--query`, `--match-header 'K: glob'`, `--times N`, `--priority N`,
`--source ktor,okhttp,urlconnection,urlsession`, `--label TEXT` and `--disabled`.

- `B` is `@file`, `@-` (stdin) or the text itself, up to 1 MiB.
- `D` is a duration: `500ms`, `2s`, at most `120s`.

### Match patterns

`M` is `[METHODS ]URL-PATTERN`:

| Pattern | Matches |
| --- | --- |
| `GET */users/*` | GET, any host, `/users/<one segment>` |
| `GET|POST /api/**` | GET or POST, any host, any path under `/api/` |
| `* *.cdn.example.com/**` | any method, any subdomain of the CDN |
| `GET https://api.example.com/v1/me` | that URL exactly |
| `GET /items?page=2&debug&!trace` | requires `page=2` and `debug`, forbids `trace` |

### What each source can do

| | Ktor plugin (Android, JVM) | OkHttp | `HttpURLConnection` | URLSession (iOS) |
| --- | --- | --- | --- | --- |
| mock, fail | yes | yes | yes | yes, except background sessions and sessions created before `start()` |
| delay before request | yes | yes | yes | yes |
| delay before response | yes | yes | yes | with a mock or fail only |
| edit request | yes | yes | yes | yes; a body only for `HTTPBody` and upload-from-data tasks |
| edit response | yes | yes | yes | no |

`add` prints `hopi: warning: …` for each part that a source cannot apply.

### Behavior to expect

- A mock or fail contacts no server.
- Every mock and response edit reaches the app with `Cache-Control: no-store`. A rule cannot
  exercise the app's own HTTP cache.
- A mocked 3xx, 401 or 407 is not followed by OkHttp, `HttpURLConnection` or URLSession. A Ktor
  client follows it.
- An edit to another host drops `Authorization`, `Cookie` and the redacted headers.
- A mock may not set a cookie.

## Performance monitors

```text
perf start    [--frames] [--hangs [--hang-ms 250]] [--cpu] [--memory] [--interval 1s]
              [--for 10m | --no-expiry]       the app stops them when the lease ends
perf stop     [--frames] [--hangs] [--cpu] [--memory]   none named: all
perf status   one line per monitor: setting, owners, lease, counts
perf snapshot [--threads] [--pss]             summaries, recent events, top threads
perf tail     [--since SEQ] [--samples] [--fps] [--threads] [--start hangs,frames]
hangs         [--since SEQ]                   every hang held, with main-thread stacks
```

What each monitor measures:

- **frames:** FPS and janky frames on Android (FrameMetrics). On iOS, main-thread stalls only.
- **hangs:** main-thread units of work over the threshold (250 ms by default), with the stack. On
  Android, input to a dialog's, sheet's or popup's own window is not bracketed: a block there is
  caught only with `perf { hangHeartbeat = true }`.
- **cpu:** the process, Hopi's own share and, with `--threads`, the top threads.
- **memory:** heaps, resident memory, GC counts, trim levels and memory warnings.

Good practice:

- Start only the monitors you need, run the scenario, take one `snapshot`, then `stop`.
- `perf tail --start hangs` holds the monitor only while the tail runs.
- Emulator and simulator frame times are not representative. Compare runs on the same target, or
  use a device.
- `xcrun simctl launch booted <bundle id> -hopiPerf frames,hangs` starts monitors at launch on iOS.

## Log stream (no CLI)

With no CLI, each finished request is one NDJSON line in the platform log:

```bash
adb logcat -s Hopi:I -v raw                    # Android; HopiPerf:I for perf lines
xcrun simctl spawn booted log stream --style ndjson --predicate 'subsystem == "dev.koga.hopi"'
```

Lines without `"id"` are notices. Skip them.
