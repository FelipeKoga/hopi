# Design: Hopi Rules (mock, delay, fail, edit)

Status: built (2026-10-25); device verification, the performance plan and the "As built" notes
are pending (docs/ROADMAP.md). The roadmap item "Rules: mock, edit". A rule
matches a request and changes what happens to it: the app gets a made-up response (**mock**), the
request or response is held back (**delay**), the request fails the way the platform fails it
(**fail**), or the request or response is rewritten (**edit**). Rules apply to every source Hopi
records (`ktor`, `okhttp`, `urlconnection`, `urlsession`), each to the extent it can do so without
weakening the app's security, and every request a rule touched is marked in its record. Replay
(Hopi re-sending a recorded request) was built and then removed from the product on 2026-10-25;
see "Replay (removed)" below and docs/ROADMAP.md, "Replay (no priority)".

This design builds on `docs/design/android-capture.md` and `docs/design/urlsession-capture.md`
(the interception points it reuses) and on the architecture's store, bridge and `PlatformCapture`
contracts.

## Goals and non-goals

Goals:

- **Zero cost with no rule.** A request pays one volatile read of the rule state and a reference
  comparison, nothing else: no allocation, no lock, no string work. The one stated exception is a
  URLSession session created while Hopi runs, which pays one `canInitWithTask:` message to Hopi's
  `NSURLProtocol` (see "URLSession"). Measured: the no-rule A/B is within the off-to-off spread
  on every source.
- **Bounded cost with rules.** At most 64 rules, compiled at `add` time, matched without
  backtracking blow-up. A request that matches nothing costs a stated, measured number.
- **Zero production impact.** The engine lives in `hopi-core`, which a release build does not
  contain. The consumer API (`HopiRules`) has a noop twin with the same API that does nothing.
  Release builds are unchanged, and the existing release checks prove it.
- **Offline, in memory, session only.** Rules and their bodies live in the app process and die
  with it. Nothing is written to disk, nothing is sent anywhere, no new transport is opened.
- **Never weaken the app's security.** No action bypasses certificate pinning, custom TLS, a
  `HostnameVerifier`, an `SSLSocketFactory`, a proxy, or a URLSession authentication-challenge
  delegate. Where an action cannot be given on a source without that, it is unsupported on that
  source, and `rules.add` says so.
- **One rule per request, one application per request.** First match wins, and exactly one layer
  (the one that records the request) applies it.
- **Every surface.** Bridge commands, the CLI, an in-process Kotlin API for tests and debug menus,
  a rules panel in the desktop viewer, a rules list and "Mock this response" in the in-app screen.

Non-goals (v1):

- Live breakpoints (pause a request in flight and edit it by hand). Later.
- Matching on the request body, and matching on the response (a rule matches the request only).
- Mid-body faults ("reset after 1 KB"), bandwidth throttling, and stacking several rules on one
  request.
- Rules that survive an app restart, rule files checked into a repository, a separate mock server.
- WebSocket frames, gRPC streams, and every request no source records today (Cronet, WebView,
  native stacks, background URLSession sessions; see each source).

## What the existing tools do, and what we take

| Tool | Mechanism | Verdict |
| --- | --- | --- |
| DebugSwift, Netfox, Wormholy (iOS) | A `URLProtocol` that claims every request and re-issues it through its own session; mocks are answered from it | Rejected for real traffic: re-issuing through another session drops the app's challenge delegate (pinning), its cookies, cache and proxy settings. **Taken** for one thing only: a protocol that claims a request it will answer itself, with no network. |
| Proxyman, Charles (host proxies) | A system or Wi-Fi proxy with a MITM certificate the device must trust | Rejected: it requires installing a CA and disabling pinning, which is the opposite of priority 4, and it is external communication. |
| OkHttp `MockWebServer`, Ktor `MockEngine` | A fake server or engine the test wires in | Not an inspector feature: needs per-client wiring. Hopi rules work without touching the app's client and also in tests that already use these. |
| Chucker / Pluto / Flipper (Android) | Observe-only interceptors; Flipper's network plugin had a "mock" tab that answers from an interceptor | **Taken**: an application interceptor answering without `proceed` is the cheapest correct OkHttp mock, and it is where Hopi's interceptor already sits. |
| Pulse (iOS) | `MockingURLProtocol` prepended to `protocolClasses` at session creation; mocks served by the protocol | **Taken** for mock and fail, with the claim limited to matched requests and the protocol answering in under a microsecond when no rule exists. Rejected for capture long ago (it changes `protocolClasses`); accepted here as the one observable change rules need, behind a switch. |

### Spike evidence (2026-10-25, macOS 27 host, `swiftc -O`, not kept in the repo)

Run on the host's Foundation, which shares CFNetwork with the simulator; every item is confirmed
on the iPhone 16 (iOS 18.2) and iPhone 17 (iOS 27.0) simulators by the tests below before the code
is final.

1. **Session factories funnel.** `URLSession(configuration:)` calls
   `+sessionWithConfiguration:`, which calls `+sessionWithConfiguration:delegate:delegateQueue:`;
   `URLSession(configuration:delegate:delegateQueue:)` calls the latter directly. Replacing only
   the three-argument class method, prepending a protocol class to a copy of the configuration,
   made an ephemeral session and a default session with a delegate serve a mocked
   `http://127.0.0.1:1/mock` (status 418, body `mocked`) and still fail the unmatched
   `http://127.0.0.1:1/real` with `-1004` (connection refused): unmatched requests go to the
   network as before. `session.configuration.protocolClasses` then lists the class first.
2. **`URLSession.shared` honors `+[NSURLProtocol registerClass:]`** called after the shared session
   exists, with the same results.
3. **One `canInit` per task.** The protocol's class method ran once per task in each session.
4. **Protocol-served tasks are recorded by the existing byte path.** With the capture hooks on
   `__NSCFURLLocalSessionConnection` installed, a mocked task went through `_didReceiveData:`
   (twice, the same replay shape the identity dedupe already handles) and `_didFinishWithError:`
   once, and `countOfBytesReceived` was 6. So rules need no second recorder on iOS, only a mark.
5. **Cancel before resume.** `cancel()` on a never-resumed task completes it at once with `-999`,
   and a later `resume()` is a no-op (state stays `completed`, no hook runs). This is the
   semantics a delayed `resume` needs.
6. **No factory funnel for tasks.** The public `dataTaskWithRequest:`, `dataTaskWithURL:` (with and
   without completion handlers), upload and download factories do **not** route through the
   private `_dataTaskWithRequest:delegate:completionHandler:` (`@40@0:8@16@24@?32`); Swift's async
   `data(for:)` does. Request edits on URLSession therefore hook each factory by name, as the
   upload hooks already do.
7. **`-[NSURLSessionTask session]`** is defined on `NSURLSessionTask` with encoding `@16@0:8`: the
   task's session is reachable, which replay needed (replay is removed; nothing reads it now).

## Rule model

```text
Rule
  id: Long                 process-wide, monotonic, starts at 1, never reused
  label: String?           ≤ 80 chars, shown in lists
  enabled: Boolean         default true
  priority: Int            default 0; higher is tried first; ties: newest first
  times: Int?              apply at most this many times (1..1_000_000), then the rule is exhausted
  hits: Long               requests the rule was applied to (read-only)
  match: Match
  delay: Delay?            optional on any rule
  action: Mock | Fail | Edit | none     at most one; "none" needs a delay
```

