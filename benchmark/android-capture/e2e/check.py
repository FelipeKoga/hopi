#!/usr/bin/env python3
"""Checks one Android end-to-end run (see run.sh) and prints one JSON line per check, PASS or FAIL;
the Android counterpart of benchmark/urlsession/e2e/check.py.

For the capture build it matches the CLI's records (`capture-get.ndjson`) to the requests the dev
server saw in that run (`capture-server.ndjson`): exactly one record per request (a redirect the
client follows itself is one record carrying `finalUrl`), nothing re-sent, the source of each
record the stack that made the request (`ktor` never: the Ktor plugin defers to OkHttp capture).
Per scenario of the app's `{"verify":…}` lines it checks status or error, headers and bodies
(captured bytes against the bytes the app read, or the dev server's body for a truncated one). It
compares the bytes each scenario read on the capture and the nohopi build, the server's requests from
both, the `tail` stream against `list`, and Hopi's summary log lines against the records.

usage: check.py <run dir>
"""
import base64, gzip, hashlib, json, os, sys, urllib.request
from collections import Counter
from urllib.parse import urlsplit

CAP = 262144
SERVER = "http://127.0.0.1:8765"
PATTERN_1MB = bytes(i % 251 for i in range(1024 * 1024))
SOURCE = {"kt": "okhttp", "ok": "okhttp", "uc": "urlconnection"}
# Requests that never reach the dev server: no connection, no host, or another server (HTTPS).
UNREACHED = {f"{p}-{s}" for p in SOURCE for s in ("refused", "unresolvable", "https")}
FAILED = {f"{p}-{s}" for p in SOURCE for s in ("refused", "unresolvable", "drop")}
REQUEST_BODIES = {"kt-post-echo": b'{"name":"Ada","tags":["kmp","ktor"],"n":3}',
                  "ok-post-echo": b'{"event":"verify","n":1}', "uc-post-echo": b'{"event":"verify","n":2}'}
# Ktor's OkHttp engine turns OkHttp's redirects off and follows them itself: two calls, two records.
RECORDS = {"kt-redirect": 2}

results = []


def check(name, ok, detail=""):
    results.append({"check": name, "result": "PASS" if ok else "FAIL", "detail": detail})


def ndjson(path):
    if not os.path.exists(path):
        return []
    return [json.loads(line) for line in open(path) if line.strip().startswith("{")]


def logcat_json(path, tag):
    """The JSON payloads of one tag's lines in a `logcat -v tag` file."""
    out, prefix = [], f"I/{tag}"
    for line in open(path, errors="replace"):
        if line.startswith(prefix) and ": {" in line:
            try:
                out.append(json.loads(line.split(": ", 1)[1]))
            except ValueError:
                pass
    return out


def app_lines(path):
    return {r["verify"]: r for r in logcat_json(path, "HopiSample") if "verify" in r and r["verify"] != "done"}


def body_bytes(body):
    if not body:
        return b""
    if body.get("encoding") == "base64":
        return base64.b64decode(body.get("data", ""))
    return body.get("text", "").encode("utf-8")


def header(pairs, name):
    for k, v in pairs or []:
        if k.lower() == name.lower():
            return v
    return None


def path_of(url):
    parts = urlsplit(url)
    return parts.path + ("?" + parts.query if parts.query else "")


host_cache = {}


def host_body(path, decoded):
    """The dev server's body for `path` (deterministic routes only), gunzipped when the client did."""
    if path not in host_cache:
        with urllib.request.urlopen(SERVER + path) as response:
            host_cache[path] = (response.read(), response.headers.get("Content-Encoding"))
    data, encoding = host_cache[path]
    return gzip.decompress(data) if decoded and encoding == "gzip" else data


def key_of_record(r):
    return (r["method"], path_of(r["url"]), header(r["request"]["headers"], "X-Hopi-Verify"))


def key_of_server(s):
    return (s["method"], s["path"], header(s["headers"], "X-Hopi-Verify"))


