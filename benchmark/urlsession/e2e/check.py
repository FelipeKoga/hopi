#!/usr/bin/env python3
"""Checks one end-to-end run (see run.sh) and prints one JSON line per check, PASS or FAIL.

For every run of the real build (`hopi-swift`, `hopi-ktor`, `hopi-sdk`) it matches the CLI's records
(`<label>-get.ndjson`) to the requests the dev server saw in that run (`<label>-server.ndjson`):
exactly one record per request, nothing re-sent, every record `source = "urlsession"`. Per
scenario of the app's own log it checks method, URL, status or error, headers and bodies (captured
bytes against the bytes the app read, or the dev server's body for a truncated one). It compares
the bytes each scenario read on the real and the nohopi build (`nohopi-*`), the `tail` stream against
`list`, and Hopi's os_log lines against the records.

usage: check.py <run dir>
"""
import base64, hashlib, json, os, sys, urllib.request
from collections import Counter
from urllib.parse import urlsplit

CAP = 262144
SERVER = "http://127.0.0.1:8765"
PATTERN_1MB = bytes(i % 251 for i in range(1024 * 1024))
# Scenarios whose request may never reach the server: no connection, no host, cancelled at once.
UNREACHED = {"sw-refused", "kt-unresolvable", "sw-cancel-before-bytes"}
# URL-only APIs cannot carry the tag header: matched by method and path.
UNTAGGED = {"sw-shared-url-completion": ("GET", "/items/2"), "sw-async-data-from": ("GET", "/items/3")}
EXPECTED_ERRORS = {"sw-refused": "-1004", "sw-timeout": "-1001", "sw-cancel-before-bytes": "cancelled",
                   "sw-cancel-mid-body": "cancelled", "kt-unresolvable": "-1003"}
REQUEST_BODIES = {"sw-delegate-upload": b'{"event":"app_open","n":1}', "sw-async-upload": b'{"batch":[1,2,3]}',
                  "kt-post-echo": b'{"name":"Ada","tags":["kmp","ktor"],"n":3}'}

results = []


def check(name, ok, detail=""):
    results.append({"check": name, "result": "PASS" if ok else "FAIL", "detail": detail})


def ndjson(path):
    if not os.path.exists(path):
        return []
    return [json.loads(line) for line in open(path) if line.strip().startswith("{")]


def app_lines(path, prefix):
    out = []
    for line in open(path, errors="replace"):
        i = line.find(prefix)
        if i >= 0 and '"verify"' in line:
            out.append(json.loads(line[i + len(prefix):].strip()))
    return {r["verify"]: r for r in out if r["verify"] != "done"}


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


def host_body(path):
    if path not in host_cache:
        host_cache[path] = urllib.request.urlopen(SERVER + path).read()
    return host_cache[path]


def key_of_record(r):
    return (r["method"], path_of(r["url"]), header(r["request"]["headers"], "X-Hopi-Verify"))


def key_of_server(s):
    return (s["method"], s["path"], header(s["headers"], "X-Hopi-Verify"))


