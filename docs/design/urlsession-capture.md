# Design: automatic URLSession capture on iOS

Status: built (2026-10-24); the deviations from the design below are listed at the end, under
"As built". Part of the owner requirement "capture every HTTP request the app
makes, including third-party SDKs, with no per-client configuration". This document covers the
iOS part (URLSession); the Android part (OkHttp, `HttpURLConnection`) has its own design. Both
write into the existing `HopiStore`, so the NDJSON log line, the bridge/CLI and the desktop viewer
(and the in-app screen, until the runtime-split plan's stage 5 removed it) show the new records
with no change beyond the `source` field below.

**History note (2026-10-30).** The runtime-split plan (`docs/design/api-runtime-split-plan.md`)
removed `hopi-urlsession`, its noop twin `hopi-urlsession-noop` and the other twins (stages 5 to
10, 2026-10-28 and 29), then the parity check `:checkNoopParity` (stage 11, 2026-10-30). The
sections it changed say so with their stage; every other passage that names a noop, a twin or
parity (the architecture contract changes, the tests, the A/B method, the implementation steps,
"As built" and "Hardening") records the design as built before it and is kept as history. The
contract is `docs/ARCHITECTURE.md`.

## Goals and non-goals

Goals:

- One call at startup, from Kotlin or Swift, in debug builds: since the runtime-split plan's stage
  6 (2026-10-28) `hopi-start`'s `Hopi.start()` (`Hopi.shared.start()` in Swift), which also
  registers the performance monitors, and since stage 9 (2026-10-29) the only one (the design's
  own start, below, was removed with its module). No per-session, per-client or per-SDK setup.
- Every in-process URLSession request is recorded once: completion-handler, delegate and
  async/await APIs, any delegate shape (custom, partial, nil, `NSProxy`), `URLSession.shared`,
  default and ephemeral configurations, sessions created before `start()`, and the Ktor Darwin
  engine.
- Observe only. No request is re-issued, and nothing about the request or response changes:
  bytes, timing, callback order, queues, cookies, caching, redirects, authentication, HTTP/2,
  metrics, `respondsToSelector:` answers, delegate identity, `protocolClasses`.
- Bounded: the response body is kept up to `maxBodyBytes` and only counted past it. The app's
  threads do no copying; the main thread does no Hopi work at all.
- Release builds contain none of this code, checked automatically: since stage 9 of the
  runtime-split plan (2026-10-29) they get no runtime and no `hopi-start` (as designed, the noop
  twin), and the iOS release guard fails a Release link that would hold one.

Non-goals (v1): WebSocket frames, `NSURLSessionStreamTask`, AVFoundation media loading, WKWebView,
`NSURLConnection`, raw CFNetwork/BSD sockets, gRPC or Cronet stacks, physical iOS devices for
the bridge (unchanged from today).

## What the existing tools do, and what we take

| Tool | Mechanism | Re-issues requests | Verdict |
| --- | --- | --- | --- |
| DebugSwift, Netfox, Wormholy | A `URLProtocol` subclass injected into `protocolClasses` (and the session configuration swizzled so it is always there), which re-sends each request through a session of its own and replays the result to the app | Yes | Rejected. Doubles the work per request, changes timing, and changes caching, cookies, redirects, authentication challenges, HTTP/2 and metrics, because the app's session no longer talks to the network. This is the cause of DebugSwift's slowness. |
| FLEX (`FLEXNetworkObserver.m`) | Exchanges `-resume` (on `NSURLSessionTask` on iOS 14+, `__NSCFURLSessionTask` on 9 to 13), wraps the completion-handler factories (`dataTaskWithRequest:completionHandler:`, `dataTaskWithURL:completionHandler:`, the download and upload `…completionHandler:` variants, on `NSURLSession` and `__NSURLSessionLocal`), and scans every class (`objc_copyClassList`) for delegate methods to wrap | No | Partly. It **adds** delegate methods a class lacks (with `didReceiveResponse:completionHandler:` answering `Allow`) and overrides `respondsToSelector:` on delegate classes, which changes what the app's delegate answers. The class scan costs startup time. It has no async/await support. On a download it reads the whole file into memory. |
| Pulse, `URLSessionProxyDelegate` automatic registration | Exchanges `+[NSURLSession sessionWithConfiguration:delegate:delegateQueue:]`, replaces the delegate with a forwarding proxy, and **prepends a `URLProtocol` (`MockingURLProtocol`) to `protocolClasses`** | No, but it changes the configuration | Rejected. It changes delegate identity, `respondsToSelector:` (the proxy answers YES for its own methods) and `protocolClasses`. It sees delegate-based sessions only. |
| Pulse, `NetworkLogger.enableProxy()` (`PulseProxy/URLSessionSwizzler.swift`) | `method_setImplementation` on `-[NSURLSessionTask resume]` (and `__NSCFURLSessionTask`), plus two private methods of CFNetwork's per-task connection object `__NSCFURLLocalSessionConnection`: `_didReceiveData:` and `_didFinishWithError:`. Each calls the original first, then reads the task through KVC (`task`) and the metrics through `_incompleteTaskMetrics` | No | **Adopted as the primary path.** Every in-process ("local") task's bytes go through this object, whatever API created the task and whatever delegate the session has, so one pair of hooks covers everything without touching any app class. |

### Spike evidence (2026-10-24)

A throwaway Swift program (not kept in the repo; the test plan below turns it into real tests)
replaced `_didReceiveData:` and `_didFinishWithError:` on `__NSCFURLLocalSessionConnection`
(original first, then count) and ran against a loopback HTTP server, on macOS 27.0.1 and on the
iPhone 16 simulator, iOS 18.2 (`xcrun simctl spawn`, a bare executable: plain `http` to
`127.0.0.1` needs no ATS exception there). Both gave the same result:

- Type encodings: `_didReceiveData:` `v24@0:8@16`, `_didFinishWithError:` `v24@0:8@16`.
- Every case delivered the whole 300,000-byte body to the hook, and `_didFinishWithError:` ran
  exactly once per task: `URLSession.shared` with a completion handler, an ephemeral session with
  a full data delegate on `delegateQueue: .main`, a partial delegate (`URLSessionTaskDelegate`
  with no methods) plus a completion handler, a nil-delegate fire-and-forget task, a download
  task with a completion handler, an upload with data, and Swift `data(for:)`, `data(from:)`,
  `upload(for:from:)` and `bytes(for:)`.
- No hook call ran on the main thread, even for the session whose delegate queue is `.main`: the
  connection runs on CFNetwork's own work queue and the delegate queue is served afterwards.
- `_didFinishWithError:` runs while `task.state` is still `running` (0), before the app's
  completion handler and delegate: it is the network's end, not the app's.
- `_incompleteTaskMetrics` answered with metrics; `networkProtocolName` read `http/1.1`.
- Ivars: the connection's task is `_task` (`@"NSURLSessionTask"`) on the superclass
  `__NSCFURLSessionConnection`; the data passed in is `OS_dispatch_data` (`_pendingData`,
  `_sniffData`), which bridges to `NSData` without a copy.
- Upload bodies: `originalRequest.HTTPBody` of `uploadTaskWithRequest:fromData:` is nil; the data
  is not reachable from the task. The async `upload(for:from:)` goes through the private
  `-[NSURLSession _uploadTaskWithRequest:fromData:delegate:completionHandler:]`
  (`@48@0:8@16@24@32@?40`); the public `uploadTaskWithRequest:fromData:completionHandler:` and
  `uploadTaskWithRequest:fromData:` do not route through it.
- `URLSession.shared` is a `__NSURLSessionLocal`; background sessions are `__NSURLBackgroundSession`
  (out of process, so they do not use the local connection class). Task classes: a local data
  task is `__NSCFLocalDataTask` (superclass `__NSCFLocalSessionTask`), a background one
  `__NSCFBackgroundDataTask` (`__NSCFBackgroundSessionTask`).

A second spike on the same simulator covered failures, cancels and redirects:

- `_didFinishWithError:` ran exactly once for connection refused (`-1004`), an unresolvable host
  (`-1003`), `cancel()` right after `resume()` (`-999`, before any byte) and `cancel()` mid-body
  (`-999`, status 200 kept), each before the app's completion handler. A local task therefore needs
  no second terminal signal.
- A `302` to `/final` followed natively used **one** connection object: one `_didFinishWithError:`
  with the final status 200 and `currentRequest.URL` = `/final`, so one task is one record.
- **`_didReceiveData:` can see the same chunk twice.** The redirect delivered its 10-byte body to
  the hook twice (the app received 10 bytes), and the mid-body cancel showed a nested call: the
  connection's own methods call `_didReceiveData:` again, from inside the first call or later,
  with the **same** `dispatch_data` object, which they park in the `_pendingData` ivar. Counting
  every call would double bytes. The rule below (identity dedupe) handles both shapes seen.

The klib of `ktor-client-darwin` 3.5.2 (`DarwinRequestUtils.kt`, `DarwinUtils.toDataOrStream`)
confirms that Ktor Darwin sends a `ByteArrayContent` as `HTTPBody` (an `NSData`) and a
`ReadChannelContent`/`WriteChannelContent` as `HTTPBodyStream` (bound streams), that its
`KtorNSURLSessionDelegate` implements `URLSession:dataTask:didReceiveData:` and
`URLSession:task:didCompleteWithError:`, and that it creates tasks with `dataTaskWithRequest:`.

## Interception points

All hooks are installed once, by `start()`. There is no `+load`, no ObjC category, and no class
scan, so nothing runs before `start()` and consumers need neither `-ObjC` nor `-force_load`.

| # | Hook | Kind | What it does |
| --- | --- | --- | --- |
| 1 | `-[NSURLSessionTask resume]`, on every class in the task class chains that defines its own `resume` | public selector | Creates the per-task state on the first resume of an `http`/`https` task created by a local session (a task already resumed before `start()` and suspended since, seen by a response or a byte count, is left alone) (a task whose class is not a `__NSCFBackgroundSessionTask` takes the byte path; looked up once at `start()`), retains `originalRequest`, takes the start time, and posts "started" to the capture queue. The state is built whole and published last, under one lock, so a concurrent `resume` of the same task sees none or all of it. Never changes the call. |
| 2 | `-[__NSCFURLLocalSessionConnection _didReceiveData:]` | private | After the original, ignores a call whose data object is identical to one already counted for this task (the replays above), else retains the chunk (zero copy) while the kept bytes are below the cap and past the cap only adds its length. |
| 3 | `-[__NSCFURLLocalSessionConnection _didFinishWithError:]` | private | After the original, reads `task.response`, the error, `countOfBytesSent`, `currentRequest` and the metrics' protocol, takes the end time, and posts "finished" to the capture queue. |
| 4 | `-[NSURLSession uploadTaskWithRequest:fromData:]`, `uploadTaskWithRequest:fromData:completionHandler:`, `_uploadTaskWithRequest:fromData:delegate:completionHandler:` (each on the class that defines it) | public and private | After the original returns, attaches the request body to the returned task, unless the task already has one (checked before anything is copied, so a public method that calls the private one on another OS version copies once): `[data copy]` (O(1) for an immutable `NSData`), except for a mutable body over the cap, which keeps a copy of its first `maxBodyBytes` and its whole length, so a large `NSMutableData` costs at most the cap on the calling thread. Hook 1 moves it to the task's state and clears it from the task. The return value is passed through untouched. |
| 5 | (fallback only) KVO on `task.state` | public | Used only by the metadata path below: a terminal signal for tasks whose bytes do not go through hook 2 and 3. |

### Two paths

- **Byte path (local sessions).** Hooks 1 to 4. Everything is captured: request, response,
  status, headers, bodies up to the cap, size, protocol, timing, error.
- **Metadata path.** Background-session tasks (out of process), and every task if the self-check
  below fails (a future iOS that renames the private class or changes an encoding). Hook 1 creates
  the state, hook 5 gives the end. The record has request, status, headers, sizes
  (`countOfBytesReceived`/`countOfBytesSent`), timing and error; bodies are skipped with reason
  `"not observable"`. KVO is used only here: it adds an isa-swizzled subclass to the task
  instance, which the byte path never does.

**Self-check at `start()`.** The byte path is enabled only if `__NSCFURLLocalSessionConnection`
exists, both selectors resolve with exactly the encodings above, and `__NSCFURLSessionConnection`
has an ivar `_task` of type `@"NSURLSessionTask"` (its `Ivar` is cached; the hooks read it with
`object_getIvar`, not KVC). Otherwise Hopi logs one notice line
(`{"hopi":1,"notice":"urlsession: byte capture unavailable on this OS, recording metadata only"}`)
and uses the metadata path everywhere. Hopi never calls a private selector whose encoding it has
not verified.

### Why not the public delegate and completion-handler route

It would need FLEX's approach (hook the delegate classes and wrap the completion handlers) plus
`task.delegate` for async/await, and it still misses data delivered to a delegate that does not
implement `didReceiveData:`, unless the method is added, which changes what `respondsToSelector:`
answers. The connection hooks see the bytes once, below every API, on CFNetwork's own queue. The
cost is a private-API dependency, which the self-check, the metadata fallback and the per-OS test
run turn into a visible degradation instead of a crash. This is a debug-only library, absent from
release builds, so App Store review never sees it.

Recorded URL: `originalRequest.URL`, what the app asked for, as the Ktor plugin's recorder (on
Android and the JVM) records the URL as sent. After a native redirect the status, headers and
body are the final hop's; the detail view shows the final URL (`currentRequest.URL`) on a
separate line when it differs, carried in the transaction as `finalUrl` (nullable, additive on
the wire like `source`). CFNetwork rewrites `currentRequest` even without a redirect: it
lowercases the scheme and host and writes an empty
path as `/` (checked: dot segments, escapes, user info and fragments stay as written), so both
URLs are compared in that canonical form and `finalUrl` is set only when a redirect changed the
URL. A redirect to the same URL in another case is not shown.

