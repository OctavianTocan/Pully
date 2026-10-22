#!/usr/bin/env python3
"""Keep failed shared-test assertions readable when artifact downloads are unavailable."""

import json
from pathlib import Path
import secrets
import sys
import xml.etree.ElementTree as ET


MAX_FAILURES = 20
MAX_MESSAGE = 2000
MAX_DETAILS = 8000


def report_failures(path):
    root = ET.parse(path).getroot()
    for case in root.iter("testcase"):
        for outcome in case:
            if outcome.tag in ("failure", "error"):
                yield {
                    "report": str(path), "suite": case.get("classname", root.get("name", "")),
                    "test": case.get("name", ""), "outcome": outcome.tag,
                    "message": outcome.get("message", "")[:MAX_MESSAGE],
                    "details": (outcome.text or "")[:MAX_DETAILS],
                }


def print_failure(failure):
    details = failure["details"]
    print(json.dumps({key: value for key, value in failure.items() if key != "details"}), flush=True)
    for line in details.splitlines():
        print("  | " + line, flush=True)


def _print_reports():
    """
    @cc [owner:OctavianTocan,label:architecture] ios-native-failure-evidence-preserves-the-failed-gate
    After shared Native verification fails, CI MUST retain bounded readable actual
    JUnit testcase names, assertion messages and stacks in its log. Missing or
    unreadable reports MUST be identified, never represented as a passing suite.
    Diagnostics MUST NOT replace or override the original test exit status, remove
    assertions or bypass the strong release checks and full artifact uploads.
    All report-derived output MUST suspend runner command processing with a fresh
    unpredictable token and restore it even if reading a report raises.
    Source: Tavi's 22 Oct 2026 strong release-verification decision and accepted
    failure-evidence repair after the 608-case Native run's four assertion failures.
    """
    reports = sorted({path for directory in sys.argv[1:] for path in Path(directory).rglob("TEST-*.xml")})
    if not reports:
        print("No shared JUnit reports are available; inspect the failed Gradle step.", flush=True)
    count = 0
    for report in reports:
        try:
            for failure in report_failures(report):
                if count == MAX_FAILURES:
                    print("Further failures omitted from this bounded log; retain the full test artifacts.", flush=True)
                    return
                print_failure(failure)
                count += 1
        except (ET.ParseError, OSError) as error:
            print(json.dumps({"unreadableReport": str(report), "error": str(error)[:MAX_MESSAGE]}), flush=True)
    print(json.dumps({"reportsFound": len(reports), "failuresPrinted": count}), flush=True)


def main():
    token = secrets.token_hex(32)
    print(f"::stop-commands::{token}", flush=True)
    try:
        _print_reports()
    finally:
        print(f"::{token}::", flush=True)


if __name__ == "__main__":
    main()
