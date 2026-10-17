#!/usr/bin/env python3
"""Offline HTTP analytics smoke against an already built Rust backend.

Only loopback HTTP is used. The process receives an allowlisted environment and
isolated HOME/cache/analytics directories; no repository or account data is read.
Usage: python3 backend/scripts/analytics_smoke.py [path/to/pulls-review-server]
"""

from contextlib import contextmanager
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request


BINARY = Path(
    sys.argv[1] if len(sys.argv) > 1
    else "backend/target/debug/pulls-review-server"
).resolve()
BODY_LIMIT = 2 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise AssertionError(message)


@contextmanager
def server(root, enabled, seed_history=False):
    """Start one credential-free fixture server; always terminate it within bounds."""
    root.mkdir()
    home = root / "home"
    repo = root / "repo"
    home.mkdir()
    repo.mkdir()
    analytics = root / "analytics"
    if seed_history:
        analytics.mkdir()
        stamp = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
        (analytics / "2000-01-01.ndjson").write_text("{}\n")
        (analytics / (stamp[:10] + ".ndjson")).write_text(json.dumps({
            "name": "screen.view", "app": "web", "at": stamp, "rx": stamp,
            "props": {"screen": "retained-fixture"},
        }) + "\n")
        (analytics / "keep.txt").write_text("unrelated fixture\n")
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    # An allowlist, rather than a denylist, also removes unanticipated provider
    # credentials. Temporary HOME prevents discovery through provider config files.
    env = {key: os.environ[key] for key in ("PATH", "LANG", "LC_ALL", "SYSTEMROOT")
           if key in os.environ}
    env.update({
        "HOME": str(home),
        "USERPROFILE": str(home),
        "XDG_CONFIG_HOME": str(root / "config"),
        "XDG_DATA_HOME": str(root / "data"),
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_CONFIG_GLOBAL": os.devnull,
        "PULLS_REVIEW_ANALYTICS": "on" if enabled else "off",
        "PULLS_REVIEW_ANALYTICS_DIR": str(analytics),
        # Defense in depth if a regression unexpectedly tries HTTP outside this
        # fixture: route it to an unserved loopback port, without inherited proxy.
        "HTTP_PROXY": "http://127.0.0.1:9",
        "HTTPS_PROXY": "http://127.0.0.1:9",
        "ALL_PROXY": "http://127.0.0.1:9",
        "NO_PROXY": "127.0.0.1,localhost",
    })
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(path, body=None, raw=None):
        require(path in ("/health", "/api/events", "/api/rpc"),
                "The fixture may call only its local analytics endpoints")
        data = raw if raw is not None else (
            json.dumps(body).encode() if body is not None else None
        )
        req = urllib.request.Request(
            f"http://127.0.0.1:{port}{path}", data=data,
            headers={"Content-Type": "application/json"},
        )
        try:
            with opener.open(req, timeout=3) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()

    with (root / "server.log").open("w+") as log:
        process = subprocess.Popen(
            [str(BINARY), "--host", "127.0.0.1", "--port", str(port),
             "--cwd", str(repo), "--data-dir", str(root / "cache"),
             "--no-warm", "--no-open", "--no-auth"],
            cwd=repo, env=env, stdin=subprocess.DEVNULL,
            stdout=log, stderr=log,
        )
        try:
            deadline = time.monotonic() + 6
            while time.monotonic() < deadline:
                require(process.poll() is None, "Fixture server exited before becoming healthy")
                try:
                    if request("/health")[0] == 200:
                        break
                except OSError:
                    time.sleep(0.05)
            else:
                raise AssertionError("Fixture server did not become healthy within six seconds")
            yield request, analytics
        finally:
            process.terminate()
            try:
                process.wait(timeout=4)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=2)


def summary(request):
    status, payload = request("/api/rpc", {
        "name": "analytics-summary", "args": {"days": 2},
    })
    require(status == 200, f"analytics-summary returned HTTP {status}")
    result = json.loads(payload)["result"]
    require(len(result["days"]) == 2, "Summary must include both UTC days, including empty days")
    return result


def stored_events(analytics):
    files = sorted(analytics.glob("*.ndjson"))
    events = []
    for path in files:
        for line in path.read_text().splitlines():
            event = json.loads(line)
            require(path.name == event["rx"][:10] + ".ndjson",
                    "Receive-time UTC day must determine the storage filename")
            events.append(event)
    return events