### Coverage

| Case | Covered | Notes |
| --- | --- | --- |
| `dataTaskWithURL:`/`Request:` with or without a completion handler | Byte path | |
| `uploadTaskWithRequest:fromData:` (both), async `upload(for:from:)` | Byte path | Request body from hook 4 |
| `uploadTaskWithRequest:fromFile:` (both), async `upload(for:fromFile:)` | Byte path | Request body skipped, `"file, not captured"`, size from `countOfBytesExpectedToSend` |
| `uploadTaskWithStreamedRequest:`, a request with `HTTPBodyStream` | Byte path | Request body skipped, `"stream, not captured"`: a stream is single-use, reading it would consume the app's body |
| Download tasks (`downloadTaskWith…`, async `download(for:)`) | Byte path, metadata body | Response body skipped, `"download"`, `totalBytes` = bytes received. The file is never opened, and no chunk is retained. |
| Delegate sessions: custom, partial, nil, `NSProxy`, subclasses that call `super` | Byte path | No delegate is touched, so the delegate's shape does not matter |
| `URLSession.shared`, default and ephemeral configurations | Byte path | |
| Swift `data(for:)`, `data(from:)`, `upload(for:from:)`, `bytes(for:)`, `download(for:)` | Byte path | Spike covered all but `download(for:)`; the Swift test target covers all five |
| Sessions created before `start()` | Byte path for every task **resumed** after `start()` | Hooks are class-level and need no session or delegate. A task resumed before `start()` is not recorded (a chunk for a task without state is ignored), nor is one resumed before, suspended, and resumed after it (it would show part of the transfer as all of it). A task created before and resumed after `start()` is recorded, but an upload-from-data body created before `start()` is skipped as `"not observed"`. |
| Ktor Darwin engine | Byte path | One record per request, see Dedupe (since the runtime-split plan's stage 10 the Ktor plugin installs nothing on iOS); under `ContentEncoding` the body is recorded decoded, as the app reads it (stage 10's R3, see Dedupe) |
| Background sessions | Metadata path | While the app runs. Tasks finished while the app was suspended or relaunched by the system are not recorded. |
| `NSURLSessionWebSocketTask` | Not recorded in v1 | Skipped at resume (class check). Frames and the handshake are a later "WebSocket inspection" item. |
| `NSURLSessionStreamTask` | Not recorded | Not HTTP |
| `data:`, `file:` and custom-scheme loads (including an app `URLProtocol` for its own scheme) | Not recorded | Skipped at resume (scheme check): not HTTP, they end with a plain `NSURLResponse`. An `http(s)` load that an app `URLProtocol` answers with a plain `NSURLResponse` is still recorded, as Failed `"no HTTP response"`: a record has no "complete without a status". |
| AVPlayer / AVAssetDownloadTask | Not recorded | Media loading runs out of process (mediaserverd). `AVAggregateAssetDownloadTask` is skipped at resume, as FLEX does. |
| WKWebView, `NSURLConnection`, CFNetwork streams, BSD sockets, Cronet, gRPC | Not recorded | Out of process or not URLSession |
| Responses from an app's own `URLProtocol` (OHHTTPStubs and the like), cache hits | Expected byte path | Spike item: both are expected to go through the connection's loader, to be confirmed by tests |

