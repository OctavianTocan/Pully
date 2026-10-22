#!/usr/bin/env python3
"""Print real xcresult evidence and reject an empty or incomplete UIKit test run."""

import json
from pathlib import Path
import subprocess
import sys


def require_complete_tests(summary):
    if not isinstance(summary, dict):
        raise ValueError("The UIKit summary is not an object.")
    total = summary.get("totalTestCount")
    if not isinstance(total, int) or isinstance(total, bool) or total <= 0:
        raise ValueError("UIKit did not execute any tests.")
    if summary.get("result") != "Passed" or summary.get("passedTests") != total:
        raise ValueError("The real UIKit suite did not pass every test.")
    if any(summary.get(field) != 0 for field in ("failedTests", "skippedTests", "expectedFailures")):
        raise ValueError("UIKit reported failed, skipped or expected-failure tests.")


def require_host_test_inventory(summary, inventory):
    """
    @cc [owner:OctavianTocan,label:architecture;product] ios-host-receipt-identifies-both-test-targets
    The release host receipt MUST prove nonempty passing PullyUITests UI and
    PullyAccountTests unit-test bundles from the same xcresult as its summary.
    Missing, empty or misidentified targets, failed cases, duplicate case identities
    and inventory counts that disagree with the summary MUST reject the receipt.
    Source: Tavi's 22 Oct 2026 strong release-verification decision and the accepted
    native verification plan requiring actual UIKit and app-hosted account execution.
    """
    require_complete_tests(summary)
    required = {"PullyUITests": "UI test bundle", "PullyAccountTests": "Unit test bundle"}
    targets = {}

    def children(node, field="children"):
        if not isinstance(node, dict) or not isinstance(node.get(field), list):
            raise ValueError("The host test inventory has a malformed node list.")
        return node[field]

    def cases(nodes):
        for node in nodes:
            if not isinstance(node, dict):
                raise ValueError("The host test inventory has a malformed test node.")
            kind = node.get("nodeType")
            if kind == "Test Suite":
                yield from cases(children(node))
            elif kind == "Test Case":
                identifier = node.get("nodeIdentifier")
                if not isinstance(identifier, str) or not identifier.strip() or node.get("result") != "Passed":
                    raise ValueError("Every inventoried host test must have an identity and pass.")
                yield identifier
            else:
                raise ValueError("The host test inventory contains an unexpected suite or case node.")

    for plan in children(inventory, "testNodes"):
        if not isinstance(plan, dict) or plan.get("nodeType") != "Test Plan":
            raise ValueError("The host test inventory is missing its test-plan boundary.")
        for bundle in children(plan):
            if not isinstance(bundle, dict) or bundle.get("nodeType") not in ("UI test bundle", "Unit test bundle"):
                raise ValueError("The host test inventory is missing its test-bundle boundary.")
            name = bundle.get("name")
            if not isinstance(name, str) or not name.strip():
                raise ValueError("An inventoried host test bundle has no name.")
            target = targets.setdefault(name, {"nodeType": bundle["nodeType"], "testIdentifiers": []})
            if target["nodeType"] != bundle["nodeType"]:
                raise ValueError("The host test inventory disagrees about a bundle's type.")
            for identifier in cases(children(bundle)):
                if identifier in target["testIdentifiers"]:
                    raise ValueError("The host test inventory repeats a target-qualified test identity.")
                target["testIdentifiers"].append(identifier)

    for name, kind in required.items():
        target = targets.get(name)
        if target is None or target["nodeType"] != kind or not target["testIdentifiers"]:
            raise ValueError(f"The host run did not execute the required {name} {kind}.")
    total = sum(len(target["testIdentifiers"]) for target in targets.values())
    if total != summary["totalTestCount"]:
        raise ValueError("The host test inventory does not account for every summary test.")
    return {"totalTestCount": total, "targets": targets}


def main():
    """
    @cc [owner:OctavianTocan,label:architecture;product] ios-early-ui-evidence-preserves-the-full-gate
    The release UIKit verification MUST compile the checked-out source with the pinned Apple
    toolchain and run the actual host test suite. It MUST reject zero, failed or skipped
    tests and retain readable xcresult and accessibility evidence. It MUST NOT replace,
    skip or weaken the full native, Keychain, Darwin, shared-test or Android release-promotion gates.
    Source: Tavi's 22 Oct 2026 fast-main and strongly verified release-branch decision.
    """
    bundle, output = map(Path, sys.argv[1:])
    if not (bundle / "Info.plist").is_file():
        raise SystemExit("No completed UIKit xcresult is available; inspect the host build log.")
    output.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(
        ["xcrun", "xcresulttool", "get", "test-results", "summary", "--path", str(bundle)],
        check=True, capture_output=True, text=True,
    )
    (output / "host-test-summary.json").write_text(result.stdout)
    summary = json.loads(result.stdout)
    print(json.dumps(summary, indent=2), flush=True)
    inventory_result = subprocess.run(
        ["xcrun", "xcresulttool", "get", "test-results", "tests", "--path", str(bundle)],
        check=True, capture_output=True, text=True,
    )
    (output / "host-test-inventory.json").write_text(inventory_result.stdout)
    inventory = json.loads(inventory_result.stdout)
    subprocess.run(
        ["xcrun", "xcresulttool", "export", "attachments", "--path", str(bundle),
         "--output-path", str(output / "attachments")],
        check=True, stdout=subprocess.DEVNULL,
    )
    for report in sorted((output / "attachments").rglob("*.txt"))[:12]:
        print("::group::" + report.name)
        print(report.read_text(errors="replace")[:24000])
        print("::endgroup::")
    receipt = require_host_test_inventory(summary, inventory)
    (output / "host-test-targets.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt, indent=2), flush=True)


if __name__ == "__main__":
    main()
