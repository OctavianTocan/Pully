#!/usr/bin/env python3
"""Print real xcresult evidence and reject an empty or incomplete UIKit test run."""

import json
from pathlib import Path
import subprocess
import sys


def require_complete_tests(summary):
    total = summary.get("totalTestCount")
    if not isinstance(total, int) or isinstance(total, bool) or total <= 0:
        raise ValueError("UIKit did not execute any tests.")
    if summary.get("result") != "Passed" or summary.get("passedTests") != total:
        raise ValueError("The real UIKit suite did not pass every test.")
    if any(summary.get(field) != 0 for field in ("failedTests", "skippedTests", "expectedFailures")):
        raise ValueError("UIKit reported failed, skipped or expected-failure tests.")


def main():
    """
    @cc [owner:OctavianTocan,label:architecture;product] ios-early-ui-evidence-preserves-the-full-gate
    The early UIKit diagnostic MUST compile the checked-out source with the pinned Apple
    toolchain and run the actual host test suite. It MUST reject zero, failed or skipped
    tests and retain readable xcresult and accessibility evidence. It MUST NOT replace,
    skip or weaken the full native, Keychain, Darwin, shared-test or Android merge gates.
    Source: Tavi's 21 Oct 2026 request to parallelize and shorten the iOS CI feedback loop.
    """
    bundle, output = map(Path, sys.argv[1:])
    if not (bundle / "Info.plist").is_file():
        raise SystemExit("No completed UIKit xcresult is available; inspect the host build log.")
    output.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(
        ["xcrun", "xcresulttool", "get", "test-results", "summary", "--path", str(bundle)],
        check=True, capture_output=True, text=True,
    )
    summary = json.loads(result.stdout)
    (output / "host-test-summary.json").write_text(result.stdout)
    print(json.dumps(summary, indent=2), flush=True)
    subprocess.run(
        ["xcrun", "xcresulttool", "export", "attachments", "--path", str(bundle),
         "--output-path", str(output / "attachments")],
        check=True, stdout=subprocess.DEVNULL,
    )
    for report in sorted((output / "attachments").rglob("*.txt"))[:12]:
        print("::group::" + report.name)
        print(report.read_text(errors="replace")[:24000])
        print("::endgroup::")
    require_complete_tests(summary)


if __name__ == "__main__":
    main()