## Per-task state and lifetime

One `HopiUSTask` object (ObjC, ARC) per recorded task:

- `localId` (atomic 64-bit counter in the library), `storeId` (set on the capture queue),
- `seen`: the identities of counted chunks. Below the cap they are the retained chunks
  themselves; past the cap a ring of the last 4 counted chunk objects (retained, so an address is
  never reused while it is compared), which bounds what replays can hide behind,
- `startWallMs` and `startNs` (`clock_gettime_nsec_np(CLOCK_UPTIME_RAW)`), `endNs`,
- `request` (retained `originalRequest`; immutable), `uploadBody` (retained `NSData`, or nil),
  `requestBodyKind` (data, stream, file, none),
- `chunks`: retained `dispatch_data_t` regions, `keptBytes`, `totalBytes` (exact count of every
  byte received), `captureBody` (NO for download tasks),
- at the end: `response`, `error`, `currentRequest`, `bytesSent`, `bytesReceived`
  (`countOfBytesReceived`, public and authoritative: it becomes the body's `totalBytes`; when it is
  lower than the kept bytes, a replay escaped the dedupe and the body is cut to it and marked
  truncated, and a test-only counter records the event), `protocolName`,
- `lock` (`os_unfair_lock`), `finished` (atomic flag: exactly one "finished" per task).

Lifetime: the state is attached to the task with
`objc_setAssociatedObject(task, &key, state, OBJC_ASSOCIATION_RETAIN_NONATOMIC)` at the first
resume (the attach is checked and set under one global `os_unfair_lock`, so a second `resume`, a
subclass `resume` calling `super`, or two threads racing to resume leave one state). The
connection hooks find it through `object_getIvar(connection, taskIvar)`, then the associated
object: no map, nothing to remove, and the state dies with the task. The capture queue holds its
own reference from "finished" until the Kotlin side has built the terminal snapshot, then drops
`chunks`, `request` and `uploadBody`, so a retained body never outlives its record's construction.

Memory bound per task in flight: the kept regions are at most `maxBodyBytes`, plus the 4-chunk
dedupe ring past the cap. Instead of retaining the whole region that crosses the cap, the hook
retains `dispatch_data_create_subrange` of the part below the cap, which shares the buffer without
copying it (for a plain `NSData` that is not a `dispatch_data`, which an app's `URLProtocol` may
deliver, `subdataWithRange:` of at most the remaining cap, the only bounded copy on that thread).
The buffer itself is CFNetwork's, which it releases after delivering to the app anyway.

## Threading

- **App threads** (whatever thread calls `resume` or an upload factory, which may be the main
  thread): allocate the state, two retains, one associated-object set, one `dispatch_async`.
  No headers are read and Kotlin is not called. The only body bytes copied are an upload
  factory's `fromData:` argument that is not immutable, at most `maxBodyBytes` of it.
- **CFNetwork's connection queue** (hooks 2 and 3, never the main thread, per the spike): a lock,
  a retain and an array append per chunk below the cap, an addition past it; at the end, a few
  property reads and one `dispatch_async`.
- **Capture queue**: one serial `dispatch_queue_t` (`dev.koga.hopi.urlsession`,
  `QOS_CLASS_UTILITY`). The Kotlin callbacks run only here: they build `HttpTransaction`s, copy
  the request body and the kept response regions into one `ByteArray` each, and call the store's
  non-suspending `submit`. Being serial gives "started before finished" for each task for free. It
  creates no thread and wakes nothing while no request is running: idle cost stays zero. With no
  viewer attached the store applies a record's end within its 50 ms batch window instead of
  waking its consumer per request (ARCHITECTURE.md, "Store"), so the os_log summary line can come
  up to 50 ms after the task ended; with the bridge connected nothing waits. An uncaught
  Objective-C exception and `exit()` wait at most 200 ms for the store to apply and log what
  waited (chained `NSSetUncaughtExceptionHandler`, `atexit`); `abort()`, a Swift trap and an
  uncaught Kotlin exception end the process with no handler Hopi can run, so they can still lose
  the lines of the last 50 ms.

Order of hooks against the app's callbacks: hooks 2 and 3 call the original first and then
observe, so the app's delegate and completion-handler order, and the queue they run on, are
CFNetwork's own. `durationMs` runs from `resume` to `_didFinishWithError:`, the network's end; it
excludes the time the app's delegate queue takes to drain, unlike the Ktor plugin's duration,
which ends when the app's reader takes in the body. The detail view labels it as "network".

## Swizzling rules

- `method_setImplementation` with `imp_implementationWithBlock`, keeping the previous IMP and
  calling it through a typed function pointer with the original `_cmd`. Never
  `method_exchangeImplementations`, never `class_addMethod` for a selector a class does not
  already respond to, never `respondsToSelector:`/`forwardingTargetForSelector:` overrides. No app
  or SDK class is touched; only `NSURLSessionTask`'s class chain, `NSURLSession`'s upload
  factories and CFNetwork's connection class.
- `resume`: the defining classes are found from probe tasks of each kind (data, upload, download)
  created from a private ephemeral session that is `invalidateAndCancel`ed right after, never resumed, walking each class chain with
  `class_copyMethodList` (not `class_getInstanceMethod`, which cannot tell inherited from defined).
  Each defining class gets its IMP replaced once; a subclass `resume` that calls `super` reaches
  hook 1 twice for the same task, and the second visit finds the state and passes through.
- Coexistence: a swizzler installed before Hopi (Firebase Performance, Pulse, FLEX, AFNetworking's
  `af_resume`) is called as Hopi's previous IMP; one installed after calls Hopi's IMP as its own
  previous one. Both work whether the other uses `method_setImplementation` or
  `method_exchangeImplementations`, because Hopi passes its own captured selector to the previous
  IMP, not `_cmd`. Pulse's `enableProxy` replaces the same two connection methods: chained the same
  way, both see the bytes. Pulse's and DebugSwift's `URLProtocol` modes re-issue requests, and
  Hopi then records both their copy and the original, as it should: both hit the network.
- `start()` is idempotent and thread-safe: all installation runs inside one `dispatch_once`; a
  second call returns at once and keeps the first configuration (logged in the notice of the second
  call when the config differs).
- `method_setImplementation` is atomic; a thread inside an old IMP while `start()` runs finishes
  with it, so a task resumed during `start()` may be missed, never recorded twice.