def run_checks(run, label, prefix):
    records = ndjson(os.path.join(run, f"{label}-get.ndjson"))
    server = ndjson(os.path.join(run, f"{label}-server.ndjson"))
    listed = [r for r in ndjson(os.path.join(run, f"{label}-list.ndjson")) if r.get("type") == "transaction"]
    tail = [r for r in ndjson(os.path.join(run, f"{label}-tail.ndjson")) if r.get("type") == "transaction"]
    check(f"{label}: get returned every listed record", len(records) == len(listed) and len(records) > 0,
          f"{len(listed)} listed, {len(records)} details")

    sources = Counter(r.get("source") for r in records)
    check(f"{label}: every record source=urlsession, none ktor", set(sources) == {"urlsession"}, dict(sources).__repr__())

    # Exactly once: the records and the server's requests are the same multiset, except requests
    # that never reached the server (which still have one record each).
    rec_keys, srv_keys = Counter(map(key_of_record, records)), Counter(map(key_of_server, server))
    unreached_tags = {k for k in rec_keys if k[2] in UNREACHED}
    diff_rec = {str(k): v for k, v in (rec_keys - srv_keys).items() if k not in unreached_tags}
    # A redirect URLSession follows itself is one task, so one record (with finalUrl) for both hops.
    followed = Counter(("GET", path_of(r["finalUrl"]), header(r["request"]["headers"], "X-Hopi-Verify"))
                       for r in records if r.get("finalUrl"))
    diff_srv = {str(k): v for k, v in (srv_keys - rec_keys - followed).items()}
    check(f"{label}: one record per request the server saw, none missing, none extra",
          not diff_rec and not diff_srv and len(server) > 0,
          f"{len(records)} records, {len(server)} server requests; records not on server: {diff_rec}; server not recorded: {diff_srv}")

    tail_final = {}
    for t in tail:
        tail_final[t["id"]] = t
    check(f"{label}: tail streamed every record to its final state",
          set(tail_final) == {r["id"] for r in listed} and all(tail_final[r["id"]]["state"] == r["state"] for r in listed),
          f"{len(tail)} tail lines, {len(tail_final)} ids, {len(listed)} listed")
    if prefix is None:
        return records
    app = app_lines(os.path.join(run, f"{label}-app.log"), prefix)
    for name, line in app.items():
        if name in UNTAGGED:
            method, path = UNTAGGED[name]
            mine = [r for r in records if r["method"] == method and path_of(r["url"]) == path
                    and header(r["request"]["headers"], "X-Hopi-Verify") is None and header(r["request"]["headers"], "User-Agent") is None]
        else:
            mine = [r for r in records if header(r["request"]["headers"], "X-Hopi-Verify") == name]
        expected = 2 if name == "kt-redirect" else 1
        problems = []
        if len(mine) != expected:
            check(f"{label}/{name}", False, f"{len(mine)} records, expected {expected}")
            continue
        r = mine[-1]
        if name == "kt-redirect":
            hop = mine[0]
            if hop["code"] != 302 or header(hop["response"]["headers"], "Location") != "/items/1":
                problems.append(f"first hop {hop['code']} {hop['response']['headers']}")
        if r.get("source") != "urlsession":
            problems.append(f"source {r.get('source')}")
        want_error = EXPECTED_ERRORS.get(name)
        if want_error:
            if r["state"] != "failed" or want_error not in (r.get("error") or ""):
                problems.append(f"state {r['state']} error {r.get('error')!r}, want {want_error}")
            if name.startswith("sw-") and want_error != "cancelled" and want_error not in line.get("error", ""):
                problems.append(f"app error {line.get('error')}")
        else:
            if r["state"] != "complete" or r["code"] != line.get("status"):
                problems.append(f"state {r['state']} code {r['code']} app {line.get('status')}")
            res = r["response"]["body"]
            got = body_bytes(res)
            if res is None:
                problems.append("no response body")
            elif res.get("skippedReason"):
                if not (name == "sw-download" and res["skippedReason"] == "download" and res["totalBytes"] == line["bytes"]):
                    problems.append(f"skipped {res['skippedReason']} total {res['totalBytes']}")
            elif res["truncated"]:
                full = host_body(path_of(r.get("finalUrl") or r["url"]))
                if not (len(got) == CAP and got == full[:CAP] and res["totalBytes"] == line["bytes"] == len(full)
                        and hashlib.sha256(full).hexdigest() == line["sha256"]):
                    problems.append(f"truncated body: {len(got)} captured, total {res['totalBytes']}, app {line['bytes']}, host {len(full)}")
            elif hashlib.sha256(got).hexdigest() != line["sha256"] or res["totalBytes"] != line["bytes"]:
                problems.append(f"body hash differs: captured {len(got)} B, app {line['bytes']} B")
            clen = header(r["response"]["headers"], "Content-Length")
            if clen is not None and res and not res.get("skippedReason") and int(clen) != res["totalBytes"]:
                problems.append(f"Content-Length {clen} vs totalBytes {res['totalBytes']}")
            if header(r["response"]["headers"], "Content-Type") is None:
                problems.append("no response Content-Type")
        req = r["request"]["body"]
        if name in REQUEST_BODIES:
            if body_bytes(req) != REQUEST_BODIES[name] or req["truncated"]:
                problems.append(f"request body {body_bytes(req)[:60]!r}")
        if name in ("sw-async-upload-1mb", "kt-post-1mb"):
            if not (req and req["truncated"] and body_bytes(req) == PATTERN_1MB[:CAP] and req["totalBytes"] == len(PATTERN_1MB)):
                problems.append(f"1 MB request body: {req and (req['capturedBytes'], req['totalBytes'], req['truncated'])}")
        if name == "sw-body-stream" and not (req and req["skippedReason"] == "stream, not captured"):
            problems.append(f"stream body {req}")
        if name in ("sw-delegate-upload", "kt-post-echo") and header(r["request"]["headers"], "Authorization") != "<redacted>":
            problems.append(f"Authorization {header(r['request']['headers'], 'Authorization')!r}")
        if name == "sw-redirect" and not ((r.get("finalUrl") or "").endswith("/json") and path_of(r["url"]).startswith("/redirect")):
            problems.append(f"redirect url {r['url']} finalUrl {r.get('finalUrl')}")
        check(f"{label}/{name}", not problems,
              "; ".join(problems) or f"id {r['id']} {r['method']} {path_of(r['url'])} -> {r.get('code') or r.get('error')}, "
              f"{(r['response']['body'] or {}).get('capturedBytes', 0)}/{(r['response']['body'] or {}).get('totalBytes', 0)} B")
    return records