**One rule per request.** Rules are tried in order and the first that matches and still has
`times` left is applied; the rest are not consulted. Delay is a field of a rule rather than a
separate rule so that the one composition people need ("this endpoint returns 500 after 2 s",
"fail with a timeout after 30 s") is one rule, and first-match stays deterministic, explainable
(one rule id per record) and cheap (the scan stops at the first match). Stacking (a global "slow
network" delay plus specific mocks) is the cost; the answer is a delay on each mock, and a later
version may add an explicit `continue` flag if real use asks for it.

### Match

| Field | Meaning | Default |
| --- | --- | --- |
| `methods` | Set of method tokens, compared case-sensitively (methods are case-sensitive in HTTP) | any |
| `scheme` | `http` or `https` | any |
| `host` | Glob over the lowercased host; `*` matches any run of characters including dots (`*.example.com` matches `a.b.example.com`, not `example.com`) | any |
| `port` | Exact port; the scheme's default when the URL has none | any |
| `path` | Glob over the percent-encoded path as sent; `*` matches within one segment (no `/`), `**` matches across segments; case-sensitive; no query, no fragment | any |
| `query` | Up to 8 predicates, all must hold: `name=value-glob`, `name` (present), `!name` (absent); names and values percent-decoded; order and other parameters ignored | none |
| `headers` | Up to 8 predicates, same shape, names case-insensitive, over the request headers the recorder sees (after the app's OkHttp interceptors; never the headers the stack adds itself, see each source); a predicate holds when any value of the name matches. URLSession keeps one field per name, joining repeated values with `,`, so there the whole field and each trimmed comma-separated element are tried | none |
| `sources` | Subset of `ktor`, `okhttp`, `urlconnection`, `urlsession` | all |

**Shorthand** (CLI `--match`, `HopiRules` `match("…")`): `[METHODS ]URL-PATTERN`, where `METHODS`
is `GET`, `GET|POST` or `*`, and `URL-PATTERN` is `[scheme://]host[:port]path[?query]`. A pattern
that starts with `/` has host `*`; `*/users/*` is host `*`, path `/users/*`. `?page=2&debug&!trace`
gives the three query predicates. Examples: `GET */users/*` (any host, `/users/42` but not
`/users/42/posts`), `POST https://api.example.com/v1/**`, `* *.cdn.example.com/**`.

**Compilation.** At `add` time each glob becomes a list of literal pieces and wildcards, matched by
the standard two-pointer algorithm with one backtrack point per `*` (linear in practice, at most
O(pattern × input) in the worst case, with the pattern capped at 256 chars). Methods become a
bitmask over the nine standard methods plus a set for any other token; the host's literal suffix
(`.example.com`) is kept separately so most non-matching hosts are rejected with one `endsWith`.
The match order inside a rule is cheapest first: source, method, scheme, port, host, path, query,
headers. Query and header predicates parse nothing until a rule that has them reaches that step.

### Actions

**Mock**: `status` (100..599, not 101), `headers` (≤ 32 pairs), `body` (inline UTF-8 text, base64
bytes, or a blob the CLI uploaded from a file; ≤ 1 MiB; none for a 1xx, 204 or 304 status, and a
matched `HEAD` gets the headers without it). `Content-Length` is computed by Hopi and
may not be given; `Transfer-Encoding` and `Content-Encoding` are rejected, because each stack
decodes at a different layer and a mock is delivered as the app receives a body: decoded.
`Set-Cookie` and `Set-Cookie2` are rejected too: a cookie store that saw a mock would keep its
cookie after the rule is gone, and some stores persist (`HTTPCookieStorage`, a Ktor `HttpCookies`
storage the app backs with a file), which priority 3 forbids. For the same reason no mocked or
edited response reaches an HTTP cache: OkHttp's and `HttpURLConnection`'s caches sit below Hopi,
URLSession's protocol answers with `NSURLCacheStorageNotAllowed`, and on Ktor, whose `HttpCache`
runs in the receive pipeline after Hopi, the delivered response carries `Cache-Control: no-store`
in place of any `Cache-Control` the rule gave when the client has `HttpCache` installed (stated as
an observable difference). A mock never touches the network: no DNS, no connection, no TLS.
`fromTransaction: id` builds the mock in the app from a recorded response (status, headers minus
the rejected ones, body), overridable field by field; the record's values never cross the bridge
for it, so redaction never ends up inside a mock. A record whose response body is truncated or
skipped cannot be the source of a mock body (`BAD_REQUEST`, "response body not held in full"). The
copied body counts toward the rule body limits like any other. It is copied decoded, as every mock
is delivered, when the record shows it encoded: an OkHttp or `HttpURLConnection` record of a
request that named its own `Accept-Encoding` holds the server's bytes under their
`Content-Encoding`. A single `gzip`, `x-gzip` or `deflate` (zlib or bare) is decoded in the app;
decoding stops one byte past the 1 MiB body limit (`LIMIT`), into an array that doubles up to that
size and never past it, so a small body that decodes to far more costs no more than one at the
limit: about 2 MiB held at once, at most. Any other encoding, or several stacked, is refused
(`BAD_REQUEST`, "response body is encoded (Content-Encoding: br); give a body"), and so are bytes
that do not decode; a `body` given with `fromTransaction` is used instead of the copy. "Mock this
response" in both viewers is disabled with that reason when the record's headers show such an
encoding. The app's own screen builds the copy off the main thread, and shows a refusal only
decoding finds without the bridge's wording ("Response body does not decode as gzip"). A Ktor or
URLSession record keeps the raw headers over what the app received after any decoder, so they do
not tell, and it is copied as held.

**Follow-ups of a mocked status.** A mock is delivered at the recorder's position, so only the
follow-up logic above that position sees it: on Ktor, `HttpRedirect`, `HttpRequestRetry` and `Auth`
act on a mocked 3xx, 5xx or 401 (they run per hop above the send pipeline), and the follow-up hop
is a request of its own (real unless a rule matches it). On OkHttp, `HttpURLConnection` and
URLSession the redirect, `Authenticator` and challenge logic is below Hopi or inside the system
loader: the stack never follows a mocked 3xx, 401 or 407. A client above the stack still sees the
mocked status and acts on it. Ktor's engines turn the stack's own redirects off
(`followRedirects(false)` on OkHttp, `instanceFollowRedirects = false` on `HttpURLConnection`, one
task per hop on URLSession), so a Ktor client's `HttpRedirect` follows a mocked 3xx on these sources
too (GET and HEAD with a `Location`), as on the Ktor source, and its `Auth` answers a mocked 401;
the follow-up hop is a request of its own. `rules.add` returns this as an `unsupported` note for a
3xx, 401 or 407 mock on those sources (the rule still applies).

**Fail**: one of `timeout`, `reset` (connection reset), `unknown-host` (DNS-like), `cancel`. Thrown
or reported as each stack's own exception or `NSError` (table below), with the platform's own
wording, so the app's error handling runs as it would in the field; the record carries the mark.
A fail never touches the network. Combined with `delay.beforeResponse` it fails after the delay
("timeout after 30 s").

**Delay**: `beforeRequest` and `beforeResponse`, each 0..120 s. Before request: before the stack
resolves, connects and sends. Before response: after the response headers arrived (or the mock was
built), before the app sees them. The wait is cancellation-aware on every source (below): a delay
never outlives the app's cancel. A delay longer than a timeout of the app's that covers the
waiting interval (OkHttp's `callTimeout`, and `readTimeout` before a response; `HttpURLConnection`'s
connect and read timeouts; Ktor's `HttpTimeout` request timeout) ends as that timeout would. On
URLSession a before-request delay holds the task suspended, and the session's timeouts start only
at the real `resume`, so there the delay adds to them instead of racing them (stated).

**Edit**: request and/or response side.

- Request: `method`; URL parts `host`, `port`, `path`, `setQuery` / `removeQuery`; `setHeaders`
  (replace all values of a name), `addHeaders`, `removeHeaders`; `body` (as the mock body).
  Header edits may not name `Host` (the origin is edited through `host` and `port`),
  `Content-Length` (computed from the body by each stack), `Transfer-Encoding`, `Connection`, or a
  name starting with `:` (`BAD_REQUEST`). `Content-Type` may be set; with a body edit and no
  `Content-Type` edit, the original body's type is kept.
  **The scheme cannot change** (no downgrade, and no upgrade either: on Android the connection's
  class is chosen by scheme before the edit happens). **An edit that changes the origin** (host or
  port) removes every value the app gave `Authorization`, `Proxy-Authorization`, `Cookie` and
  every header in the store's redaction list: the same rule OkHttp and browsers apply to a
  cross-host redirect, so a rule can never ship the app's credentials to another host by accident.
  Only the values the edit supplies itself (`setHeaders` or `addHeaders`) reach the new origin; an
  added `Cookie` never travels next to the app's own. On URLSession, an origin-changing edit is not
  applied to a request whose session configuration adds one of these headers
  (`HTTPAdditionalHeaders`) that the edit does not set or add itself: URLSession merges those below
  Hopi, so they cannot be removed, and the request goes out unedited and unclaimed.
- Response: `status`, `setHeaders` / `addHeaders` / `removeHeaders`, `body`. `Content-Length` is
  recomputed when the body is replaced, and a replaced body also removes `Content-Encoding`: where
  the recorder sees the body still encoded (an app that set its own `Accept-Encoding` on OkHttp, a
  Ktor client whose `ContentEncoding` decodes after Hopi's hook), the replacement is plain bytes
  and a kept `Content-Encoding: gzip` would make the app's decoder fail on it. The mock's rejected
  headers (`Set-Cookie` included) stay rejected.

**Times and hits.** A rule's `hits` is an atomic counter shared by every source. A source *claims*
an application with one compare-and-increment against `times`; a claim that fails means the rule
does not match and the scan goes on. The claim that reaches `times` republishes the rule state
without the exhausted rule (once per rule, off the request when the platform allows it, see
"Engine"), so a rule set whose rules are all exhausted or disabled returns to the zero-cost path.
An exhausted rule stays listed (`"exhausted":true`) until removed or updated.

### Limits

| Limit | Value | On violation |
| --- | --- | --- |
| Rules | 64 | `LIMIT` |
| Mock or edit body | 1 MiB each | `LIMIT` |
| All rule bodies together | 8 MiB | `LIMIT` |
| Headers per mock or edit side | 32 pairs, 8 KiB in total | `BAD_REQUEST` |
| Query / header predicates | 8 each | `BAD_REQUEST` |
| Host or path pattern | 256 chars | `BAD_REQUEST` |
| Delay | 120 s per side | `BAD_REQUEST` |
| `times` | 1..1,000,000 | `BAD_REQUEST` |
| Blob staging per bridge session | 2 blobs, 1 MiB each | `LIMIT` |

Rule bodies are outside the store's `maxTotalBytes` and bounded by their own 8 MiB, counted per
copy held: on iOS a rule whose `sources` include `urlsession` holds its bodies twice (the Kotlin
bytes and the mirror's `NSData`), and both count, so the worst case stays 8 MiB on every platform.
Blob staging is bounded separately: 2 blobs × 1 MiB × the bridge's 8 connections, 16 MiB at worst,
all of it freed when the sessions end.

## Engine (`hopi-core`, `dev.koga.hopi.core.rules`, all `@HopiInternalApi`)

```kotlin
object RuleEngine {
    val state: RuleState            // one volatile read; RuleState.EMPTY when nothing applies
    fun add(spec: RuleSpec): RuleResult
    fun update(id: Long, patch: RulePatch): RuleResult
    fun remove(id: Long): Boolean
    fun clear(): Int
    fun list(): List<RuleInfo>
}

class RuleState(                     // immutable
    val bySource: Array<Array<CompiledRule>>,   // indexed by source; empty arrays allowed
    val revision: Long,
)
```

- **Lock-free snapshot.** The authoring book (the rules as added, in order) and the compiled state
  are one immutable object behind one `AtomicReference`. A mutation copies, compiles and publishes
  with a compare-and-set loop; mutations are rare (a CLI command, a tap) and at most 64 entries
  long. A reader does `val s = RuleEngine.state` once per request and never sees a half-built set.
  `CompiledRule` objects are shared between successive states, so `hits` survives a republish.
- **The fast path** on every source is `if (s === RuleState.EMPTY) <original behavior>`.
  `EMPTY` is published whenever no enabled, unexhausted rule exists.
- **Per-source arrays.** At publish, each source gets only the rules whose `sources` include it and
  whose action that source can apply (the capability table below). A rule therefore never matches
  where it cannot be applied, and no per-request capability check exists. A source with no rules
  of its own sees an empty array: one array load and a zero-length loop.
- **Matching** reads the request through a `RuleRequest` view each source implements over its own
  objects (`HttpUrl` and `Headers` for OkHttp, the `HttpRequestBuilder` for Ktor, the connection's
  URL and request properties for `HttpURLConnection`), so nothing is converted unless a rule asks
  for it. The view is allocated only when the state is not `EMPTY`. Each field is read from the
  stack once per request, on the first rule that needs it (since 2026-10-25 also the
  `HttpURLConnection` host, scheme and path), and a query or header predicate is asked in place
  (`hasQueryValue`/`hasHeaderValue`: the OkHttp and `HttpURLConnection` views scan their own
  headers and query with no list per predicate; `MatcherEquivalenceTest` holds the compiled matcher
  to the list-based one it replaced on a generated case set).
- **Thread safety.** Rules and their bodies are immutable; the only mutable per-rule datum is the
  atomic `hits`. Body bytes are shared by every application and are never handed to the app as a
  mutable array (each stack gets a fresh read-only source over them).
- **Exhaustion republish.** On the JVM and Android the claiming thread runs the compare-and-set
  itself (one copy of at most 64 references, once per rule lifetime). On iOS the URLSession side
  posts it to the capture queue.
- **iOS mirror.** URLSession's hooks run on app and CFNetwork threads and call no Kotlin
  (urlsession design, "Threading"), so each publish also hands the URLSession rules, compiled to
  plain C structs (globs, method mask, mock status, headers and body as `NSData`), to the
  Objective-C library (`hopi_us_rules_publish`), synchronously, before the mutation returns:
  when `rules.add` replies, the rule is live on every source. The library keeps an atomic pointer
  that is `NULL` when the URLSession array is empty (its fast path); a non-`NULL` pointer is
  loaded and retained under an `os_unfair_lock`, which only the rules-exist path ever takes. The
  ObjC matcher is the second implementation of the same rules; one JSON table of test vectors
  (patterns, URLs, headers, expected result) in `commonTest` resources runs against both, as
  `ResponseSkip`'s copy is compared today. `hits` lives in C memory (`hopi_us_rule_claim`), which
  the Kotlin side reads and claims through the same functions on iOS.

### How records are marked

`HttpTransaction` gains three additive fields and one in-memory reference:

| Field | Value |
| --- | --- |
| `ruleId: Long?` | The rule applied, or null |
| `ruleApplied: String?` | What it did, comma-separated in this fixed order: `delay`, `edit-request`, `mock`, `fail`, `edit-response` |

A record holds the request **as sent** (after a request edit) and the response **as delivered to
the app** (after a response edit, or the mock): what crossed each boundary. The pre-edit values are
not kept; the rule, which `rules ls` shows, says what changed. A mock's record has the recorder's
`source`, `protocol` null and a duration that includes the delay. A fail's record is `Failed` with
the exception the app saw (`"java.net.SocketTimeoutException: timeout"`,
`"NSURLErrorDomain -1001: The request timed out."`).

On the wire, like `source` and `finalUrl`: the summary line, the bridge summary and the detail carry
`"rule":3,"applied":"delay,mock"` after `finalUrl` (or `source`), before `error`; omitted when null,
decoded as null when absent. They count toward the line budget as fixed fields: at most 72 bytes
together with a worst-case id (`"rule":` with 19 digits, `"applied"` with its longest value
`delay,edit-request,edit-response`), reserved inside every platform's budget (iOS's 1000
included). HAR export adds `_rule` and `_ruleApplied` to an entry. The in-app and desktop list rows
show a badge (`MOCK`, `EDIT`, `DELAY`, `FAIL`), the detail an overview row "Rule #3: delay, mock".
`hopi list`/`tail` text appends `[mock #3]`; `hopi get` shows the row; `--json` passes the lines
through. An app built before replay was removed may still send `"replayOf"`; every reader ignores
it.

## Who applies a rule: the recorder

Exactly one Hopi layer records each request today (`PlatformCapture`'s one-record rule). **That
layer, and only that layer, applies rules to it.** Concretely:

- A Ktor client whose engine a platform recorder covers (Darwin under URLSession capture; OkHttp
  and Android engines under the Gradle plugin) is ruled by that platform layer: the plugin's
  `covers` read already returns before it records, and it returns before reading rules too.
- A request the plugin records although its engine is covered (a covered engine on Android with
  the request's own `Accept-Encoding`, marked `X-Hopi-Pass-Through`) is ruled by the plugin; the
  interceptor and the wrapper already record nothing for a marked request and apply nothing
  either, so the mark in the record is the plugin's own.
- Every other Ktor client (CIO, Java, Apache, `MockEngine`, Darwin without URLSession capture,
  OkHttp without the plugin) is ruled by the plugin.

So a rule can never be applied twice, and a record's mark always describes what its recorder did.

## Capability by source

| | Ktor plugin | OkHttp | `HttpURLConnection` | URLSession |
| --- | --- | --- | --- | --- |
| mock | yes | yes | yes | yes; not background sessions; not sessions created before `start()` (except `shared`) |
| fail | yes | yes | yes | yes; same limits |
| delay before request | yes | yes | yes | yes (deferred `resume`; adds to the session's timeouts) |
| delay before response | yes | yes | yes | with a mock or fail only |
| edit request: method, URL, headers | yes | yes | yes | yes, for tasks from the hooked factories |
| edit request: body | yes | yes | yes | `HTTPBody` and upload-from-data only |
| edit response | yes | yes | yes | **no** |

Every "no" and every limit is a place where the only way to do it would bypass the app's own
stack. The reasons are in each section.

### Ktor plugin (`hopi-ktor`, every engine and target)

The plugin's `Recorder.send` at `HttpSendPipeline.Monitoring` is the decision point, after its
existing `covers` read and only for a request it records:

1. `val s = RuleEngine.state; if (s === EMPTY)` → today's path unchanged.
2. Otherwise match the `HttpRequestBuilder` against `s.bySource[KTOR]` and claim.
3. **Edit request**: rewrite the builder's method, URL and headers and, for a body edit, replace the
   outgoing content with a `ByteArrayContent` before the record's request snapshot is taken and
   before `proceedWith`. The engine then sends it with the app's engine configuration (TLS,
   pinning via the engine's own config, proxy): nothing is bypassed.
4. **Delay before request**: a wait before `proceedWith`. It suspends the request's own
   coroutine, so the app's cancellation ends it at once, and it also ends when `HttpTimeout`
   cancels the request's execution context (see "Delays and `HttpTimeout`" below), so the request
   timeout applies to it as to a slow server.
5. **Mock** (after the optional delay): the engine is not called. The plugin builds the
   `HttpClientCall` from `HttpRequestData` and `HttpResponseData` (Ktor's `HttpClientCall(client,
   requestData, responseData)`, `@InternalAPI`, pinned to Ktor 3.5.2 with a test that fails when
   Ktor changes it), runs it through `client.receivePipeline` exactly as an engine's call is run,
   and finishes the send pipeline with it. Response validation (`expectSuccess`,
   `HttpCallValidator`), `ContentEncoding`, `BodyProgress` and Hopi's own response hooks therefore
   see the mock like a real response: a mocked 500 throws `ServerResponseException` where a real
   one would. Spike item: confirm that finishing the send pipeline with a built call skips the
   `Engine` phase on Ktor 3.5.2 (a test asserts the engine's `execute` count stays 0).
6. **Fail**: thrown from `Recorder.send` instead of proceeding. `timeout` →
   `HttpRequestTimeoutException(request)` (with `HttpTimeout`, see Mechanics below); `reset` → `java.net.SocketException("Connection reset")`
   on the JVM and Android, `kotlinx.io.IOException("Connection reset by peer")` on native;
   `unknown-host` → `java.net.UnknownHostException(host)` / `kotlinx.io.IOException("…could not
   resolve host…")` (an `expect`/`actual` pair); `cancel` → `CancellationException("cancelled")`,
   which is what the call's coroutine sees when the app cancels it.
7. **Delay before response and edit response**: in the plugin's raw-response hook (before
   `SaveBody`), the same wait as before the request, then for an edit a new call with the edited `HttpResponseData`
   (status, headers, and the body as a byte channel over the replacement, with the original body
   cancelled so the engine can release the connection), passed on with `proceedWith`. The hook runs
   before `ContentEncoding` decodes, so a replaced body drops `Content-Encoding` (see Edit), and
   before `HttpCache` and `HttpCookies` at `State`, which is why the cache and cookie rules above
   apply to edited responses too.

Mechanics, decided rather than left to the spike: the mock call's `HttpResponseData` gets a
`callContext` built like an engine's (`createCallContext`): a new `Job(request.executionContext)`,
which is a `CompletableJob` because `HttpStatement`'s cleanup completes it by cast, plus the
client's dispatcher; the `HttpRequestData` carries the builder's `executionContext` and
`attributes`. The plugin runs `client.receivePipeline` on the call's response, sets it on the call,
then sets the send pipeline's subject to the call and calls `finish()`: `proceedWith(call)` would
hand the call to the `Engine` phase's interceptor, which requires an `OutgoingContent` and throws.
The app's request body is never sent: a streaming body (`WriteChannelContent`,
`ReadChannelContent`) is drained up to the body cap for the record and the rest discarded, so the
app's writer coroutine completes instead of suspending forever. `timeout` throws
`HttpRequestTimeoutException(request)` when the client has `HttpTimeout`, else
`io.ktor.client.network.sockets.SocketTimeoutException`, which is what an engine throws without it.

**Delays and `HttpTimeout`.** The send pipeline runs in the caller's coroutine, while `HttpTimeout`
ends a request by cancelling `request.executionContext`, which a bare `delay` in the pipeline does
not observe. The wait therefore ends at the first of: the deadline, the caller's cancellation, or
the completion of `request.executionContext` (an `invokeOnCompletion` handle, disposed after the
wait), in which case it throws that job's cause (`HttpRequestTimeoutException` for a timeout).

**Request edits on Ktor.** `Content-Type` is a property of the `OutgoingContent`, not a builder
header (Ktor throws `UnsafeHeaderException` for it), so a `Content-Type` edit rewraps the content
(the same bytes or channel with the new type); `Content-Length` is never set by Hopi.

Ktor's own redirects and retries (`HttpRedirect`, `HttpRequestRetry`, `Auth`) are `HttpSend`
interceptors whose `DefaultSender` executes the send pipeline once per hop, so each hop passes
`Monitoring`, is matched on its own and is its own record, as today. A mock of a 401 with `Auth`
installed is therefore retried once by `Auth`, as a real 401 would be; `times` bounds it.

### OkHttp (`hopi-android`, the existing interceptor)

Hopi's interceptor already runs **last among the application interceptors**, before
`RetryAndFollowUpInterceptor`, `BridgeInterceptor`, the cache, `ConnectInterceptor` and the network
interceptors. Rules are applied there; the network position was rejected for capture and is
rejected again here (a mock or a DNS fail below `ConnectInterceptor` would come after a real
connection and TLS handshake).

Step 1 of `HopiOkHttpInterceptor.intercept` becomes: if capture is not started, proceed; read
`RuleEngine.state` once; `EMPTY` → today's path. Otherwise match `chain.request()` against
`s.bySource[OKHTTP]`, claim, and:

- **Edit request**: `request.newBuilder()` with the edits (body replaced by a `RequestBody` over
  the replacement bytes, with the original body's content type unless the edit sets one), then
  `chain.proceed(edited)`. Everything below runs on it: the client's `CertificatePinner`,
  `SSLSocketFactory`, `HostnameVerifier`, proxy, DNS, cookie jar and cache. An edited host is
  pinned by the client's own pins for that host.
- **Delay before request**: before `proceed`. **Delay before response**: after `proceed` returned
  the response, before Hopi wraps and returns it. The wait parks in slices of at most 50 ms and
  checks `chain.call().isCanceled()` after each, because OkHttp offers no cancel callback to an
  interceptor; it is the only periodic wait in Hopi and exists only while a delayed request waits.
  On cancel it throws `IOException("Canceled")`, which `RealCall` turns into its own call-timeout
  exception when the cancel came from `callTimeout`, as for a real request. A before-response delay
  longer than `chain.readTimeoutMillis()` (when non-zero) ends with
  `SocketTimeoutException("timeout")` at that timeout, closing the real response.
- **Mock**: a `Response` built with `chain.request()` (the request as the app's interceptors left
  it), `Protocol.HTTP_1_1`, the status, message (the standard reason phrase), headers and a body
  over the bytes, `sentRequestAtMillis`/`receivedResponseAtMillis` set, **`handshake` null**: no TLS
  happened, so none is faked, and an app interceptor that inspects the handshake sees none.
  `chain.proceed` is never called. The app's interceptors above Hopi see the mock as a response
  (an auth interceptor that refreshes on 401 does so); OkHttp's cache, cookie jar and network
  interceptors below never see it, so a mocked `Set-Cookie` is not stored and the app's own network
  interceptors (Chucker, Flipper) do not record the mock.
- **Fail**: `timeout` → `SocketTimeoutException("timeout")`; `reset` →
  `SocketException("Connection reset")`; `unknown-host` → `UnknownHostException("Unable to resolve
  host \"<host>\": No address associated with hostname")`, Android's wording; `cancel` →
  `chain.call().cancel()` then `IOException("Canceled")`, so `isCanceled()` is true afterwards as
  with a real cancel. An app interceptor that retries on `IOException` retries a fail, as it would
  retry a real one.
- **Edit response**: `response.newBuilder()` with the status, message, headers and, for a body edit,
  a body over the replacement (the original body closed first, so the connection is released or
  discarded by OkHttp as for any closed body). The edit happens above `BridgeInterceptor`, so the
  body it replaces is decoded unless the app set its own `Accept-Encoding` (then the replacement
  drops `Content-Encoding`, see Edit). The cache below never stores the edit: `CacheInterceptor`
  writes the network body as it is read, so a header-only edit caches the original as the app reads
  it, and a body edit, which closes the original unread, aborts that write and caches nothing.

OkHttp's own redirects and retries happen below Hopi's position, so a rule matches the call's
request once, as the call is one record. The hook in `RealCall` is unchanged; a request through
OkHttp 3.12/3.13 (left alone by the runtime) gets no rules, as it gets no record.

### `HttpURLConnection` (`hopi-android`, the existing wrappers)

The wrapper takes its request snapshot before the delegate's first connecting call. The rule read
happens there, once per connection, and journaling of the app's setters is unconditional because it
is only field writes inside setters the app calls (no per-request cost): the wrapper keeps the
streaming mode (`setFixedLengthStreamingMode`, `setChunkedStreamingMode`, which have no getters),
and the `SSLSocketFactory`, `HostnameVerifier` and `Proxy` the app passed, if any. Everything else
(timeouts, `doOutput`, `useCaches`, `instanceFollowRedirects`, `ifModifiedSince`,
`allowUserInteraction`, request properties) is read back from the delegate, which holds it.

At the first connecting call: `EMPTY` → today's path. Otherwise match against
`s.bySource[URL_CONNECTION]`, claim, and:

- **Mock**: the delegate never connects. The wrapper answers every overridable method from the
  mock: `connect()` does nothing; `getResponseCode`, `getResponseMessage`, `getHeaderField*` (by
  index and by name, with the status line at index 0 as Android does), `getHeaderFields`,
  `getContentLength*`, `getContentType`, `getContentEncoding`, `getDate`, `getExpiration`,
  `getLastModified`, `getContent*` answer from the mock's status and headers; `getInputStream`
  returns the body for a status below 400 and throws `FileNotFoundException(url)` at 400 and above,
  where `getErrorStream` returns the body (null below 400, and null before any connecting call, as
  it does on an unconnected delegate), as Android's implementation does;
  `getOutputStream` (only with `doOutput`, else the same `ProtocolException` the platform throws)
  returns a stream that captures up to the cap for the record and discards the rest;
  `usingProxy()` is false. On a `HopiHttpsURLConnection`, `getServerCertificates`,
  `getPeerPrincipal` and `getCipherSuite` throw `SSLPeerUnverifiedException("No peer certificate
  (response mocked by Hopi)")`: no certificate is invented, so an app that pins by reading the
  server's certificates after connecting fails closed, as it should. `disconnect()` goes to the
  delegate, which never connected. The test that reads `android.jar` for every overridable method
  gains a second run over the mock mode: each method must have a mock answer or a stated delegate
  pass-through.
- **Fail**: the connecting call throws `SocketTimeoutException("connect timed out")` (`timeout`;
  `"Read timed out"` when it comes after a before-response delay), `SocketException("Connection
  reset")`, `UnknownHostException("Unable to resolve host \"<host>\": No address associated with
  hostname")`, or `SocketException("Socket closed")` for `cancel` (what `disconnect()` from another
  thread produces). The delegate never connects.
- **Delay**: the wrapper waits on its own monitor before calling the delegate (before request) or
  after the delegate's response arrived and before the first value is returned (before response).
  `disconnect()` notifies the monitor and the waiting call throws `SocketException("Socket
  closed")`; a before-request delay longer than `getConnectTimeout()` ends with `"connect timed
  out"`, a before-response delay longer than `getReadTimeout()` with `"Read timed out"` (non-zero
  timeouts only). No polling: the only waker is `disconnect()` or the deadline.
- **Edit request**, method and header sets and adds: `setRequestMethod` / `setRequestProperty` /
  `addRequestProperty` on the delegate before its first connecting call. **Header removal** cannot
  be done on that delegate: Android's `HttpURLConnectionImpl.setRequestProperty(name, null)` logs
  "Ignoring header … because its value was null" and keeps the old value, and there is no remove.
  A rule that removes a header therefore takes the URL-edit path below with the same URL, the new
  delegate getting every read-back property except the removed names. **URL edit**: a new delegate from the real
  `URL(edited).openConnection(proxy)` (the app's proxy if it passed one), configured with
  everything read back or journaled above, including the app's `SSLSocketFactory` and
  `HostnameVerifier`; the old delegate is never connected. So the new connection carries exactly
  the TLS settings the app gave the old one, or the process defaults if it gave none. The new
  delegate is opened from inside `hopi-android`, which is not instrumented, so it is the wrapper's
  delegate and never a second recorded connection. **Body edit**:
  the app writes into a capture-only stream; at its `close()` or the next connecting call the
  wrapper sets fixed-length streaming mode to the replacement's size on the delegate, opens the
  delegate's output stream and writes the replacement.
- **Edit response**: the wrapper's response methods answer the edited status and headers (built
  once, at the first response read); a body edit serves the replacement from `getInputStream` /
  `getErrorStream` by the same status rule as a mock and closes the delegate's real stream.

### URLSession (`hopi-urlsession`, the existing library plus three pieces)

Capture stays observe-only for every request no rule claims. Rules add, all inside the Objective-C
library and behind the `rules` switch of `HopiUrlSession.start { }` (default true):

1. **`HopiUSRuleProtocol`**, an `NSURLProtocol` subclass that claims only requests a mock or fail
   rule matches. It is put in front of every session's protocol list by replacing
   `+[NSURLSession sessionWithConfiguration:delegate:delegateQueue:]` (spike 1: the funnel both
   initializers use) to pass a copy of the configuration with the class prepended. It is
   registered with `+[NSURLProtocol registerClass:]` for `URLSession.shared` (spike 2) only while
   the URLSession rule array is non-empty, and unregistered when it empties, so the shared session
   pays nothing without rules. Background configurations are passed through untouched (custom
   protocols do not run there).
2. **Task factory hooks** for request edits and delays: `dataTaskWithRequest:`,
   `dataTaskWithRequest:completionHandler:`, `dataTaskWithURL:`, `dataTaskWithURL:completionHandler:`,
   the private `_dataTaskWithRequest:delegate:completionHandler:` and
   `_dataTaskWithURL:delegate:completionHandler:` (spike 6), the upload factories already hooked,
   and the download factories with a request or URL. Each is self-checked by encoding like the
   existing hooks; one that fails the check is left alone (tasks it creates get mock and fail but
   not edit or delay). **They are installed the first time an edit or delay rule is added**
   (a second `dispatch_once`), never at `start()`: an app that never adds one pays nothing for
   them. Installing late is safe for chaining: Hopi's IMP calls whatever IMP was current, a
   swizzler installed before or after included; Hopi never uninstalls.
3. **The deferred resume** in the existing `resume` hook.

Per action:

- **Mock and fail.** `+canInitWithTask:` reads the library's atomic pointer; `NULL` → `NO`. This
  message plus one load is the whole no-rule cost on a session created after `start()`, the one
  exception to "zero" (measured below). With rules, it returns `NO` for a task already ruled at
  creation (a factory plan, below) or a background task; otherwise it matches
  `task.currentRequest` against the URLSession array in order. The first matching rule owns the
  request (one rule per request): if it is a mock or fail and the claim succeeds, the protocol
  attaches the decision to the task (associated object) and returns `YES`; if it is an edit or
  delay-only rule (its task came from an unhooked factory, or was created before the factory hooks
  were installed), it returns `NO` and the request goes unruled, never on to a later mock. A second
  `canInitWithTask:` for the same task finds the decision and returns `YES` without claiming
  again. `+canInitWithRequest:` always returns `NO`: URLSession tasks reach `canInitWithTask:`
  because the class overrides it, and the callers of the request form (`NSURLConnection`, other
  loaders sharing the registered classes) are not sources Hopi records, so a rule applied there
  would leave no mark. If URLSession asks again for a redirect hop, that hop is matched as a
  request of its own, as Ktor's hops are. That each OS calls `canInitWithTask:` for session tasks,
  and whether a redirect hop asks, are test items; if an OS calls only the request form for
  session tasks, mock and fail are unsupported on that OS and `rules.add` reports it in `unsupported`. `-startLoading` serves the
  decision on a private serial queue: after `delay` (a `dispatch_after`; `-stopLoading`, which the
  task's `cancel` triggers, cancels it), a mock calls the client's `didReceiveResponse:` with an
  `NSHTTPURLResponse` (status, headers, `HTTP/1.1`, cache policy `NSURLCacheStorageNotAllowed`),
  `didLoadData:` and `didFinishLoading`; a fail calls `didFailWithError:` with `NSURLErrorDomain`
  `-1001` (`timeout`), `-1005` (`reset`: `NSURLErrorNetworkConnectionLost`), `-1003` (`unknown-host`:
  `NSURLErrorCannotFindHost`), each with the system's `NSLocalizedDescription`,
  `NSURLErrorFailingURLErrorKey` and `NSURLErrorFailingURLStringErrorKey`, while `cancel` calls
  `[task cancel]` itself so the task ends with `-999` exactly as an app cancel does. No network, no
  challenge, no TLS. The app's delegate, completion handler or async continuation receives the
  callbacks through URLSession, on its own queue, as for a response from the network. The
  existing byte path records the task (spike 4); the resume hook copies the rule mark into the
  task's state, and the record is marked. Tasks are claimed when they start loading, so a task
  created before a rule existed and resumed after is mocked. A mocked download task gets its body
  through the same calls; URLSession writes it to the temporary file and calls
  `didFinishDownloadingToURL:` as for a network download (a test item). An upload task's body is
  never read by the protocol, so `didSendBodyData:` progress is not reported for a mocked upload
  (stated).
- **Delay before request.** At creation, a factory hook (installed, and the state non-empty) that
  matches a delay-only or edit rule as first match attaches a plan (rule id, delay) in the same
  associated object that already carries an upload body to the `resume` hook, so `resume` pays no
  extra lookup. On the first resume of a task with a delay, the hook creates the capture state as
  usual and calls the original `resume` from a `dispatch_after` on the capture queue instead of at
  once: the task stays suspended meanwhile, and an app `cancel` completes it with `-999` and makes
  the late `resume` a no-op (spike 5). An app `suspend` during the delay must not be undone by the
  late `resume`, so `-[NSURLSessionTask suspend]` is hooked together with the task factories (same
  lazy install, same self-check): a suspend drops the pending resume, and the app's next `resume`
  resumes at once. During the delay the task reports `NSURLSessionTaskStateSuspended` where the app
  expects `Running` (stated). The app's own session sends it, unchanged, and its timeouts start at
  that resume.
- **Delay before response** on a real request: **unsupported.** The response reaches the app
  through CFNetwork's private connection methods; holding it would mean blocking or re-ordering
  those calls, which the capture design forbids, or loading the request through Hopi (below).
  With a mock or fail the protocol owns the timing, so the delay is supported there.
- **Edit request.** The factory hook that matched an edit rule passes an edited
  `NSMutableURLRequest` copy (method, URL, headers, `HTTPBody`), or for upload-from-data the
  replacement data, to the original factory (a URL factory, which cannot take headers, calls the
  original IMP of the matching request factory with the edited request instead, keeping the
  completion handler), and marks the copy with an `NSURLProtocol` property
  (`dev.koga.hopi.ruled`) so a factory that calls another factory internally does not apply it
  twice; the copy is Hopi's own request, which is why setting a property on it is allowed where
  the capture design forbids it on the app's. **The app's own session then creates and sends the
  task**: its delegate answers every challenge, its pinning (delegate-based, `NSPinnedDomains` or
  a TrustKit-style swizzle) applies to the edited host, its cookies, cache and proxy apply. What the
  app can observe: `task.originalRequest` is the edited request. A body edit on a file or stream
  upload (`fromFile:`, `uploadTaskWithStreamedRequest:`, an `HTTPBodyStream`, which includes Ktor
  Darwin's channel bodies) is unsupported (`rules.add` warns; the URL and header edits of the same
  rule still apply).
- **Edit response: unsupported.** Rejected options, for the record:
  - *Load the request inside the protocol through a Hopi session and rewrite the result.* This is
    the DebugSwift design. The load happens in another session with another configuration
    (cookie storage, cache, proxy dictionary, TLS minimum, HTTP/3), and the app's
    authentication-challenge delegate is no longer the one asked: relaying the challenge through
    `URLProtocol:didReceiveAuthenticationChallenge:` hands the app a challenge whose sender is
    Hopi, which URLSession does not route reliably to the session delegate, and a pinning library
    that swizzles delegates may never see it. A rule could then silently remove pinning. Rejected
    by priority 4.
  - *Load it inside the protocol through the app's own session* (`task.session`, spike 7) with a
    loop guard. Challenges would reach the app's delegate, but for a task the app never created,
    and the protocol must then replay every delegate callback onto the app's task; cookies and
    cache are handled twice. Too much behavior Hopi would have to fake; rejected.
  - *Rewrite inside `_didReceiveResponse:` / `_didReceiveData:` before the original runs.* It
    changes what a private CFNetwork method receives, which the self-check cannot validate.
    Rejected.
  The answer for an app that needs a different response is a mock, which is supported.
- **Not covered:** background sessions (out of process; custom protocols never run there, and
  their tasks keep today's metadata path), sessions created before `start()` other than `shared`
  (they lack the protocol; `start()` is meant to be the first call at launch, and an SDK that
  builds its session in a `+load` is the documented gap), `NSURLSessionWebSocketTask` and stream
  tasks (no record either), tasks from an unhooked factory (no edit or delay), AVFoundation,
  WKWebView.

Ktor Darwin under URLSession capture is a URLSession client for rules as for records: mock, fail
and request edits work (its `dataTaskWithRequest:` is hooked; a channel body cannot be edited), a
response edit does not. A KMP app that needs response edits on iOS can leave URLSession capture
off for that build, which makes the Ktor plugin record and rule Darwin requests itself.

## Replay (removed)

Replay, re-sending a recorded request (optionally edited) through the client or session that sent
the original, was built on every source and removed from the product on 2026-10-25: it is the only
feature in which Hopi itself originates a request, and the owner put it in the backlog with no
priority (docs/ROADMAP.md, "Replay (no priority)"). The full implementation, its per-source
plumbing (a weak per-client target in each record, a hand-off of the pre-allocated id to the
recorder, a count of replays in flight in the rule state) and its tests are in git commit
`a5d160b`. Removing it only removed work: recorders no longer read back a connection's timeouts and
TLS objects, look up an OkHttp call's client, read the task's session on URLSession, or look for a
hand-off. The bridge answers a `replay` line as an older app answers any command it does not know
(`BAD_REQUEST`: "unknown cmd", or "malformed JSON object" when it carries an `edit` object).

## Surfaces

### Bridge

All write commands use the existing authenticated session (Android: peer uid and proof; iOS and
JVM: the token) and are accepted only after `hello` on that connection (`BAD_REQUEST`, "hello
first"). That rule only orders lines on the app's side; it cannot tell the app that the CLI has
checked the proof, and the read commands' handshake pipelines token, `hello` and the request in one
write. **Write commands are never pipelined that way**: `hopi-connect` sends `rules.*` and
`blob.put` only after it has read `hello` and verified it (`protocol`, `app`, `rules`, and on
Android the proof), so a squatter holding the socket name never receives a rule or a body; it gets
`hello` and nothing else. Requests stay at most 4096 chars,
enforced while framing as today. Rule specs parse with a new bounded tree parser in core (depth
6, the same 4096-char line), since `parseFlatJsonObject` is flat.

**Compatibility.** The CLI requires `"protocol":1`, so the integer stays 1: changing it would make
every current CLI refuse a new app. `hello` gains `"rules":true` when the engine is present and the
app's switch allows rules (`false` otherwise; absent on an older app). The new CLI sends write
commands only when it is `true`, and otherwise says "this app's Hopi does not support rules" (exit
4). An older app answers a new command `BAD_REQUEST`: "unknown cmd" for a flat one, "malformed JSON
object" for one with a nested object (its flat parser refuses those first); neither changes its
state. `tail`'s `"rules":true` is sent only to an app whose `hello` said `"rules":true` (an older
app ignores unknown fields, so it would be harmless, but the desktop and the CLI follow the same
rule). The new fields in records are additive, as above; older decoders ignore unknown fields.

```json
{"cmd":"rules.add","rule":{...}}          // → {"type":"rule",...} with "id" and "unsupported"
{"cmd":"rules.list"}                      // → {"type":"rule",...}* then {"type":"end"}
{"cmd":"rules.update","id":3,"enabled":false,"priority":5,"times":10,"resetHits":true}  // → {"type":"rule",...}
{"cmd":"rules.remove","id":3}             // → {"type":"ok"} | NOT_FOUND
{"cmd":"rules.clear"}                     // → {"type":"ok","removed":N}
{"cmd":"blob.put","blob":1,"offset":0,"data":"<base64, at most 3072 chars>"}  // → {"type":"ok","blob":1,"size":N}
{"cmd":"tail","since":0,"rules":true}     // as today, plus {"type":"rules","rev":N} when the set changes
```

A rule object on the wire:

```json
{"label":"users 500","priority":0,"enabled":true,"times":3,
 "match":{"methods":["GET"],"scheme":"https","host":"*.example.com","path":"/users/*",
          "query":[{"name":"page","value":"2"},{"name":"trace","absent":true}],
          "headers":[{"name":"X-Env","value":"stag*"}],"sources":["okhttp","ktor"]},
 "delay":{"beforeRequestMs":0,"beforeResponseMs":2000},
 "mock":{"status":500,"headers":[["Content-Type","application/json"]],
         "body":{"text":"{\"error\":\"x\"}"} | {"base64":"…"} | {"blob":1,"size":N},
         "fromTransaction":42},
 "fail":"timeout|reset|unknown-host|cancel",
 "edit":{"request":{"method":"POST","host":"staging.example.com","port":8443,"path":"/v2/users",
                    "setQuery":[["k","v"]],"removeQuery":["k"],"setHeaders":[["K","V"]],
                    "addHeaders":[],"removeHeaders":["K"],"body":{...}},
         "response":{"status":503,"setHeaders":[],"addHeaders":[],"removeHeaders":[],"body":{...}}}}
```

At most one of `mock`, `fail`, `edit`; a rule with none needs a non-zero delay. A reply's rule
object adds `id`, `hits`, `exhausted`, and `unsupported` (a list of `{"source","reason","applies"}`
for every source in the rule's `sources` that cannot apply all or part of its action, from the
capability table, so the CLI warns at once instead of the rule silently never matching there).
`applies` is false for an exclusion (the source never gets the rule: a response edit on URLSession)
and true for a stated difference (a mocked 3xx the stack does not follow). A rule that names no sources is
checked against the sources that can record on the app's platform (`platformSources`, an
expect/actual set beside `Capabilities`: Ktor, OkHttp and `HttpURLConnection` on Android; Ktor and
URLSession on iOS; Ktor on a plain JVM),
so an Android app is never told what URLSession cannot do; the per-source arrays still cover every
source. Ktor is on every platform and excludes nothing, so only a rule whose named sources all
exclude it applies nowhere (`Capabilities.appliesNowhere`). Bodies are echoed only as
`{"bytes":N}`, and header values (match predicates, mock and edit headers) through
the store's redactor, like every other outward value.

**Bodies larger than a line.** A body that does not fit inline is uploaded first as a *blob*:
consecutive `blob.put` lines, each at most 3072 base64 chars (2304 bytes), `offset` equal to the
bytes received so far, at most 2 blobs and 1 MiB each per session, held in memory only. A
`rules.add` names it with its final `size`, which the app checks against what it
received (the sequential `offset` already proves no line was lost; core has no digest and gains
none for this), then the blob moves into
the rule and leaves the session. Blobs die with the session. A 1 MiB body is
456 lines; the CLI pipelines them in windows of 32 (half the 64-line reply queue) and reads the
acknowledgements between windows, so neither side can block the other. Any failed `blob.put`
(offset mismatch, size, bad base64) discards that blob.

**Errors**: `BAD_REQUEST` (validation, with a message naming the field: `"match.path: pattern
longer than 256 chars"`), `NOT_FOUND` (rule or transaction id), `LIMIT` (a limit in the table,
with the limit in the message), `UNSUPPORTED` (`"rules":false`), plus the existing
codes. **Rules notices** (`{"type":"rules","rev":N}`) go only to a `tail` that asked for them, so an
older client never sees an unknown line type on its stream.

### CLI (`hopi`)

All take the existing target options (`--app`, `--platform`, `--device`, `--bridge-file`) and
`--json`.

```text
hopi mock add  --match 'GET */users/*' --status 500 [--header 'K: V']... [--body @err.json | --body '{"a":1}' | --body @-]
               [--from <txn-id>] [--delay 2s] [--times N] [--priority N] [--source okhttp,ktor] [--label TEXT] [--disabled]
hopi fail add  --match ... --with timeout|reset|unknown-host|cancel [--after 30s] [common options]
hopi delay add --match ... [--before-request 500ms] [--before-response 2s] [common options]
hopi edit add  --match ... [--method M] [--host H] [--port N] [--path P] [--set-query k=v] [--remove-query k]
               [--set-header 'K: V'] [--add-header 'K: V'] [--remove-header K] [--body @f]
               [--status N] [--set-response-header 'K: V'] [--remove-response-header K] [--response-body @f] [common options]
hopi rules ls | rm <id>... | clear | enable <id>... | disable <id>... | reset <id>...
```

`--match` is the shorthand above; `--query` and `--match-header` add predicates. `--body` follows
curl: `@file` reads a host file, `@-` stdin, anything else is the literal text. `--from` builds the
mock from a record inside the app. Durations take `ms` and `s`. `add` prints the rule
(`#3  GET */users/*  mock 500 (61 B)  times 3`) and one `hopi: warning: …` line on stderr per
`unsupported` entry. `rules ls` prints `ID ON PRI HITS MATCH ACTION`. New exit code 4: unsupported
(rules off or absent in the app). Validation errors from the app are exit 1, like `BAD_REQUEST` today.

### In-process API (`hopi-rules`, `dev.koga.hopi.rules`; Swift: `HopiRules.shared`)

```kotlin
public object HopiRules {
    public fun mock(match: String, block: HopiMock.() -> Unit = {}): Long     // rule id; -1 in the noop
    public fun fail(match: String, failure: HopiFailure, block: HopiRuleOptions.() -> Unit = {}): Long
    public fun delay(match: String, block: HopiDelay.() -> Unit): Long
    public fun edit(match: String, block: HopiEdit.() -> Unit): Long
    public fun list(): List<HopiRuleInfo>          // empty in the noop
    public fun setEnabled(id: Long, enabled: Boolean): Boolean
    public fun remove(id: Long): Boolean
    public fun clear()
}

// a test
val id = HopiRules.mock("GET */users/*") {
    status = 500
    header("Content-Type", "application/json")
    body("""{"error":"boom"}""")
    delay(beforeResponse = 2.seconds)
    times = 1
}
```

`HopiMock`, `HopiDelay`, `HopiEdit`, `HopiRequestEdit` and `HopiRuleOptions` (label, priority,
times, sources, query and header predicates) are plain builder classes; `HopiFailure` is an enum.
Validation errors throw `IllegalArgumentException` with the bridge's message (a Kotlin API); limits
throw `IllegalStateException`. No `inline` function and no `const` (`:checkNoopParity` forbids
both). Calls are thread-safe and take effect before they return. The module depends on `hopi-core`
only; it works with any recorder (or none: a rule waits for one). Tests call `HopiRules.clear()` in
their teardown; the engine is process-wide like `HopiStore.default`.

### Desktop viewer (`hopi-desktop`)

A Rules panel (⌘L) beside the list: rows with id, an enable toggle, match, action and
`hits`/`times`; add and edit forms for each action (headers as a table, a body from a text area or
a file chooser, uploaded as a blob); remove, clear. The detail pane gains "Mock this response"
(a `rules.add` with `fromTransaction`, method plus scheme, host and path of the record, editable
before sending). The panel
lists on connect and re-lists on each `{"type":"rules"}` notice of its tail. Its hits are only
ever the app's: a summary with a `rule` field the last list may not include lists again, instead
of being counted on top of it (the list replies on the request connection and the summaries come
on the tail, in no order between the two, so a local count could add a hit the list already had);
so does a `get` reply with one (a selected row, an export), which can bring a URLSession record's
mark before the tail does. "May not include" is an id above the highest the viewer had seen (from
the tail or a `get`) when that list was asked for (Ktor, OkHttp and `HttpURLConnection` claim a
rule before the record's id exists), once per request, or a mark that appears only now on an id the
viewer held without one, or did not hold at all (URLSession claims a mock or fail when the task
starts loading, after its record started). It applies from the moment the link is up, so a ruled
request recorded just after the app wrote the first list of a connect costs one more list, not a
hit short. Lists coalesce: a burst of
ruled requests costs at most one `rules.list` in flight and one queued (while ruled requests keep
coming, one list per round trip), a quiet app none, and nothing polls. Each `unsupported` entry the app reported shows under the row: an exclusion as its
own line in full contrast, "not applied on urlsession: …", a stated difference as a muted warning
line. A rule whose named sources all exclude it reads "inactive" after its hits, its match dimmed
and its switch as set; a rule naming none never does, since the line does not say which sources
the app's platform has. All of it goes through `hopi-connect`, which gains the commands, the blob
upload and `BridgeDecoder` support for the rule line and the new fields.

### In-app screen (`hopi-ui`)

A "Rules" entry in the inspector's top bar opens a list: one row per rule (match, action, hits,
an enable switch), remove per row, "Remove all". Each `unsupported` entry shows under the row as
in the desktop panel (an exclusion as "not applied on urlsession: …" in full contrast, a stated
difference as a muted warning), and a rule that every source it is checked against excludes (the
named ones, or the platform's when it names none: `Capabilities.appliesNowhere`) reads "inactive".
That is not every rule that never applies: on iOS with URLSession capture, a response edit or a
before-response delay that names no sources reads active (Ktor is one of the platform's sources and
excludes nothing, though its plugin defers to URLSession for the Darwin engine), its "not applied
on urlsession" line the only sign; a rule naming only sources the platform lacks (okhttp on iOS)
gets no line at all. The detail screen gains "Mock this response"
(creates an enabled rule from the record, exact method, scheme, host and path, `times` unset, with
a confirmation that offers Undo; disabled with the reason when the body is not held in full or is
held in an encoding a mock does not decode, see Mock). There is no rule editor in the app: phones are a
poor place to type globs and headers; the CLI and the desktop panel are. The list is read from
`RuleEngine` only while the screen is composed (a `revision` flow the engine emits on publish), so
the closed screen costs nothing. No public API change: `HopiScreen` and `HopiLauncher` are
unchanged, so `hopi-ui-noop` is unchanged.

## Configuration switch

Each recorder's configuration gains `rules: Boolean = true`: `HopiConfig` (Ktor), `HopiUrlSessionConfig`,
and the Gradle DSL `hopi { rules = true }` (generated into the variant's configuration). `false`
means that recorder never reads the rule state (it becomes a constant-false branch) and, on iOS, no
protocol is injected and no factory is hooked. The engine and the API stay; `hello` reports
`"rules":false` when every recorder that started has it off. The default is true because an empty
rule set costs one read (and on iOS one message), and an inspector whose mock command needs a
rebuild first fails the agent use case; the switch exists for the app whose code reads
`configuration.protocolClasses` or that suspects the rules layer. The noops accept and ignore it
(`DefaultsParityTest` covers the new default).

## Module layout, noop twin and release exclusion

| Module | Content |
| --- | --- |
| `hopi-core` | `RuleEngine`, specs, compiled rules, matcher, the tree parser, bridge commands, record fields (all `@HopiInternalApi`; core's ABI dump updated) |
| `hopi-rules` (new) | `HopiRules` and its builders over `RuleEngine`; depends on core; targets android, iosArm64, iosSimulatorArm64, jvm |
| `hopi-rules-noop` (new) | The same public API, no dependency at all; every call returns `-1`, `false` or an empty list and stores nothing |
| `hopi-ktor`, `hopi-android`, `hopi-urlsession` | The per-source application above; the ObjC protocol, factory hooks and C matcher live in core's ObjC library next to the capture code |
| `hopi-connect`, `hopi-cli`, `hopi-desktop`, `hopi-ui` | Surfaces |

Knock-ons, all part of the change:

- `:checkNoopParity` gains the `hopi-rules` pair; both get `abiValidation` dumps (`updateKotlinAbi`
  on both, deliberately), and the parity task's `inline`/`const` ban applies.
- The `dev.koga.hopi` Gradle plugin's per-variant twin substitution gains the third pair
  (`hopi-rules` → `hopi-rules-noop` in non-debuggable variants), and its TestKit tests check it.
- KMP shared modules select `hopi-rules` with `-Phopi.enabled` like the other twins and export it
  from the iOS framework for Swift.
- `:sample:androidApp:verifyReleaseUsesNoop` (and `verifyHopiSelection`) expect exactly four noops
  on the release classpath, `:hopi-rules-noop` included, and its classes the noop jar's; the
  existing "no type of `dev.koga.hopi.core` defined or referenced" check already excludes the
  engine. `verifyDebugCapture` adds a positive control (`RuleEngine` reachable in the debug APK).
- `:sample:shared:verifyIosReleaseExcludesCapture` also fails on any `HopiUSRuleProtocol` class
  symbol and any `_hopi_us_rule` symbol without the flag, and requires them with it.
- `hopi-ktor-noop`, `hopi-ui-noop`, `hopi-android-noop`, `hopi-urlsession-noop` change only by the
  `rules` property where their real twin gains it.

**Persistence: none.** No rule, body or blob is written to a file, preferences, `NSUserDefaults`,
the proof file or `bridge.json`. There is no import from a file, an intent, a broadcast, a deep link,
a launch argument or an environment variable: the only ways in are the authenticated bridge, the
in-process API and the in-app screen. Restarting the app clears every rule; the CLI then lists none. `hopi clear` (the store) leaves
rules in place; `hopi rules clear` removes them.

## Security summary

- No action contacts the network outside the app's own stack: a mock and a fail contact nothing;
  an edited request goes through the app's client or session with its own TLS, pinning, verifier,
  proxy and challenge delegate. Hopi never offers an action that changes TLS settings, trust, a
  verifier, a proxy or a challenge answer, and never fabricates a certificate or handshake.
- Scheme changes are rejected; origin changes strip credentials unless set explicitly. An edited host is judged by the app's own stack like any host:
  its pins (or none), Android's network security config (cleartext, trust anchors) and iOS's ATS.
- Nothing a rule produces outlives it: mocks and response edits never set cookies (`Set-Cookie`
  rejected) and never enter an HTTP cache (see Mock), so no persisted store of the app's keeps a
  rule's output after the session.
- Unsupported, with reasons above: URLSession response edit and before-response delay on real
  requests, URLSession body edit of file and stream uploads, rules on background sessions.
- The bridge gains write commands, gated by the same authentication as reads plus `hello` first,
  sent by the host only after it verified `hello` (never pipelined with it, so a squatter receives
  none), bounded by the limits, never echoing bodies and redacting header values. Its reach (debuggable
  builds on Android; the simulator and the JVM on 127.0.0.1 with a token) is unchanged.

## Architecture contract changes

`docs/ARCHITECTURE.md` changes before the code:

- Modules table: `hopi-rules`, `hopi-rules-noop`. Consumer API: `HopiRules`, the `rules` switches.
- Data model: `ruleId`, `ruleApplied`. Agent access: the new summary
  fields, the commands, `blob.put`, the `rules` notice, `hello.rules`, the new error codes, CLI
  commands and exit code 4.
- URLSession capture: "No … `protocolClasses` … changes" becomes "no `protocolClasses` change
  except, with `rules` on, Hopi's protocol class first in sessions created after `start()`, which
  claims only requests a mock or fail rule matches"; the list of replaced methods gains the
  session factory and, once an edit or delay rule was added, the task factories.
- "Idle cost is zero": the DebugSwift row gains that a rule's protocol answers mocks without
  re-issuing anything, and that the OkHttp before-response wait checks for cancel every 50 ms
  only while a delayed request waits.
- The roadmap item moves to built when it is.

## Tests

Base check (every item runs in it unless marked):

- **Core, JVM** (`:hopi-core:jvmTest`): glob matcher table (the shared vectors), pathological
  patterns (`*a*a*a*…` against long inputs stay under a bound), shorthand parser, query and header
  predicates, ordering (priority, then newest), first match, disabled and exhausted rules,
  `times` under 16 threads (exactly `times` claims, the republish happens once and the state returns
  to `EMPTY`), snapshot consistency under concurrent add/remove while 8 threads match, every
  validation rule and limit, the tree parser's bounds (depth, line), bridge sessions for every
  command (hello first, blob sequencing, windows, size mismatch, session end frees blobs,
  redaction of rule headers, `rules` notices only on opted-in tails, `hello.rules`), record fields
  through `TransactionJson`, the line budget with them, `BridgeDecoder` round trips, an old line
  without them.
- **Ktor, JVM** (`:hopi-ktor:jvmTest`, `MockEngine` and a loopback CIO server): every action;
  engine `execute` count 0 for mock and fail; `expectSuccess` throws on a mocked 500;
  `ContentEncoding`; `HttpTimeout` ends a long delay; cancelling the caller ends a delay; a covered
  engine (a test `PlatformCapture.cover`) applies nothing; a pass-through request applies once;
  `Auth` retries a mocked 401 once; `HttpRedirect` follows a mocked 302 to a second, separately
  matched hop (two records); a streaming request body is drained under a mock and its writer
  completes; `HttpTimeout`'s request timeout ends a before-request delay with
  `HttpRequestTimeoutException`; `HttpStatement.execute { }` over a mock completes its call job;
  `HttpCache` does not store a mock or an edited response, and `HttpCookies` stores no cookie
  (Set-Cookie rejected); a body edit under `ContentEncoding` drops `Content-Encoding` and the app
  reads the replacement; a `Content-Type` edit; the `HttpClientCall` constructor test.
- **Android host tests** (`:hopi-android:testAndroidHostTest`, OkHttp `MockWebServer`): a mock
  leaves the server's request count at 0 and app interceptors see it; request edit reaches the
  server; **an edit to a host the client pins with a wrong pin still fails with
  `SSLPeerUnverifiedException`** (MockWebServer over TLS with a held certificate); response edit
  with the cache not storing the edit (header-only edit caches the original, body edit caches
  nothing); a body edit with an app-set `Accept-Encoding: gzip` drops `Content-Encoding`; a mocked
  302 and 401 reach the app unfollowed and the client's `Authenticator` is not called; each fail's
  exception class and message; delays with `call.cancel()`, `callTimeout` and `readTimeout`.
  `HttpURLConnection`: a mock never connects the delegate (a counting fake delegate plus the server
  count); the mock-mode method coverage test over `android.jar`; 4xx mock throws from
  `getInputStream` and serves `getErrorStream`; certificate getters throw on an HTTPS mock; URL edit
  builds a new delegate with the app's `SSLSocketFactory` (a recording factory sees the
  connection) and `HostnameVerifier`; a header removal reaches the server without the header (the
  rebuild path); `getErrorStream` is null before a connecting call on a 4xx mock; body edit with
  fixed-length mode; delay ends on `disconnect()`.
- **iOS** (`:hopi-urlsession:iosSimulatorArm64Test`, `:hopi-core:iosSimulatorArm64Test`, the
  existing in-process server and fixtures): the spikes 1 to 7 as tests; which `canInit` form each
  OS calls and whether a redirect hop asks again; mock and fail through a
  completion handler, a full delegate, a partial delegate and a nil-delegate session, each fail's
  `NSError` code and keys; `canInitWithTask:` returns `NO` with no rules and claims once;
  `canInitWithRequest:` returns `NO` always; an edit rule that is first match for a task from an
  unhooked factory keeps a later mock rule from claiming it; a mocked download task delivers its
  file through `didFinishDownloadingToURL:`; an app `suspend` during a before-request delay is not
  undone by the deferred resume; a URL factory's edit goes through the request factory once;
  `shared` registers and unregisters with the rule set; delay with a cancel during it; each
  factory hook's self-check and an edit through each; a factory that calls another does not edit
  twice; a delegate that rejects every server-trust challenge still rejects the edited request
  (loopback TLS in the test server); the factory hooks are absent until the first edit or delay
  rule; the C matcher against the shared vectors.
- **Parity and release**: `:checkNoopParity` with the new pair; the noop's JVM test that no
  `hopi-core` is on its classpath; `verifyReleaseUsesNoop`, `verifyHopiSelection`,
  `verifyDebugCapture` and `verifyIosReleaseExcludesCapture` as extended; the Gradle plugin's
  `functionalTest` for the third pair.
- **CLI and connect** (`:hopi-cli:test`, `:hopi-connect:test`): argument parsing of every new
  command, blob upload windows against the scripted bridge, the warnings, exit code 4, `--wait`,
  no write line sent before `hello` was verified (a scripted Android squatter whose `hello` lacks the
  proof receives `hello` only),
  an older app without `hello.rules`, an older CLI's `list`/`tail` against a new app's lines.
- **Desktop and UI** (`:hopi-desktop:test`, `:hopi-ui:jvmTest`): the panel's list, toggle, add,
  remove, exclusion lines and hit counts against a JVM bridge (`:hopi-connect:test` holds the
  counts to the engine's: a hit a list already counted, a marked backlog on connect, a mark that
  comes only with the end, a burst with a list in the middle); the in-app rules list and "Mock this
  response".

## Device verification plan

One device at a time, `./gradlew --stop` before booting, everything stopped afterwards (emulator,
simulator, `adb kill-server`, the dev server, Gradle).

1. **Android, API 35 emulator**, the sample's debug build, `sample/devserver/devserver.py`
   logging every request (the oracle): for each source (the Ktor screen, an OkHttp client, the
   fake SDK's `HttpURLConnection` shapes): `hopi mock add` → the app shows the mock and the dev
   server logs nothing; `hopi fail add` per kind → the app's error text names the platform
   exception; `hopi delay add` → the measured latency moves by the delay; `hopi edit add` → the dev
   server logs the edited header and path, the app shows the edited response; `hopi rules clear` → every
   request reaches the server again. The pinned client in the sample (a `CertificatePinner` with a
   wrong pin against the dev server's TLS port, debug network security config) fails before and
   after an edit rule, and a mock on it succeeds without any connection. The release build, with
   the same commands, answers "not reachable" (no bridge) and sends every request unchanged.
2. **iOS, iPhone 17 (iOS 27.0), then iPhone 16 (iOS 18.2)**, the sample's Debug app with
   `-verifyRules`: the same matrix for `URLSession.shared`, a delegate session, an async
   `data(for:)` and Ktor Darwin, including the "unsupported" warnings for response edits; the
   app's delegate with a pinning challenge handler keeps rejecting a wrong certificate after a
   request edit; a background session's request is untouched by a mock rule.
3. **Desktop**: the panel against both, "Mock this response" from a selected record.

Evidence (CLI `--json` output, dev server logs) goes under `build/evidence/rules/`, results under
`benchmark/rules/<date>/`.

## Performance plan

Reuses the existing harnesses: `benchmark/android-capture/run.py` (interleaved arms on the
emulator, `abtable.py`) and the URLSession `ab.m` method (one binary, interleaved blocks, host
load under 3).

- **No-rule A/B, the gate.** Arms: B = the build before rules, A = the build with rules and an
  empty rule set, A' = A with `rules = false`. Small and 1 MB requests per source (Ktor CIO on the
  JVM, OkHttp, `HttpURLConnection`, URLSession shared and delegate sessions), 3 alternating rounds.
  A must sit inside the B-to-B spread on wall time and on the calling thread's CPU on every source,
  or the change does not ship. On URLSession the per-task `canInitWithTask:` cost is also timed in
  isolation (target under 1 µs on the simulator).
- **With rules, non-matching.** 1, 10 and 64 rules that do not match (realistic patterns: host
  globs, path globs, a query predicate): the added CPU per request, per source. Targets, to be
  confirmed: at most 1 µs for 10 rules on the JVM microbenchmark (`-Phopi.rules`, the
  `StoreHotPathBenchmark` style), 3 µs on the emulator, 5 µs at 64 rules; URLSession pays the scan
  in the factory hook (when installed) and in `canInitWithTask:`, both stated.
  Measured 2026-10-25 (API 35 emulator, debuggable builds, 10 non-matching rules against
  `GET /json`, 4 rounds, calling-thread CPU, `build/evidence/speed/final/android/`): OkHttp −6 to
  +6 µs (median +2), inside the bench's CPU noise (−5 to +5), so the 3 µs target is below what it
  can resolve; `HttpURLConnection` +6 to +19 µs (median +7.5): **not met**. The request's method is
  now looked up once per request instead of once per rule, and the connection view caches its port,
  which moved nothing measurable; what is left is the per-rule glob and field reads through the
  `RuleRequest` interface on a debuggable build. URLSession (iPhone 17 simulator, process CPU, not
  the calling thread's): +7.0 to +18.6 µs (`build/evidence/speed/verify/ios/rules-ab-table.md`),
  **not met**. Ktor on Android goes through the OkHttp interceptor (the OkHttp figure applies); on
  the JVM 0.2 to 0.3 µs.
- **With rules, matching.** Mock latency from the app's call to its first body byte, against a
  loopback request (expected well below it, since nothing connects); the edit path's added CPU.
- **Idle.** 60 s with rules set and no traffic: no Hopi thread wakes (the 50 ms OkHttp wait exists
  only while a delayed request waits; tested by counting wakes with a delayed request and without).

## Implementation order

Features first, end to end, then one review and hardening pass at the end:

1. Core: rule model, matcher with the shared vectors, engine, record fields, JSON, tree parser.
2. `hopi-rules` and `-noop`, ABI dumps, parity, the Gradle plugin's third pair, release checks.
3. Ktor plugin: mock, fail, delay, edit.
4. OkHttp, then `HttpURLConnection`: mock, fail, delay, edit.
5. URLSession: protocol injection, mock, fail, delay; then the factory hooks and request edit.
6. Replay on every source (built, then removed: see "Replay (removed)").
7. Bridge commands, blobs, `hello.rules`; `hopi-connect`; the CLI.
8. In-app rules list and "Mock this response"; the desktop panel.
9. Device verification and the performance plan; `ARCHITECTURE.md` amended with the figures.
10. Review pass over the whole feature, then the "As built" section of this document.

## Risks

1. **Private URLSession pieces.** The private task factories may change; each is self-checked, and
   a failed check removes only the edits through that factory, with a notice line.
2. **Ktor `@InternalAPI`.** `HttpClientCall`'s constructor and finishing the send pipeline with a
   built call are Ktor internals; pinned by a test to the Ktor version Hopi builds against.
3. **Observable differences when a rule applies**, stated, not hidden: `task.originalRequest` is
   the edited request; `configuration.protocolClasses` lists Hopi's class; a delayed URLSession task
   reads `Suspended` during the delay and its timeouts start after it; an OkHttp mock has no
   handshake and bypasses the cache and network interceptors; a mocked 3xx, 401 or 407 is not
   followed by OkHttp, `HttpURLConnection` or URLSession themselves (a Ktor client above them still
   follows it); an `HttpURLConnection` mock has no certificates; a Ktor mock or edit on a client with `HttpCache` carries `Cache-Control:
   no-store`; a mocked URLSession upload reports no send progress.
4. **Two matchers** (Kotlin and C) can drift; the shared vectors fail the build when they do.
5. **The OkHttp delay wait** checks for cancel every 50 ms because OkHttp has no cancel callback; a
   cancel is noticed up to 50 ms late, only for requests a delay rule holds.
6. **Sessions created before `start()`** (an SDK's `+load`) cannot be mocked; documented, and the
   in-app rules list cannot detect it.
