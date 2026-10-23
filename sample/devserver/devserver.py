#!/usr/bin/env python3
"""Host-side dev server for the Hopi sample app. Python 3 standard library only.

Binds 127.0.0.1 only. The emulator reaches it through `adb reverse tcp:8765 tcp:8765`, so the app
talks to http://127.0.0.1:8765 and nothing leaves the machine.

Every request is logged to stdout as one JSON line (method, path, headers in order, body as text),
so a request replayed with `hopi curl` can be compared with the one the app sent.

Routes:
  GET  /json     a small JSON object
  POST /echo     echoes the request (method, path, headers, body) as JSON, credential values masked
  GET  /missing  404 (any unknown path is a 404)
  GET  /large    a JSON response of LARGE_BYTES bytes, larger than the default 256 KB body cap
  GET  /chunked  a JSON array sent with Transfer-Encoding: chunked, in CHUNKS chunks

Usage: python3 sample/devserver/devserver.py [--port 8765]
"""

import argparse
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LARGE_BYTES = 300 * 1024
CHUNKS = 8
SECRET_HEADERS = {"authorization", "proxy-authorization", "cookie"}


def log(record):
    sys.stdout.write(json.dumps(record, separators=(",", ":")) + "\n")
    sys.stdout.flush()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "HopiDevServer/1"

    def _read_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length > 0 else b""

    def _record(self, body):
        record = {
            "method": self.command,
            "path": self.path,
            "headers": [[name, value] for name, value in self.headers.items()],
            "body": body.decode("utf-8", errors="replace"),
        }
        log(record)
        return record

    def _send(self, code, payload, content_type="application/json"):
        data = payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self._record(b"")
        if self.path == "/json":
            self._send(200, {"id": 1, "name": "Ada Lovelace", "languages": ["kotlin", "swift"]})
        elif self.path == "/large":
            item = '{"i":%d,"pad":"' + "x" * 80 + '"}'
            parts, size, i = [], 2, 0
            while size < LARGE_BYTES:
                part = item % i
                parts.append(part)
                size += len(part) + 1
                i += 1
            self._send(200, ("[" + ",".join(parts) + "]").encode("utf-8"))
        elif self.path == "/chunked":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            pieces = ["["] + [('%s{"chunk":%d}' % ("," if i else "", i)) for i in range(CHUNKS)] + ["]"]
            for piece in pieces:
                data = piece.encode("utf-8")
                self.wfile.write(b"%x\r\n%s\r\n" % (len(data), data))
                self.wfile.flush()
            self.wfile.write(b"0\r\n\r\n")
        else:
            self._send(404, {"error": "not found", "path": self.path})

    def do_POST(self):
        record = self._record(self._read_body())
        if self.path == "/echo":
            # Like a real API, never reflect a credential back: Hopi redacts headers, not
            # bodies, so an echoed secret would reach every channel through the response body.
            echoed = dict(record, headers=[
                [name, "<received, %d chars>" % len(value) if name.lower() in SECRET_HEADERS else value]
                for name, value in record["headers"]
            ])
            self._send(200, echoed)
        else:
            self._send(404, {"error": "not found", "path": self.path})

    def log_message(self, format, *args):
        # The JSON line per request is the log; the default access line goes to stderr.
        sys.stderr.write("%s\n" % (format % args))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    sys.stderr.write("devserver listening on http://127.0.0.1:%d\n" % args.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