def enabled_smoke(root):
    with server(root, enabled=True) as (request, analytics):
        require(not analytics.exists(), "Starting an idle server must not create analytics storage")
        require(request("/api/events", raw=b"not json") == (204, b""),
                "Malformed analytics should be ignored with an empty 204")
        require(request("/api/events", {"events": [{"name": "invalid", "app": "web"}]})
                == (204, b""), "Invalid events should be ignored")
        require(not analytics.exists(), "Invalid events must not create analytics storage")

        token = "ghp_" + "syntheticfixture" * 3
        private_message = (
            "open /custom/private.rs for fixture.person@example.invalid; "
            "authorization: Bearer opaque-test-credential; \"private text fixture\""
        )
        fixtures = [
            ("screen.view", {"screen": "home"}),
            ("screen.time", {"screen": "home", "seconds": 42}),
            ("pr.open", {"number": 7, "repo": "fixture/repository"}),
            ("pr.open", {"kind": "branch"}),
            ("file.reviewed", {"files": 3}),
            ("ai.run", {"task": "review", "engine": "codex", "model": "fixture-model",
                        "status": "done", "costUsd": 0.125, "tokensIn": 123,
                        "tokensOut": 10, "ms": 2000}),
            ("ai.run", {"task": "review", "engine": "codex", "model": "fixture-model",
                        "status": "failed", "ms": 1000}),
            ("pr.load", {"ms": 275}),
            ("github.calls", {"route": "pulls/:n", "calls": 3, "failures": 1,
                              "ms": 50, "msMax": 100, "rateLeft": 1000}),
            ("cache.diff", {"outcome": "hit"}),
            ("error.client", {"kind": "FixtureError", "message": private_message,
                              "prompt": "private prompt fixture", "path": "src/private.rs",
                              "content": "private content fixture", "accessToken": token}),
            ("crash", {"kind": "FixtureCrash", "message": "fixture failure"}),
        ]
        events = [{"name": name, "app": "web", "session": "fixture-session",
                   "device": "fixture-device", "props": props,
                   "at": "1990-01-01T00:00:00Z", "rx": "1990-01-01T00:00:00Z"}
                  for name, props in fixtures]
        events[0]["version"] = token
        events[-2]["session"] = token
        events[-2]["device"] = token
        status, payload = request("/api/events", {"events": events})
        require((status, payload) == (204, b""), "Valid ingestion must return empty HTTP 204")
        result = summary(request)  # Flushes queued telemetry before reading day files.
        totals = result["totals"]
        expected = {"events": len(events), "sessions": 1, "devices": 1,
                    "prsOpened": 1, "filesReviewed": 3, "aiRuns": 2,
                    "aiFailures": 1, "costUsd": 0.125, "tokensIn": 123,
                    "tokensOut": 10, "errors": 1, "crashes": 1}
        for key, value in expected.items():
            require(totals[key] == value, f"Incorrect summary total: {key}")
        require(result["screens"] == [{"screen": "home", "views": 1, "seconds": 42}],
                "Screen views and duration must be aggregated")
        require(result["engines"][0]["medianMs"] == 2000, "AI median must use recorded samples")
        require(result["speed"][0]["medianMs"] == 275, "PR load latency must be recorded")
        require(result["github"] == {"calls": 3, "failures": 1, "lowestRateLeft": 1000},
                "GitHub aggregates must match recorded counts")
        require(result["cache"] == {"hit": 1, "refetch": 0, "stale": 0},
                "Cache outcomes must match recorded events")
        rows = stored_events(analytics)
        require(len(rows) >= len(events), "Accepted telemetry must be persisted")
        received = next(event for event in rows if event["name"] == "error.client")
        require(set(received["props"]) == {"kind", "message"},
                "Content, path and credential properties must be dropped")
        require("session" not in received and "device" not in received,
                "Credential-shaped identifiers must be dropped")
        require(rows[0]["version"] == "[redacted]", "Version strings must be sanitized")
        require(all(event["rx"].startswith(str(datetime.now(timezone.utc).year)) for event in rows),
                "A forged client receive timestamp must not control persistence")
        require(not (analytics / "1990-01-01.ndjson").exists(),
                "A wrong client clock must not select an old day file")
        stored = "\n".join(path.read_text() for path in analytics.glob("*.ndjson"))
        for canary in (token, "/custom/private.rs", "src/private.rs", "fixture.person@example.invalid",
                       "opaque-test-credential", "private prompt fixture", "private content fixture",
                       "private text fixture"):
            require(canary not in stored, "A synthetic private-data canary escaped sanitization")

        # The body ceiling and the per-batch event cap are separate contracts.
        require(request("/api/events", raw=b"x" * (BODY_LIMIT + 1))[0] == 413,
                "An analytics body larger than 2 MiB must be rejected")
        require(request("/api/events", {"events": [
            {"name": "ui.tap", "app": "web", "props": {"id": "fixture-button"}}
            for _ in range(205)
        ]}) == (204, b""), "A capped batch should still be accepted")
        # Account failures bypass dispatch, but still belong in server error totals.
        for method, expected_status in (("account-me", 401), ("unknown-fixture", 404)):
            status, _ = request("/api/rpc", {"name": method, "args": {}})
            require(status == expected_status, "Failure fixture returned the wrong status")
        failed = summary(request)
        require(failed["totals"]["errors"] == 3, "Native and account failures must record server errors")
        failures = [event for event in stored_events(analytics) if event["name"] == "error.server"]
        require({event["props"]["method"] for event in failures} == {"account-me", "unknown-fixture"},
                "Every response failure must be recorded once")
        require(any(event["props"].get("message") == "Sign in first." for event in failures),
                "Sanitized client-visible failure messages must reach Stats")

        capped = summary(request)
        tap = next(row for row in capped["names"] if row["name"] == "ui.tap")
        require(tap["count"] == 200, "Only the first 200 events in a batch may be stored")
        require(sum(row["name"] == "ui.tap" for row in stored_events(analytics)) == 200,
                "The 200-event cap must also hold in persisted NDJSON")
    print("Enabled analytics: HTTP ingestion, privacy, persistence, real summary, caps passed")