## Dedupe with the Ktor plugin

**Since the runtime-split plan's stage 10 (2026-10-29) this section is history on iOS.** The Ktor
plugin is thin: its recorder is `hopi-network`'s, which the network tool registers on Android and
`Hopi.start` on the JVM, and on iOS nothing registers it, so the plugin installs nothing there and
nothing defers. `UrlSessionCapture.start` covers no engine any more (its `cover` call and
`KTOR_DARWIN_ENGINE_PACKAGE` were deleted): URLSession capture records Ktor's Darwin engine as any
URLSession client, once, and another engine (CIO) is not recorded on iOS. The in-flight
withdrawal below (`HopiStore.withdraw`) is kept in the recorder, unreachable outside tests, for a
recorder that would cover an engine late (Android's network tool covers before it registers the
recorder). The plugin's own settings (`redactHeaders`, `logSink`, `agentBridge`, the caps) do
nothing on iOS: `Hopi.start { }` holds the store's. As designed and first built:

Constraint: one record per request, without changing the wire (no marker header) and without
changing any observable app state (no `taskDescription`, no `NSURLProtocol` property on a request
Hopi does not own).

**Rule: platform capture is the source of truth for the engines it covers; the Ktor plugin
defers.**

- `hopi-core` gains `PlatformCapture` (`@HopiInternalApi`): `cover(enginePackage: String)` and
  `covers(engineClassName: String?): Boolean`, an atomic set. The start (`UrlSessionCapture.start`,
  which the network tool calls) calls `cover("io.ktor.client.engine.darwin.")` once the byte path
  or the metadata path is installed.
- The Ktor plugin reads its client's engine class name once at install
  (`client.engine::class.qualifiedName`, supported on Kotlin/Native), and on each send, at
  `HttpSendPipeline.Monitoring`, checks `PlatformCapture.covers(name)` (one atomic read). When it is
  covered, the plugin proceeds without recording: no transaction, no tee, no request copy. It marks
  the call with a request attribute (Ktor `Attributes`, in memory, never sent), so its receive
  pipeline interceptors skip that call too. The
  install-time work still happens (store caps, redaction union, log sink, bridge), so a client's
  `redactHeaders`, `logSink` and `agentBridge` keep their meaning.
- The decision is per request, so `start()` after the client was built takes effect at the next
  request. A request already past `Monitoring` when `start()` runs is not recorded twice
  (2026-10-27; before, one resumed after the hooks was recorded twice, once per recorder).
  `start()` covers the engine before `hopi_us_start` installs the hooks, and the plugin reads the
  coverage once more when the engine answers: at the first response hook, before anything decides
  the record's end (a teed or decoded body can end it before the send pipeline returns), or at a
  failure before any response. One more atomic read per request. Still not covered then, the task
  was resumed before the hooks, so the plugin's record is the only one. Covered by then, the resume
  hook may have seen the task and the plugin cannot tell, so it withdraws its record
  (`HopiStore.withdraw`): the request is recorded once, by URLSession capture, or missed when it was
  resumed before the hooks, the rule for any task resumed during `start()` ("may be missed, never
  recorded twice", Swizzling rules). A mock or a fail rule never reaches the engine, so its record
  is kept. A request that reaches `Monitoring` after the cover and is resumed before the hooks is
  missed the same way; the window is `hopi_us_start`'s own time.
- What of the withdrawn record has already gone out decides what the store publishes. The summary
  log line is written only at a record's end (`Completed`), so the log never had it. A bridge
  `tail` shows a start as soon as a pass applies it while a viewer is
  attached, and `RemoteSession` then holds it pending (`pending`, `highestSeen`). So the store drops
  the entry and every later snapshot of its id, and publishes the plugin's last snapshot (failed,
  `"withdrawn: platform capture started during this request"`) as its end only when an earlier
  pass published the start and a viewer is attached: a tail, `hopi tail` and the desktop then close
  the row instead of showing it pending forever, and the summary log gets that one failed line.
  Otherwise (no viewer, or the start and the withdrawal applied in one pass) nothing of the record
  goes out. A viewer that showed the start can still miss that end: it was disconnected when the
  withdrawal applied, its `SLOW_CLIENT` queue lost it, or the record was evicted or cleared before
  its withdrawal. The desktop's `RemoteSession` therefore asks the app about each row it still
  holds pending when it resumes (a reconnect to the same process or a `SLOW_CLIENT` re-tail, a
  one-line `list` from just below each id, no bodies, the oldest 64 first) and ends one the app no
  longer has as gone (failed, "gone from the app"), so it neither stays pending nor holds the
  resume point below it.
- An engine Hopi does not cover (Ktor CIO, MockEngine in tests, OkHttp on Android until the Android
  part calls `cover` for it) keeps the plugin as today, `source = "ktor"`.

Cost of deferring, stated: a Ktor `ReadChannelContent`/`WriteChannelContent` request body travels
as `HTTPBodyStream` and is recorded as `"stream, not captured"` (the plugin's tee captures it
today); JSON bodies built with `setBody`/content negotiation are `ByteArrayContent`/`TextContent`
and are captured. Ktor's `HttpRedirect` sends each hop as its own task (to be confirmed by the
Ktor test; Ktor's delegate implements `willPerformHTTPRedirection`), so hops stay separate records
as with the plugin. Ktor's response decoding happens after URLSession's, so the recorded body is
URLSession's decoded body, as the app's Ktor reader receives it. Measured in the runtime-split
plan's stage 10 (R3, 2026-10-29, `KtorDarwinTest` and `UrlSessionCaptureTest` since): URLSession
decodes gzip whoever named `Accept-Encoding` (the app's own, or Ktor's under `ContentEncoding`),
and the Darwin engine drops the compression headers, so Ktor does not decode a second time; the
record holds the decoded body the app read, under the wire's `Content-Encoding: gzip` and
compressed `Content-Length`. So nothing decodes `urlsession` records, and core's `heldCodings`
excludes them: a header-driven decoder would fail on them.

Rejected: "the plugin records, platform capture skips tasks whose session delegate is
`KtorNSURLSessionDelegate`". It would miss Ktor clients without the plugin (a KMP SDK's own
client), and telling the two apart needs a per-request mark that changes app state.

## The `source` field

Backward compatible in both directions:

- `HttpTransaction.source: String?`, default null. Values: `"ktor"`, `"urlsession"`, and the
  Android part's `"okhttp"`, `"urlconnection"`. The Ktor plugin sets `"ktor"` (its recorder, on
  Android and the JVM, since the runtime-split plan's stage 10; nothing records `"ktor"` on iOS).
- `TransactionJson`: summary line, bridge summary and detail write `"source":"…"` right after
  `"state"` (before `error`, `reqHeld`, `resHeld`, `urlTruncated`, `errorTruncated`), omitted when
  null. The line budget logic counts it like any other fixed field (at most 24 bytes, within the
  iOS 1000-byte budget). Agents that ignore unknown fields are unaffected.
- `BridgeDecoder.summary`/`detail`: `json.str("source")`, absent → null (an older app).
- UI detail (overview): a "Source" row, hidden when null. Desktop: the same detail row, a
  `source` column in the list that is hidden while every row is `ktor` or null, HAR export
  `"_source"` on each entry (HAR allows `_`-prefixed custom fields), NDJSON export keeps the field.
- CLI: `hopi list`/`tail` text output shows the source when it is not `ktor`; `--json` passes it
  through.

## Shipping from KMP

### Module layout

Since stage 9 of the runtime-split plan (2026-10-29): the library, its binding (package
`dev.koga.hopi.network.internal.urlsession`), `UrlSessionCapture`, `UrlSessionRules` and the iOS
network tool are `hopi-network`'s iosMain (`dev.koga.hopi.network.internal`), beside the Android
runtime; a rule's hit counter is core's own small C library, `hopi_hits` (binding
`dev.koga.hopi.core.internal.hits`), which the URLSession library retains and claims through
`hopi_hits_*` calls only; the public module and its noop below are deleted; the contract is ARCHITECTURE's ("Modules",
"URLSession capture"). The layout as designed and first built:

| Module | Targets | Contents |
| --- | --- | --- |
| `hopi-core` (iosMain) | iosArm64, iosSimulatorArm64 | The ObjC static library, its cinterop binding (package `dev.koga.hopi.core.internal.urlsession`, like the `oslog` shim), and `UrlSessionCapture` (`@HopiInternalApi`): the Kotlin side of the callbacks, record → `HttpTransaction`, `PlatformCapture` |
| `hopi-urlsession` | android, jvm, iosArm64, iosSimulatorArm64 | Public API (`HopiUrlSession`, `HopiUrlSessionConfig`), depends on `hopi-core`. iOS calls `UrlSessionCapture`; Android and JVM do nothing (`start()` returns at once, `isStarted` stays false), so `commonMain` code can call it. ABI-validated. |
| `hopi-urlsession-noop` | same | Same API, does nothing, depends on nothing. ABI-validated, added to `:checkNoopParity`. |

**From the runtime-split plan's stage 6 to its stage 9 (2026-10-28 and 29).** The start moved to
`hopi-start` (iosArm64 and iosSimulatorArm64; no noop twin, linked and exported by Debug
frameworks only): `Hopi.start` runs core's start sequence with the network tool `NetworkTool`,
which then sat in `hopi-core`'s iosMain beside `UrlSessionCapture` and called its `start`, and with
perf's tool. The tool was public (`@HopiInternalApi`) because `hopi-start` names it, and not in
`hopi-urlsession`, whose ABI dump had no filter and was compared with a noop that held only
`HopiUrlSession` and `HopiUrlSessionConfig`, and which the sample still exported then.
`HopiUrlSession` stayed until stage 9, which moved the library, `UrlSessionCapture`,
`UrlSessionRules` and the tool into `hopi-network` (above), after splitting off the native hit
counter that core's rule engine uses (it stays in core). The parity reason below for keeping the
library in core went with the twins. The DCE claim below is out of date: `HitCounter.ios.kt` also
referred to the library, and the test it names was never built.

The native code lives in `hopi-core` because a cinterop binding cannot be annotated or filtered
the same way on both sides of a parity pair; `hopi-core` is not parity-checked and already carries
the `oslog` precedent. It costs other `hopi-core` consumers nothing: the static library's object
files are linked only when a referenced symbol pulls them in, and only `UrlSessionCapture` refers
to them, which Kotlin/Native's DCE drops unless `HopiUrlSession.start()` is reachable. A test
asserts it (a framework built with `hopi-ktor` alone has no `_hopi_us_` symbol).