def main():
    run = sys.argv[1]
    records = ndjson(os.path.join(run, "capture-get.ndjson"))
    server = ndjson(os.path.join(run, "capture-server.ndjson"))
    listed = [r for r in ndjson(os.path.join(run, "capture-list.ndjson")) if r.get("type", "transaction") == "transaction"]
    tail = [r for r in ndjson(os.path.join(run, "capture-tail.ndjson")) if r.get("type", "transaction") == "transaction"]
    app = app_lines(os.path.join(run, "capture-app.log"))
    done = [r for r in logcat_json(os.path.join(run, "capture-app.log"), "HopiSample") if r.get("verify") == "done"]
    check("capture: verify ran to its end with capture started and OkHttp instrumented",
          len(done) == 1 and done[0].get("started") is True and done[0].get("okHttpInstrumented") is True, f"{done}")
    check("capture: get returned every listed record", len(records) == len(listed) and len(records) > 0,
          f"{len(listed)} listed, {len(records)} details")

    sources = Counter(r.get("source") for r in records)
    check("capture: every record okhttp or urlconnection, none ktor",
          set(sources) <= {"okhttp", "urlconnection"} and len(sources) > 0, f"{dict(sources)}")

    # Exactly once: the records and the server's requests are the same multiset, except requests
    # that never reached the server (one record each still) and hops of a redirect the client
    # followed itself (one record, with finalUrl).
    rec_keys, srv_keys = Counter(map(key_of_record, records)), Counter(map(key_of_server, server))
    diff_rec = {str(k): v for k, v in (rec_keys - srv_keys).items() if k[2] not in UNREACHED}
    followed = Counter(("GET", path_of(r["finalUrl"]), header(r["request"]["headers"], "X-Hopi-Verify"))
                       for r in records if r.get("finalUrl"))
    diff_srv = {str(k): v for k, v in (srv_keys - rec_keys - followed).items()}
    check("capture: one record per request the server saw, none missing, none extra",
          not diff_rec and not diff_srv and len(server) > 0,
          f"{len(records)} records, {len(server)} server requests; records not on server: {diff_rec}; server not recorded: {diff_srv}")

    tail_final = {}
    for t in tail:
        tail_final[t["id"]] = t
    check("capture: tail streamed every record to its final state",
          set(tail_final) == {r["id"] for r in listed} and all(tail_final[r["id"]]["state"] == r["state"] for r in listed),
          f"{len(tail)} tail lines, {len(tail_final)} ids, {len(listed)} listed")

    for name, line in sorted(app.items()):
        mine = [r for r in records if header(r["request"]["headers"], "X-Hopi-Verify") == name]
        expected = RECORDS.get(name, 1)
        if len(mine) != expected:
            check(f"capture/{name}", False, f"{len(mine)} records, expected {expected}")
            continue
        problems = []
        r = mine[-1]
        if name == "kt-redirect":
            hop = mine[0]
            if hop["code"] != 302 or header(hop["response"]["headers"], "Location") != "/items/1":
                problems.append(f"first hop {hop['code']} {hop['response']['headers']}")
        if name in ("ok-redirect", "uc-redirect") and not (path_of(r["url"]).startswith("/redirect") and (r.get("finalUrl") or "").endswith("/json")):
            problems.append(f"redirect url {r['url']} finalUrl {r.get('finalUrl')}")
        want_source = SOURCE[name.split("-", 1)[0]]
        if any(m.get("source") != want_source for m in mine):
            problems.append(f"source {[m.get('source') for m in mine]}, want {want_source}")
        if name in FAILED:
            if r["state"] != "failed" or not r.get("error"):
                problems.append(f"state {r['state']} error {r.get('error')!r}")
            if "error" not in line:
                problems.append(f"the app did not fail: {line}")
        elif "error" in line:
            problems.append(f"the app failed: {line['error']}")
        else:
            if r["state"] != "complete" or r["code"] != line.get("status"):
                problems.append(f"state {r['state']} code {r['code']} app {line.get('status')}")
            res = r["response"]["body"]
            got = body_bytes(res)
            decoded = header(r["response"]["headers"], "Content-Encoding") is None
            if res is None:
                if line["bytes"]:
                    problems.append("no response body")
            elif res.get("skippedReason"):
                problems.append(f"skipped {res['skippedReason']} total {res['totalBytes']}")
            elif res["truncated"]:
                if name.endswith("https"):
                    ok = len(got) == CAP and res["totalBytes"] == line["bytes"]
                else:
                    full = host_body(path_of(r.get("finalUrl") or r["url"]), decoded)
                    ok = (len(got) == CAP and got == full[:CAP] and res["totalBytes"] == line["bytes"] == len(full)
                          and hashlib.sha256(full).hexdigest() == line["sha256"])
                if not ok:
                    problems.append(f"truncated body: {len(got)} captured, total {res['totalBytes']}, app {line['bytes']}")
            elif hashlib.sha256(got).hexdigest() != line["sha256"] or res["totalBytes"] != line["bytes"]:
                problems.append(f"body hash differs: captured {len(got)} B (total {res['totalBytes']}), app {line['bytes']} B")
            clen = header(r["response"]["headers"], "Content-Length")
            if clen is not None and res and not res.get("skippedReason") and int(clen) != res["totalBytes"]:
                problems.append(f"Content-Length {clen} vs totalBytes {res['totalBytes']}")
            if name.endswith("gzip") and not decoded:
                problems.append("the client decoded the body but the record keeps Content-Encoding")
            if name.endswith("gzip-raw") and (decoded or got[:2] != b"\x1f\x8b"):
                problems.append(f"raw gzip: Content-Encoding {header(r['response']['headers'], 'Content-Encoding')}, first bytes {got[:2]!r}")
            # OkHttp's Protocol names (h2, http/1.1) as Hopi writes them (HTTP/2.0, HTTP/1.1).
            seen = {"h2": "HTTP/2.0", "http/1.1": "HTTP/1.1"}.get(line.get("detail"), line.get("detail"))
            if name == "ok-https" and r.get("protocol") != seen:
                problems.append(f"protocol recorded {r.get('protocol')}, the app saw {line.get('detail')}")
        req = r["request"]["body"]
        if name in REQUEST_BODIES:
            if body_bytes(req) != REQUEST_BODIES[name] or req["truncated"]:
                problems.append(f"request body {body_bytes(req)[:60]!r}")
            if header(r["request"]["headers"], "Authorization") != "<redacted>":
                problems.append(f"Authorization {header(r['request']['headers'], 'Authorization')!r}")
        if name.endswith("post-1mb"):
            if not (req and req["truncated"] and body_bytes(req) == PATTERN_1MB[:CAP] and req["totalBytes"] == len(PATTERN_1MB)):
                problems.append(f"1 MB request body: {req and (req['capturedBytes'], req['totalBytes'], req['truncated'])}")
        if name.startswith("ok-") and header(r["request"]["headers"], "X-Fake-Sdk") != "1":
            problems.append("the SDK interceptor's X-Fake-Sdk header is not recorded")
        res_body = r["response"]["body"] or {}
        check(f"capture/{name}", not problems,
              "; ".join(problems) or f"id {r['id']} {r['method']} {path_of(r['url'])} -> {r.get('code') or r.get('error')}, "
              f"{res_body.get('capturedBytes', 0)}/{res_body.get('totalBytes', 0)} B, {r.get('protocol')}")
    recorded_tags = {header(r["request"]["headers"], "X-Hopi-Verify") for r in records}
    check("capture: every record belongs to a scenario the app ran", recorded_tags <= set(app), f"{sorted(recorded_tags - set(app), key=str)}")

    # Bytes the app read: identical on the capture and the nohopi build.
    nohopi = app_lines(os.path.join(run, "nohopi-app.log"))
    fields = ("status", "bytes", "sha256", "error")
    differ = [n for n in app if n not in nohopi or any(app[n].get(f) != nohopi[n].get(f) for f in fields)]
    check("bytes read identical on capture and nohopi builds (SHA-256 per scenario)",
          not differ and set(app) == set(nohopi) and len(app) > 0, f"{len(app)} scenarios, differing: {differ}")
    # Nothing re-sent and nothing changed on the wire: the server saw the same requests, with the
    # same headers and bodies, from both builds.
    def wire(path):
        return Counter((s["method"], s["path"], tuple((k.lower(), v) for k, v in s["headers"]), s["body"]) for s in ndjson(path))
    c_srv, n_srv = wire(os.path.join(run, "capture-server.ndjson")), wire(os.path.join(run, "nohopi-server.ndjson"))
    check("the server saw the same requests (method, path, headers, body) from the capture and the nohopi build",
          c_srv == n_srv and len(c_srv) > 0,
          f"{sum(c_srv.values())} requests; capture-only {[k[:2] for k in c_srv - n_srv]}, nohopi-only {[k[:2] for k in n_srv - c_srv]}")
    nohopi_records = [l for l in logcat_json(os.path.join(run, "nohopi-app.log"), "Hopi") if "id" in l]
    nohopi_done = [r for r in logcat_json(os.path.join(run, "nohopi-app.log"), "HopiSample") if r.get("verify") == "done"]
    check("nohopi: no Hopi record, capture not started",
          not nohopi_records and len(nohopi_done) == 1 and nohopi_done[0].get("started") is False, f"{len(nohopi_records)} records, {nohopi_done}")

    # The summary log line: one terminal line per record, carrying the source.
    summary = [l for l in logcat_json(os.path.join(run, "capture-app.log"), "Hopi") if "id" in l]
    terminal = {l["id"]: l for l in summary if l.get("state") in ("complete", "failed")}
    by_id = {r["id"]: r for r in records}
    check("capture: summary log line per record with its source",
          set(terminal) == set(by_id) and all(terminal[i].get("source") == by_id[i].get("source") for i in by_id),
          f"{len(summary)} lines, {len(terminal)} terminal, {len(records)} records")

    for r in results:
        print(json.dumps(r))
    failed = [r for r in results if r["result"] == "FAIL"]
    print(json.dumps({"checks": len(results), "failed": len(failed)}))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