def disabled_smoke(root):
    with server(root, enabled=False) as (request, analytics):
        require(request("/api/events", {"events": [
            {"name": "pr.open", "app": "web", "props": {"number": 1}}
        ]}) == (204, b""), "Opt-out must retain the accepted HTTP contract")
        require(request("/api/rpc", {"name": "account-me", "args": {}})[0] == 401,
                "Opt-out failure fixture returned the wrong status")
        result = summary(request)
        require(result["totals"]["events"] == 0, "Opt-out must discard client and server events")
        # Cross the normal two-second flush interval to catch delayed writes.
        time.sleep(2.2)
        require(not analytics.exists(), "Opt-out must not create or write analytics files")
    require(not (root / "analytics").exists(), "Shutdown must not write opted-out events")
    print("Disabled analytics: accepted ingestion and summary create no analytics files")



def disabled_retention_smoke(root):
    with server(root, enabled=False, seed_history=True) as (request, analytics):
        old = analytics / "2000-01-01.ndjson"
        current = next(path for path in analytics.glob("*.ndjson") if path != old)
        before = current.read_bytes()
        require(request("/api/events", {"events": [
            {"name": "pr.open", "app": "web", "props": {"number": 99}}
        ]}) == (204, b""), "Opt-out with existing history must still accept the HTTP request")
        result = summary(request)
        require(result["totals"]["events"] == 1 and result["totals"]["prsOpened"] == 0,
                "Opt-out must preserve readable history while discarding new events")
        require(not old.exists(), "Files older than 365 days must be pruned even while opted out")
        time.sleep(2.2)
        require(current.read_bytes() == before, "Opt-out must leave current history byte-for-byte unchanged")
        require(sorted(path.name for path in analytics.iterdir()) == sorted([current.name, "keep.txt"]),
                "Retention must not create new files or remove unrelated files")
        require((analytics / "keep.txt").read_text() == "unrelated fixture\n",
                "Retention must preserve unrelated content")
    require(current.read_bytes() == before, "Opted-out shutdown must not append server events")
    print("Disabled retention: expired history removed, current and unrelated files unchanged")


def main():
    require(BINARY.is_file(), "Build the backend before running this fixture; no build is invoked here")
    with tempfile.TemporaryDirectory(prefix="pulls-rust-analytics-smoke-") as temporary:
        root = Path(temporary)
        enabled_smoke(root / "enabled")
        disabled_smoke(root / "disabled")
        disabled_retention_smoke(root / "disabled-retention")
    print("Offline HTTP analytics smoke passed; temporary data removed")


if __name__ == "__main__":
    main()