Refuted for static frameworks (the runtime-split plan's stage 9 experiments, E2.3, 2026-10-29):
Kotlin/Native drops a dependency's unreferenced Kotlin in every framework kind, but merges a
cinterop archive member whole into every static framework, Debug and Release: a probe framework
with only `hopi-perf` (so core, which then held the library) on its classpath and nothing of Hopi
referenced held all 139 `_hopi_us_` symbols and `hopi_perf.o`'s 45, with no Hopi Kotlin symbol. Only
a dynamic framework's own link drops both. The claim holds for dynamic frameworks alone, and the
test above was never built. So the iOS release guard, not dead-code elimination, keeps a runtime
out of a Release framework (ARCHITECTURE, "Release guards"). Since the runtime-split plan's stage
10 the question no longer arises for `hopi-ktor`: the thin plugin brings `hopi-api` and Ktor only,
no `hopi-core` and no native library, and the guard lets it through.

### Native build

```text
hopi-network/src/nativeInterop/objc/hopi_urlsession.h   C declarations only (hopi_us_*)
hopi-network/src/nativeInterop/objc/hopi_urlsession.m   hooks, state, capture queue (ARC)
hopi-network/src/nativeInterop/cinterop/urlsession.def
hopi-core/src/nativeInterop/objc/hopi_hits.{h,m}       a rule's hit counter (hopi_hits_*), core's own
hopi-core/src/nativeInterop/cinterop/hits.def
```

(Paths since stage 9 of the runtime-split plan, 2026-10-29; the library was `hopi-core`'s before,
with the hit counter inside it. `hopi_urlsession.m` includes `hopi_hits.h` and holds each rule's
counter as a strong `id`; its compile task adds core's `objc` directory to the include path.)

Gradle registers, per iOS target, two `Exec` tasks with argument arrays (no shell):

1. `compileUrlSessionShim<Target>`:
   `xcrun --sdk <iphoneos|iphonesimulator> clang -c -x objective-c -fobjc-arc -fmodules -O2
   -Wall -Wextra -Werror -target arm64-apple-ios<min>[-simulator] -o <build>/urlsession/<target>/hopi_urlsession.o hopi_urlsession.m`
2. `archiveUrlSessionShim<Target>`: `xcrun libtool -static -o <build>/urlsession/<target>/libhopi_urlsession.a <o>`

`<min>` is Kotlin/Native's minimum iOS version for the target (a constant in the build script,
with a test comparing it to the toolchain's `konan.properties` value), so the archive links into
any framework Kotlin can build. Inputs and outputs are declared, so the tasks are cacheable and
configuration-cache safe; they run only on a macOS host with Xcode, which the iOS targets need
anyway.

`urlsession.def`:

```text
language = Objective-C
package = dev.koga.hopi.network.internal.urlsession
headers = hopi_urlsession.h
headerFilter = hopi_urlsession.h
staticLibraries = libhopi_urlsession.a
linkerOpts = -framework Foundation
```

The cinterop task gets `-libraryPath <build>/urlsession/<target>` through `extraOpts` and depends on
the archive task. A static library named in `staticLibraries` is embedded in the cinterop klib and
travels with the published Maven artifact, so consumers need no CocoaPods, SPM, `-ObjC` or
`-force_load`.

The header declares only C functions (`hopi_us_start`, accessors such as
`hopi_us_method(void *h)`, `hopi_us_request_headers(void *h)` returning `NSDictionary *`,
`hopi_us_copy_response(void *h, void *dst, size_t cap)`, `hopi_us_release(void *h)`), so the
binding exposes no ObjC class. Kotlin registers two callbacks with `staticCFunction`
(`onStarted(handle)`, `onFinished(handle)`), which the library calls on the capture queue only.
The handle is a `CFBridgingRetain`ed `HopiUSTask`, released by `hopi_us_release`. A body crosses
once: Kotlin allocates the `ByteArray` of `min(kept, maxBodyBytes)`, pins it, and
`hopi_us_copy_response` copies the kept regions into it with `dispatch_data_apply`, so a
non-contiguous `dispatch_data` is never flattened first.

ObjC rather than Kotlin-only `platform.objc` swizzling: the hooks need typed IMP casts per
selector, blocks with exact signatures, `os_unfair_lock`, `dispatch_data` and `object_getIvar`,
and every chunk would otherwise cross into Kotlin/Native on CFNetwork's queue (thread attach and
GC safepoints on a thread that is not Kotlin's). The ObjC side is about 500 lines.

### Consumer API

Since stage 9 of the runtime-split plan (2026-10-29): deleted with its module and its noop; iOS
starts capture with `hopi-start`'s `Hopi.start { }` only (stage 6), whose `HopiStartConfig` holds the
same settings plus `rules`, `perf` and `perfRemoteControl`; the contract is ARCHITECTURE's ("Consumer API"). As designed:

```kotlin
public object HopiUrlSession {
    /** Starts automatic URLSession capture. Idempotent; the first call's config wins. iOS only. */
    public fun start()
    public fun start(configure: HopiUrlSessionConfig.() -> Unit)
    public val isStarted: Boolean
}

public class HopiUrlSessionConfig {
    public var enabled: Boolean = true                   // false: start() does nothing
    public var maxEntries: Int = 500
    public var maxTotalBytes: Long = 16L * 1024 * 1024
    public var maxBodyBytes: Long = 256L * 1024          // at most maxTotalBytes / 2
    public fun redactHeaders(vararg names: String)
    public fun redactQueryParameters(vararg names: String)
    public fun unsafeDisableRedaction()
    public var summaryLog: Boolean = true               // the NDJSON line per transaction
    public var agentBridge: Boolean = false
}
```

The defaults and the store semantics are the Ktor plugin's (first installation sets the caps,
redaction is the union, disabled only when every installation opted out), so the code that does
it moves from `hopi-ktor` into `hopi-core` as one `@HopiInternalApi` function both call. A config
type of its own, instead of reusing `HopiConfig`/`HopiLogSink`, keeps `hopi-urlsession`
independent of `hopi-ktor` (a pure-URLSession or Swift-first app has no Ktor). The name is not
`Hopi`: that already names the Ktor plugin, and two `Hopi` declarations in one framework collide
in the ObjC export. (That function is core's `CaptureInstallation.install`. Since the
runtime-split plan's stage 10 the Ktor plugin's recorder, `hopi-network`'s, calls it on Android
and the JVM, the start's settings first, and on iOS the plugin makes no installation at all.)

Swift: a Kotlin framework exports only its own declarations and those it `export`s. The consumer
adds, next to its `implementation`:

```kotlin
target.binaries.framework {
    export(if (hopiEnabled) project(":hopi-urlsession") else project(":hopi-urlsession-noop"))
}
// and api(...) of the same module in iosMain
```

then calls, first thing in `App.init` or `application(_:didFinishLaunchingWithOptions:)`:

```swift
HopiUrlSession.shared.start()
// or: HopiUrlSession.shared.start { $0.agentBridge = true }
```

The call compiles in both configurations, because the noop exports the same API. The sample
did this in `iOSApp.swift` until the runtime-split plan's stage 6. Since then it calls
`Hopi.shared.start { … }` from `hopi-start` inside `#if HOPI`, a Swift condition that follows the
same build setting as the Gradle build phase (docs/ARCHITECTURE.md, "Consumer API" and
"Selecting the runtimes in a KMP project"): a framework without the flag has no `hopi-start`, so no
`Hopi`, and the call compiles only where it has.

### Release exclusion

Since stage 9 of the runtime-split plan (2026-10-29): a Release framework holds no runtime (as
designed, the noop twin below), the iOS release guard fails a Release link whose classpath holds one, and
`:sample:shared:verifyIosReleaseExcludesCapture` checks the framework by families that now include
core's `_hopi_hits_`, every runtime package's Kotlin symbols, hopi-start's `Hopi` and the header's
declarations; the Android release check is `verifyReleaseHasNoRuntime`; the contract is ARCHITECTURE's ("Release guards",
"The sample's release checks"). As designed and first built:

- `hopi-urlsession-noop`: the same declarations, `start()` empty, `isStarted` always false, every
  config setter accepted and read back. ABI dumps for all four targets, in `:checkNoopParity`, and
  a `DefaultsParityTest` like `hopi-ktor-noop`'s for the config getters. No dependency on
  `hopi-core`, no cinterop, no native code.
- Android: `:sample:androidApp:verifyReleaseUsesNoop` also requires `:hopi-urlsession-noop` on the
  release runtime classpath and its classes only (the Android actual of the real module holds no
  capture code either, but the check is about selection).
- iOS: a new `:sample:shared:verifyIosReleaseExcludesCapture` links the release framework for
  `iosSimulatorArm64` without the flag and runs `xcrun nm -m` on its binary (argument array). It
  fails on any `_hopi_us_` symbol, any `HopiUS` ObjC class, the queue label
  `dev.koga.hopi.urlsession` in `__cstring`, the selector strings `_didReceiveData:`/
  `_didFinishWithError:` in `__objc_methname`, or any `kfun:dev.koga.hopi.core` symbol. A
  positive control in the same task links with `-Phopi.enabled=true` and requires them. Part of
  `:sample:shared:check` on macOS hosts.

## Architecture contract changes

`docs/ARCHITECTURE.md` is the contract and must change before the code. In "Idle cost is zero",
the row

> Global interception (URLSession swizzling, every request in the process) | Opt-in per `HttpClient`; nothing global, no swizzling, no reflection

becomes

> Global interception that re-sends every request through a `URLProtocol` | Observe only. Global capture exists, but only after an explicit `HopiUrlSession.start()` in a debug build, never in release (noop twin, checked). It re-issues nothing, replaces method implementations only on `NSURLSessionTask`, `NSURLSession`'s upload factories and CFNetwork's connection class (never an app or SDK class), adds no method, copies no bytes on the app's threads, and wakes nothing while no request runs

and the Modules table gains `hopi-urlsession` and `hopi-urlsession-noop`; the Data model gains
`source`; "Agent access" gains `source` in the line format. The roadmap's "no swizzling" rule for
the analytics debugger is unchanged: that tool keeps an explicit API.

## Tests

Since stage 9 of the runtime-split plan (2026-10-29) the iOS tests below are `hopi-network`'s
(`:hopi-network:iosSimulatorArm64Test`, test package `dev.koga.hopi.urlsession` kept) and core's
(the hit counter). The list as designed:

`hopi-core` and `hopi-urlsession` `iosSimulatorArm64Test` (Kotlin/Native on the simulator), with
a test-only ObjC archive and cinterop on the **test** compilation (never in the main klib):

- An in-process HTTP/1.1 server on `127.0.0.1:0` (POSIX sockets, one pthread, scripted replies):
  `Content-Length` and chunked bodies, slow chunks, a reset mid-body, 204, 304, HEAD, a 302
  redirect, gzip, a 5 s stall for timeouts. No new dependency.
- Fixtures Kotlin/Native cannot write: an `NSProxy` delegate, a partial delegate, a delegate class
  pair whose subclass calls `super`, and two foreign swizzlers of `resume` and `_didReceiveData:`
  (one `method_exchangeImplementations`, one `method_setImplementation`).

Cases:

1. Every API in the coverage table (except the Swift-only ones) × `shared`/default/ephemeral ×
   delegate kinds: exactly one record per task with method, URL, status, headers, request and
   response bodies, `totalBytes`, `protocol`, `source = "urlsession"`, a duration.
2. Behavior preserved: the app receives byte-identical bodies; each completion handler runs once
   on the session's delegate queue; the delegate callback sequence equals the one recorded before
   `start()` in the same test (one ordered test runs the control scenarios, then `start()`, then
   the same scenarios); `respondsToSelector:` of every fixture delegate over the 25 session/task
   delegate selectors, `session.delegate` identity, `configuration.protocolClasses` and
   `object_getClass(task)` are unchanged.
3. Sessions created before `start()`: their tasks resumed after it are recorded with bodies; a task
   resumed before it is not, nor one resumed before, suspended and resumed after; created before
   and resumed after, recorded.
4. Concurrency: 64 parallel tasks with distinct bodies, each record has its own body; 8 threads
   calling `start()` at once install once (a test-only install counter).
5. Bounds: an 8 MB response with a 256 KB cap reaches the app whole; the record keeps 256 KB,
   `truncated`, `totalBytes` exact; the library's high-water mark of retained bytes per task stays
   at most cap plus one subrange. Download tasks retain nothing.
6. Failures: a reset mid-body is `Failed` with the error; `cancel` is `"cancelled"`; a timeout is
   `Failed`.
7. Coexistence: the foreign swizzlers installed before and after `start()` all observe every call,
   and the app's bytes are intact.
8. Metadata path: a test-only switch makes the self-check fail; records then have metadata and
   `"not observable"` bodies, and the notice line is written once. Background-session tasks cannot
   run in a bare test binary, so this path stands in for them.
9. Ktor Darwin (`ktor-client-darwin` as a test dependency): plugin installed and `start()` called
   gives one record per call, `source = "urlsession"`; two concurrent POSTs with distinct bodies to
   the same URL give two records with the right bodies; a channel body is
   `"stream, not captured"`; without `start()` the plugin records as today with `source = "ktor"`; a redirect
   gives one record per hop.
10. The `source` field: JSON round trips through `TransactionJson` and `BridgeDecoder`, an old line
    without it decodes to null, the summary line budget holds with it (jvmTest in core, connect).
11. DCE: a test framework linked with `hopi-ktor` only has no `_hopi_us_` symbol.

Swift async APIs cannot be called from Kotlin/Native, so `sample/iosApp` gains an XCTest target
(`iosAppCaptureTests`, in `project.yml`) that calls `data(for:)`, `data(from:)`,
`upload(for:from:)`, `bytes(for:)` and `download(for:)` against the sample's dev server and reads
the records from the Kotlin store through a small test-only accessor in the sample's shared module.
It runs with `xcodebuild test -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 16,OS=18.2'`,
wrapped as `:sample:shared:iosCaptureSwiftTest`; it is not part of the base check because it
boots a simulator. Every OS version the project claims runs it before release, since the byte
path depends on private CFNetwork details.

## Performance A/B method

The existing `ios-ab.sh` compares Debug-real with Release-noop, which the architecture doc calls
not exact. Here all arms are built the same way:

- **Arms**, all Debug configuration, same optimization: A = real, `start()` called; B = the same
  binary, `start()` not called (a launch argument the sample reads); C = noop (the Debug
  configuration built with `-Phopi.enabled=false` through a build-phase override).
- **Workload**, in the sample: 300 sequential requests per block to the dev server through
  `URLSession.shared` with a completion handler and through a delegate session, at 1 KB, 64 KB,
  1 MB and 5 MB, `Content-Length` and chunked. Per request, the app records wall time from
  `resume` to its completion handler, and the CPU time of the calling thread around `resume`
  (`CLOCK_THREAD_CPUTIME_ID`).
- **Blocks**: C, A, B, C interleaved, 3 rounds of 15 runs per size, the first 5 dropped, each arm's
  block median against the mean of the noop blocks around it, then the median of the rounds
  (`paired.py`), with host load under 3, as the Android A/B did.
- **Hook microbenchmark** (`iosSimulatorArm64Test`, opt-in like the giant-body benchmarks): ns per
  call of hook 1, hook 2 below and past the cap, and hook 3, over 10,000 loopback requests,
  against the same test binary without `start()`.
- **Idle**: 60 s with no requests, per thread (`ps -M`): the capture queue has no thread and the
  app's CPU matches B.
- **Targets** (to be confirmed, not promised): hook 1 under 5 µs, hook 2 under 1 µs per chunk,
  hook 3 under 10 µs; per-request wall within the C-to-C spread at 1 KB, and at most +2% at 1 MB.
  Measured on the iPhone 16 simulator only; no physical iOS device is available, and simulator
  figures are an upper bound for CPU, not a device number.

## Implementation steps

1. `source` field through model, JSON, decoder, UI, desktop, CLI; the plugin sets `"ktor"`.
2. Store-configuration helper moved into core; `PlatformCapture`; the plugin's defer check.
3. Native library, Gradle tasks, cinterop, `UrlSessionCapture`; the test server and fixtures;
   tests 1 to 8 and 11.
4. `hopi-urlsession` and `-noop`, ABI dumps, parity, defaults parity; Ktor Darwin tests (9).
5. Sample: export, Swift `start()`, Swift XCTest target, release exclusion check.
6. Measurements, then `ARCHITECTURE.md` amended with the measured figures.

## Open spike items (resolved by tests before the code is final)

- Whether the identity dedupe covers every replay shape: a delegate that answers
  `didReceiveResponse:completionHandler:` late (async disposition), `suspend`/`resume` mid-body,
  HTTP/2 and HTTP/3 servers, cache hits. The finish-time check against `countOfBytesReceived`
  catches any miss.
- Whether responses from an app's `URLProtocol` and URL-cache hits go through
  `_didReceiveData:` (expected yes).
- Whether `download(for:)` and `URLSession.download(from:)` behave like `downloadTask`.
- Whether Ktor Darwin's redirects are one task per hop.
- Which task classes define `resume` on iOS 16, 17 and 18 (FLEX documents
  `NSURLSessionTask` on 14+, `__NSCFURLSessionTask` on 9 to 13; Pulse replaces both).
- The `_incompleteTaskMetrics` key on every supported OS (missing means `protocol` stays null).
- The Kotlin/Native minimum iOS version for the archive, and whether the published klib links in
  a consumer's Xcode older than the one that built it.

## Risks

1. **Private CFNetwork details change.** `__NSCFURLLocalSessionConnection`, its two selectors,
   their encodings or the `_task` ivar may change in any iOS release. Mitigation: the start-time
   self-check never calls an unverified selector, falls back to the metadata path with a notice
   line, and the Swift XCTest target plus the Kotlin/Native tests run on every iOS version the
   project claims before a release. Residual: a change that keeps names and encodings but changes
   meaning (for example, delivering bytes that are not the app's) passes the self-check; the
   `countOfBytesReceived` cross-check and the body-equality tests are the guard.
2. **Duplicate chunk delivery.** Observed on a redirect and a mid-body cancel. Mitigation:
   identity dedupe plus the `countOfBytesReceived` cut. Residual: a replay that wraps the same bytes
   in a new object is not caught by identity. The size stays right (cut to `countOfBytesReceived`
   at the end), but below the cap the kept body could contain a repeated chunk. The replay tests
   must show that this does not happen in the shapes listed in the spike items.
3. **Foreign swizzlers.** Firebase Performance, Pulse, FLEX, AFNetworking replace the same
   methods. Chaining works in both install orders and with both styles in the tests, but a
   swizzler that does not call through (drops the original) would hide traffic from Hopi or vice
   versa; a swizzler installed while a call is in flight sees the old IMP for that call.
4. **Metadata-only cases.** Background sessions (and tasks they finish while the app is
   suspended or relaunched are never recorded), and every task on an OS where the self-check
   fails. Downloads keep no body by design. KVO on those tasks isa-swizzles the instance, visible
   to code that compares `object_getClass`.
5. **Missed at `start()`.** Tasks resumed before `start()` are not recorded, even when
   suspended and resumed after it; an upload
   body of a task created before `start()` is `"not observed"`; a Ktor Darwin request is
   URLSession capture's like any other task, so one resumed before `start()` is missed (since the
   runtime-split plan's stage 10 the plugin records nothing on iOS; it used to withdraw its record
   when it found the engine covered by the time the engine answered, see Dedupe, and until
   2026-10-27 one resumed after the hooks was recorded twice).
6. **Ktor Darwin under URLSession capture.** Channel request bodies (`HTTPBodyStream`) are
   recorded as `"stream, not captured"`; the duration ends at the network's end, not at the
   reader's; Ktor-only details (which client, Ktor's view of headers) are absent from those
   records. Since the runtime-split plan's stage 10 this holds for every Darwin request recorded:
   the plugin, whose tee captured those bodies, installs nothing on iOS.