def main():
    run = sys.argv[1]
    swift = run_checks(run, "hopi-swift", "HopiVerify: ")
    ktor = run_checks(run, "hopi-ktor", "HopiSample: ")
    sdk = run_checks(run, "hopi-sdk", None)

    # The third-party SDK file: its five requests, plus the app's own Ktor GET /items.
    want = Counter({("GET", "/json"): 1, ("POST", "/echo"): 2, ("GET", "/items"): 2, ("GET", "/chunked"): 1})
    got = Counter((r["method"], path_of(r["url"])) for r in sdk)
    bodies = sorted(body_bytes(r["request"]["body"]) for r in sdk if r["method"] == "POST")
    check("hopi-sdk: completion handler, delegate upload, data(for:), upload(for:from:), bytes(for:) each recorded once",
          got == want and bodies == sorted([b'{"event":"app_open","sdk":"third-party"}', b'{"batch":[1,2,3]}'])
          and sum(1 for r in sdk if header(r["request"]["headers"], "User-Agent") == "ktor-client") == 1,
          f"{dict(got)}")

    # Bytes the app read: identical on the real and the nohopi build.
    for engine, prefix in (("swift", "HopiVerify: "), ("ktor", "HopiSample: ")):
        real = app_lines(os.path.join(run, f"hopi-{engine}-app.log"), prefix)
        nohopi = app_lines(os.path.join(run, f"nohopi-{engine}-app.log"), prefix)
        fields = ("status", "bytes", "sha256", "error")
        differ = [n for n in real if n not in nohopi or any(real[n].get(f) != nohopi[n].get(f) for f in fields)]
        check(f"{engine}: bytes read identical on real and nohopi builds (SHA-256 per scenario)",
              not differ and set(real) == set(nohopi) and len(real) > 0,
              f"{len(real)} scenarios, differing: {differ}")
        # Nothing re-sent: the server saw the same requests from both builds.
        r_srv = Counter(map(key_of_server, ndjson(os.path.join(run, f"hopi-{engine}-server.ndjson"))))
        n_srv = Counter(map(key_of_server, ndjson(os.path.join(run, f"nohopi-{engine}-server.ndjson"))))
        check(f"{engine}: the server saw the same requests from the real and the nohopi build",
              r_srv == n_srv, f"real-only {dict(r_srv - n_srv)}, nohopi-only {dict(n_srv - r_srv)}")

    # The NDJSON log line: one terminal summary per record, carrying the source.
    for label, records in (("hopi-swift", swift), ("hopi-ktor", ktor)):
        lines = []
        for event in ndjson(os.path.join(run, f"{label}-oslog.ndjson")):
            message = event.get("eventMessage", "")
            if message.startswith("{"):
                try:
                    lines.append(json.loads(message))
                except ValueError:
                    pass
        terminal = [l for l in lines if l.get("state") in ("complete", "failed")]
        check(f"{label}: NDJSON log line per record with source",
              len(terminal) == len(records) and all(l.get("source") == "urlsession" for l in terminal),
              f"{len(lines)} log lines, {len(terminal)} terminal, {len(records)} records; sample {lines[-1] if lines else None}")

    for r in results:
        print(json.dumps(r))
    failed = [r for r in results if r["result"] == "FAIL"]
    print(json.dumps({"checks": len(results), "failed": len(failed)}))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
