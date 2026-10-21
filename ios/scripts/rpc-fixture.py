#!/usr/bin/env python3
"""Nonprivate loopback HTTP fixture for the actual Darwin RPC integration binary."""

import argparse
import json
from pathlib import Path
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


RESPONSE_LIMIT = 64 * 1024 * 1024


class Fixture:
    def __init__(self):
        self.lock = threading.Lock()
        self.records = {}
        self.target_hits = 0

    def mark(self, case, field):
        self.record(case, field, True)

    def record(self, case, field, value):
        with self.lock:
            self.records.setdefault(case, {})[field] = value

    def state(self, case):
        with self.lock:
            return {**self.records.get(case, {}), "targetHits": self.target_hits}


def handler(fixture, target_url, is_target=False):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_):
            pass  # Never print configured fixture headers or request bodies.

        def answer(self, status, value, content_type="application/json"):
            body = value.encode() if isinstance(value, str) else json.dumps(value, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def hold(self, case, content_length, prefix=b""):
            fixture.mark(case, "arrived")
            fixture.record(case, "bodyBytesSent", 0)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(content_length))
            self.end_headers()
            # URLSession may defer response delivery until body progress. One byte makes
            # the advertised-length guard observable without downloading a large body.
            try:
                if prefix:
                    self.wfile.write(prefix)
                    fixture.record(case, "bodyBytesSent", len(prefix))
                self.wfile.flush()
            except (ConnectionResetError, BrokenPipeError):
                fixture.mark(case, "disconnected")
                return
            self.connection.settimeout(0.1)
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                try:
                    if not self.connection.recv(1):
                        fixture.mark(case, "disconnected")
                        return
                except socket.timeout:
                    continue
                except (ConnectionResetError, BrokenPipeError):
                    fixture.mark(case, "disconnected")
                    return
            self.close_connection = True

        def do_POST(self):
            body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            if is_target:
                with fixture.lock:
                    fixture.target_hits += 1
                self.answer(200, {"result": "redirected"})
            elif self.path == "/redirect/api/rpc":
                self.send_response(307)
                self.send_header("Location", target_url + "/redirect-target")
                self.send_header("Content-Length", "0")
                self.end_headers()
            elif self.path == "/status/api/rpc":
                self.answer(403, {"error": "fixture forbidden"})
            elif self.path == "/access/api/rpc":
                self.answer(200, "<html>fixture Access login</html>", "text/html")
            elif self.path == "/oversize/api/rpc":
                self.hold("oversize", RESPONSE_LIMIT + 1, prefix=b"{")
            elif self.path.startswith("/hold/") and self.path.endswith("/api/rpc"):
                self.hold(self.path.split("/")[2], 2)
            elif self.path == "/api/rpc":
                envelope = json.loads(body)
                if envelope.get("name") == "fixture-state":
                    self.answer(200, {"result": fixture.state(envelope["args"]["id"])})
                elif envelope.get("name") == "fixture-echo":
                    self.answer(200, {"result": {
                        "method": self.command, "path": self.path,
                        "contentType": self.headers.get("Content-Type", ""),
                        "session": self.headers.get("X-Pully-Session", ""),
                        "access": self.headers.get("X-Fixture-Access", ""),
                        "envelope": envelope,
                    }})
                else:
                    self.answer(400, {"error": "Unknown fixture RPC"})
            else:
                self.answer(404, {"error": "Unknown fixture path"})

        def do_GET(self):
            if is_target:
                with fixture.lock:
                    fixture.target_hits += 1
                self.answer(200, {"result": "redirected"})
            else:
                self.answer(405, {"error": "POST required"})

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", type=Path, required=True)
    args = parser.parse_args()
    args.state_dir.mkdir(parents=True, exist_ok=True)
    fixture = Fixture()
    target = ThreadingHTTPServer(("127.0.0.1", 0), handler(fixture, "", is_target=True))
    target_url = "http://127.0.0.1:" + str(target.server_port)
    primary = ThreadingHTTPServer(("127.0.0.1", 0), handler(fixture, target_url))
    threading.Thread(target=target.serve_forever, daemon=True).start()
    (args.state_dir / "url").write_text("http://127.0.0.1:" + str(primary.server_port))
    print("Darwin RPC loopback fixture ready", flush=True)
    try:
        primary.serve_forever()
    finally:
        primary.server_close()
        target.shutdown()
        target.server_close()


if __name__ == "__main__":
    main()