7. **Request headers that URLSession adds** (configuration `HTTPAdditionalHeaders`, cookies,
   `User-Agent`, `Accept-Encoding`) are not seen, and header order and duplicates follow
   Foundation's dictionary.
8. **App-thread cost.** `resume` (which may be on the main thread) pays an allocation, an
   associated-object set and a `dispatch_async`; a plain-`NSData` chunk pays a bounded copy on
   CFNetwork's queue. Unmeasured until the A/B runs; targets are stated, not promised.
9. **Toolchain coupling.** The archive embedded in the klib is built with the publishing host's
   Xcode and a fixed minimum iOS version; a consumer on an older Xcode or a lower deployment
   target may fail to link. The build pins the minimum to Kotlin/Native's and a test checks it.
10. **Measurement scope.** Performance is measured on the iPhone 16 simulator only; no physical
    iOS device is available. Swift async coverage lives in an XCTest target outside the base
    check, so a regression there shows only when that target runs.
11. **`_incompleteTaskMetrics`** (private KVC key) may be absent; `protocol` is then null.
12. **Contract change.** The architecture's "no swizzling" rule is replaced by an observe-only
    rule; reviewers must hold the new rule as strictly (no added methods, no app classes, no
    re-issue), or the line between Hopi and DebugSwift blurs.

