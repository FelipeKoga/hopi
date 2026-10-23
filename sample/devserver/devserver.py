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
  GET  /slow?s=N a chunked JSON array that takes N seconds (1..300, default 60), one chunk every
                 SLOW_TICK_S seconds, so the request stays pending that long and no read timeout
                 fires
  GET  /items    the item list of the sample's "Items" screen (id, title, price)
  GET  /items/N  one item with its description, 404 for an unknown id
  POST /items    creates an item from a JSON body {"title", "price", "description"}: 201 with the
                 item, 400 when the body is not such an object; items live in memory until exit

Usage: python3 sample/devserver/devserver.py [--port 8765]
"""

import argparse
import json
import math
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LARGE_BYTES = 300 * 1024
CHUNKS = 8
SLOW_TICK_S = 5
SLOW_PATH = re.compile(r"^/slow(?:\?s=([0-9]{1,3}))?$")
SECRET_HEADERS = {"authorization", "proxy-authorization", "cookie"}
ITEM_PATH = re.compile(r"^/items/([0-9]{1,9})$")

ITEMS_LOCK = threading.Lock()
ITEMS = [
    {"id": 1, "title": "Mechanical keyboard", "price": 129.0,
     "description": "Tenkeyless, hot-swappable switches, USB-C."},
    {"id": 2, "title": "Noise-cancelling headphones", "price": 249.0,
     "description": "Over-ear, 30 hours of battery, multipoint Bluetooth."},
    {"id": 3, "title": "4K monitor", "price": 399.0,
     "description": "27 inches, IPS, 60 Hz, USB-C with 65 W power delivery."},
    {"id": 4, "title": "Laptop stand", "price": 39.0,
     "description": "Aluminium, adjustable height, folds flat."},
    {"id": 5, "title": "Webcam", "price": 89.0,
     "description": "1080p at 60 fps, privacy shutter."},
]


def summary(item):
    return {"id": item["id"], "title": item["title"], "price": item["price"]}


def parse_item(body):
    """The item a POST /items body describes, or None when it is not a valid one."""
    try:
        data = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        return None
    if not isinstance(data, dict):
        return None
    title, price, description = data.get("title"), data.get("price"), data.get("description", "")
    if not isinstance(title, str) or not title.strip() or len(title) > 200:
        return None
    if isinstance(price, bool) or not isinstance(price, (int, float)) or not math.isfinite(price) or price < 0:
        return None
    if not isinstance(description, str) or len(description) > 2000:
        return None
    return {"title": title.strip(), "price": float(price), "description": description}


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

    def _send_chunks(self, pieces, pause_s=0):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        for index, piece in enumerate(pieces):
            if pause_s and index:
                time.sleep(pause_s)
            data = piece.encode("utf-8")
            self.wfile.write(b"%x\r\n%s\r\n" % (len(data), data))
            self.wfile.flush()
        self.wfile.write(b"0\r\n\r\n")

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
        elif self.path == "/items":
            with ITEMS_LOCK:
                items = [summary(item) for item in ITEMS]
            self._send(200, items)
        elif ITEM_PATH.match(self.path):
            wanted = int(ITEM_PATH.match(self.path).group(1))
            with ITEMS_LOCK:
                found = next((dict(item) for item in ITEMS if item["id"] == wanted), None)
            if found is None:
                self._send(404, {"error": "no such item", "id": wanted})
            else:
                self._send(200, found)
        elif self.path == "/chunked":
            self._send_chunks(["["] + [('%s{"chunk":%d}' % ("," if i else "", i)) for i in range(CHUNKS)] + ["]"])
        elif SLOW_PATH.match(self.path):
            seconds = min(max(int(SLOW_PATH.match(self.path).group(1) or 60), 1), 300)
            ticks = max(seconds // SLOW_TICK_S, 1)
            pieces = ["["] + [('%s{"tick":%d}' % ("," if i else "", i)) for i in range(ticks)] + ["]"]
            self._send_chunks(pieces, pause_s=seconds / (len(pieces) - 1))
        else:
            self._send(404, {"error": "not found", "path": self.path})

    def do_POST(self):
        body = self._read_body()
        record = self._record(body)
        if self.path == "/echo":
            # Like a real API, never reflect a credential back: Hopi redacts headers, not
            # bodies, so an echoed secret would reach every channel through the response body.
            echoed = dict(record, headers=[
                [name, "<received, %d chars>" % len(value) if name.lower() in SECRET_HEADERS else value]
                for name, value in record["headers"]
            ])
            self._send(200, echoed)
        elif self.path == "/items":
            item = parse_item(body)
            if item is None:
                self._send(400, {"error": "expected {\"title\": string, \"price\": number >= 0, \"description\": string}"})
                return
            with ITEMS_LOCK:
                item = dict(item, id=max((i["id"] for i in ITEMS), default=0) + 1)
                ITEMS.append(item)
            self._send(201, item)
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