## As built (2026-10-24)

Built as designed, with these deviations, each for the reason given:

1. **`countOfBytesReceived` never cuts a record.** Measured: when `_didFinishWithError:` runs, it is
   still 0 or partial for uploads, native redirects and downloads, so the planned cut emptied
   correct bodies. `totalBytes` is Hopi's own count (replays excluded); a test-only counter records
   any task whose public count ends above Hopi's (0 in every test run).
2. **Nothing past the cap is retained.** The 4-chunk dedupe ring past the cap retained up to 4.5 MB
   for an 8 MB loopback body (chunks of about 1 MB). It now holds `__weak` references (identity
   only, nil once freed, so a reused address is never taken for a replay), and the chunk that
   crosses the cap is copied (at most the rest of the cap) instead of sub-ranged, since a subrange
   keeps the whole buffer alive. Measured high water: exactly the cap.
3. **Errors** arrive in `kCFErrorDomainCFNetwork`, whose codes are the `NSURLError` codes; they are
   recorded as `NSURLErrorDomain <code>: <description>`, `"cancelled"` for -999.
4. **Protocol** is written as Ktor writes it (`HTTP/1.1`, `HTTP/2.0`, `HTTP/3.0`).
5. **Binary and event-stream content types** are decided at the first chunk, in the library, by a
   copy of core's rule (`ResponseSkip`, moved from the plugin to core); a test compares the two.
6. **More upload factories** are hooked: `fromFile:` (both, and the private one when its encoding
   matches) and `uploadTaskWithStreamedRequest:`, so a file or stream body is named as such
   instead of `"not observed"`.
7. **The plugin's deferral** returns before the exchange is created, so no request attribute is
   needed: the response hooks already skip a call without an exchange.
8. **The header's constants are `#define`s**: cinterop maps an anonymous enum's members to `UInt`.
9. **The capture queue's label is `dev.koga.hopi.urlsession-capture`**: the package name
   `dev.koga.hopi.urlsession` is in every noop binary too, so the release check could not tell them
   apart.
10. **Android's `verifyReleaseUsesNoop` is unchanged**: the sample depends on `hopi-urlsession` from
    `iosMain` only (it does nothing on Android), so the Android release classpath has no new module.
11. **The Swift test runner is a script** (`sample/iosApp/scripts/capture-swift-test.sh`), not a
    Gradle task: `xcodebuild` runs Gradle from the app's build phase, which a Gradle task wrapping it
    would contend with.
12. **The desktop list has no source column**: the in-app and desktop lists are the same
    composables, and a row's second line ends with the source when it is not Ktor.
13. **Test 11 (DCE: a framework with `hopi-ktor` only has no `_hopi_us_` symbol) is not built**;
    the release check covers the noop selection, not dead-code elimination within the real one.
14. **Performance** is measured by the library's own per-hook timing (opt-in, a relaxed atomic load
    when off) in the Kotlin/Native tests and by a native A/B harness (`benchmark/urlsession/ab.m`,
    capture on and off in one binary), not by the three-arm sample app runs.

Open items resolved by the tests: `download(for:)` behaves like `downloadTask` (XCTest); Ktor
Darwin's redirects are one task per hop (its delegate stops URLSession's own redirect); the task
classes and private selectors pass the self-check on iOS 18.2 and 27.0. Still open: responses from
an app's own `URLProtocol` and URL-cache hits, HTTP/2 and HTTP/3 servers (the tests use loopback
HTTP/1.1), the replay shapes of a late `didReceiveResponse` disposition and `suspend`/`resume`
mid-body, a physical device, and the Kotlin/Native toolchain issue that keeps the sample app from
launching on iOS 18.2 with Xcode 27.1 (`kCGAdaptiveMaximumBitDepth`).

## Hardening (2026-10-24)

- **A task that fails after its response** (connection lost mid-body, cancelled mid-body) keeps
  its status, headers, protocol and the response bytes that arrived, as a partial body
  (`failedAfterResponse`); only a task with no HTTP response is `Failed` with no response.
- **`hopi_us_final_url`** returns at once when the current URL is the request's (the same object
  or an equal one), before building the `NSURLComponents` canonical forms, which cost about 17 µs
  per request in the profile.
- **The store** shares chunks between snapshots instead of copying the 500-entry list per update
  (the copy and the `StateFlow` comparison were 113 µs of the 307 µs Hopi cost per request), the
  summary line goes to os_log as pinned bytes with a length instead of a C string, and the sink
  runs on the store's consumer thread instead of a coroutine of its own (ARCHITECTURE.md).
- **The bridge writer** blocks on a dispatch semaphore, not in `runBlocking`: with `hopi tail`
  connected and idle, `hopi-bridge-writer` used 0 µs over 60 s (was about 0.35 ms per second), as
  did `hopi-store`, `hopi-bridge` and `hopi-bridge-client` (`-idleCPU 60`, iPhone 17, iOS 27.0).

Measured on the iPhone 17 simulator (iOS 27.0), Debug apps, `-captureBench 'n=40000;mb=1;runs=1'`,
interleaved noop / before (19:31 build) / after, 3 rounds, process CPU per small request: noop 575
to 743 µs (the first round warm), before 878 to 961, after 679 to 698, so Hopi's share fell from
about +300 to about +100 µs per request. Median wall per request was the same in all three arms
(0.61 to 0.67 ms, apart from the first noop block). Raw data: `build/evidence/hardening/fixes/ios-ab/`,
`ios-idle/`.

**iOS 18.2 launch (Xcode 27.1).** The Debug sample's `HopiSample.debug.dylib` imports
`_kCGAdaptiveMaximumBitDepth` strongly, from the Kotlin framework's
`platform.CoreGraphics` interop object: a Debug Kotlin/Native framework links every wrapper of a
platform library it uses, built against the iOS 27.1 SDK, so constants newer than iOS 18 become
strong imports. It is not a deployment-target setting (the app and framework say `minos 16.0`), and
not the compiler caches (`kotlin.native.cacheKind=none` links the same reference). Weak-linking
CoreGraphics (`-weak_framework CoreGraphics`) gets past that symbol, and dyld then stops at the next
one (`___res_9_p_cert_syms`, libresolv). Since 2026-10-25 the sample weak-links both
(`-weak_framework CoreGraphics -weak-lresolv` in `project.yml` and the project): checked against
the iOS 18.2 runtime's exports, those two are the only strong imports of the Debug dylib it lacks,
and both the noop and the real Debug app launch and run on the iPhone 16 / iOS 18.2 simulator
(`build/evidence/review/ios/ios18.2-*-running.png`). Calling one of those constants on iOS 18
would still crash; nothing in the sample does.

**Idle harness (corrected 2026-10-25).** `-idleCPU`'s `processCpuUsDelta` used to be the CPU of
the threads alive at the end minus that of those alive at the start, so a thread that exited inside
the window took its whole lifetime off it; it is now `getrusage(RUSAGE_SELF)` at both ends, with
the per-thread sum logged as `liveThreadsCpuUs`. The per-thread zeros of Hopi's threads were never
affected; `build/evidence/hardening/verify/ios/idle-table.md` is rebuilt from the rows.

