import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("host_test_results", Path(__file__).with_name("print-host-test-results.py"))
results = importlib.util.module_from_spec(spec)
spec.loader.exec_module(results)


def passing_summary(count=4):
    return {
        "totalTestCount": count, "passedTests": count, "failedTests": 0,
        "skippedTests": 0, "expectedFailures": 0, "result": "Passed",
    }


def passing_inventory():
    # Synthetic xcresulttool `get test-results tests` nodes, not Apple execution evidence.
    # Schema: bitrise-io/go-xcode@1be3b689, testresult/xcresult3/model3/testresults.go.
    return {"testNodes": [{"nodeType": "Test Plan", "name": "Pully", "children": [
        {"nodeType": kind, "name": target, "children": [{
            "nodeType": "Test Suite", "name": suite, "children": [
                {"nodeType": "Test Case", "name": name,
                 "nodeIdentifier": f"{suite}/{name}", "result": "Passed"}
                for name in names
            ],
        }]}
        for target, kind, suite, names in (
            ("PullyUITests", "UI test bundle", "PullyLaunchTests", ("testRoot()", "testBack()")),
            ("PullyAccountTests", "Unit test bundle", "AccountStorageHostTests", ("testRestore()", "testReplace()")),
        )
    ]}]}


class HostTestResultTests(unittest.TestCase):
    def test_accepts_the_real_four_test_success_shape(self):
        results.require_complete_tests({
            "totalTestCount": 4, "passedTests": 4, "failedTests": 0,
            "skippedTests": 0, "expectedFailures": 0, "result": "Passed",
        })

    def test_an_empty_selection_cannot_be_a_green_diagnostic(self):
        with self.assertRaises(ValueError):
            results.require_complete_tests({
                "totalTestCount": 0, "passedTests": 0, "failedTests": 0,
                "skippedTests": 0, "expectedFailures": 0, "result": "Passed",
            })

    def test_summary_status_cannot_hide_a_failed_skipped_or_expected_test(self):
        for field in ("failedTests", "skippedTests", "expectedFailures"):
            with self.subTest(field=field), self.assertRaises(ValueError):
                results.require_complete_tests({
                    "totalTestCount": 4, "passedTests": 4, "failedTests": 0,
                    "skippedTests": 0, "expectedFailures": 0, "result": "Passed", field: 1,
                })


class HostTargetInventoryTests(unittest.TestCase):
    def test_success_retains_target_qualified_case_identities(self):
        receipt = results.require_host_test_inventory(passing_summary(), passing_inventory())
        self.assertEqual(receipt["totalTestCount"], 4)
        self.assertEqual(receipt["targets"]["PullyUITests"]["testIdentifiers"],
                         ["PullyLaunchTests/testRoot()", "PullyLaunchTests/testBack()"])
        self.assertEqual(len(receipt["targets"]["PullyAccountTests"]["testIdentifiers"]), 2)

    def test_account_only_passing_summary_does_not_prove_uikit_ran(self):
        inventory = passing_inventory()
        inventory["testNodes"][0]["children"].pop(0)
        with self.assertRaisesRegex(ValueError, "PullyUITests"):
            results.require_host_test_inventory(passing_summary(2), inventory)

    def test_empty_ui_bundle_cannot_borrow_passing_account_counts(self):
        inventory = passing_inventory()
        inventory["testNodes"][0]["children"][0]["children"] = []
        with self.assertRaisesRegex(ValueError, "PullyUITests"):
            results.require_host_test_inventory(passing_summary(2), inventory)

    def test_a_unit_bundle_or_named_suite_cannot_impersonate_the_ui_target(self):
        for kind in ("Unit test bundle", "Test Suite"):
            with self.subTest(kind=kind):
                inventory = passing_inventory()
                inventory["testNodes"][0]["children"][0]["nodeType"] = kind
                with self.assertRaises(ValueError):
                    results.require_host_test_inventory(passing_summary(), inventory)

    def test_an_empty_or_misidentified_account_target_is_also_rejected(self):
        for replacement in ([], None):
            with self.subTest(replacement=replacement):
                inventory = passing_inventory()
                account = inventory["testNodes"][0]["children"][1]
                if replacement is None:
                    account["name"] = "OtherAccountTests"
                else:
                    account["children"] = replacement
                with self.assertRaisesRegex(ValueError, "PullyAccountTests"):
                    results.require_host_test_inventory(passing_summary(2 if replacement == [] else 4), inventory)

    def test_nested_suites_and_case_execution_metadata_do_not_lose_identities(self):
        inventory = passing_inventory()
        ui = inventory["testNodes"][0]["children"][0]
        ui["children"] = [{"nodeType": "Test Suite", "name": "Nested", "children": ui["children"]}]
        ui["children"][0]["children"][0]["children"][0]["children"] = [{
            "nodeType": "Device", "name": "iPhone 17", "result": "Passed",
        }]
        self.assertEqual(results.require_host_test_inventory(passing_summary(), inventory)["totalTestCount"], 4)

    def test_green_aggregate_cannot_hide_an_unpassed_or_unidentified_case(self):
        for field, value in (("result", "Failed"), ("result", "Skipped"),
                             ("result", "Expected Failure"), ("nodeIdentifier", ""),
                             ("nodeIdentifier", None)):
            with self.subTest(field=field, value=value):
                inventory = passing_inventory()
                inventory["testNodes"][0]["children"][0]["children"][0]["children"][0][field] = value
                with self.assertRaises(ValueError):
                    results.require_host_test_inventory(passing_summary(), inventory)

    def test_duplicates_cannot_fill_in_a_missing_test(self):
        inventory = passing_inventory()
        cases = inventory["testNodes"][0]["children"][0]["children"][0]["children"]
        cases[1]["nodeIdentifier"] = cases[0]["nodeIdentifier"]
        with self.assertRaisesRegex(ValueError, "repeats"):
            results.require_host_test_inventory(passing_summary(), inventory)

    def test_partial_inventory_cannot_match_a_larger_passing_summary(self):
        inventory = passing_inventory()
        inventory["testNodes"][0]["children"][0]["children"][0]["children"].pop()
        with self.assertRaisesRegex(ValueError, "every summary test"):
            results.require_host_test_inventory(passing_summary(), inventory)

    def test_malformed_inventory_does_not_silently_drop_nodes(self):
        malformed = (None, {}, {"testNodes": None}, {"testNodes": [None]},
                     {"testNodes": [{"nodeType": "Test Plan", "children": None}]})
        for inventory in malformed:
            with self.subTest(inventory=inventory), self.assertRaises(ValueError):
                results.require_host_test_inventory(passing_summary(), inventory)

    def run_command(self, directory, inventory, summary):
        bundle = Path(directory) / "Pully.xcresult"
        output = Path(directory) / "evidence"
        bundle.mkdir()
        (bundle / "Info.plist").touch()
        responses = {
            "summary": json.dumps(summary),
            "tests": json.dumps(inventory),
        }

        def xcrun(command, **kwargs):
            self.assertEqual(command[:2], ["xcrun", "xcresulttool"])
            self.assertEqual(command[command.index("--path") + 1], str(bundle))
            self.assertTrue(kwargs["check"])
            if command[2] == "get":
                return subprocess.CompletedProcess(command, 0, stdout=responses[command[4]])
            self.assertEqual(command[2:4], ["export", "attachments"])
            return subprocess.CompletedProcess(command, 0)

        with patch.object(results.sys, "argv", ["print-host-test-results.py", str(bundle), str(output)]), \
                patch.object(results.subprocess, "run", side_effect=xcrun) as calls, \
                patch("builtins.print"):
            try:
                results.main()
            finally:
                self.assertEqual(calls.call_count, 3)
                self.assertEqual(json.loads((output / "host-test-summary.json").read_text()), summary)
                self.assertEqual(json.loads((output / "host-test-inventory.json").read_text()), inventory)
        return output

    def test_command_queries_the_same_bundle_and_saves_both_raw_inputs_and_qualified_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            output = self.run_command(directory, passing_inventory(), passing_summary())
            receipt = json.loads((output / "host-test-targets.json").read_text())
            self.assertEqual(set(receipt["targets"]), {"PullyUITests", "PullyAccountTests"})

    def test_command_keeps_raw_evidence_and_attachments_when_only_account_tests_ran(self):
        inventory = passing_inventory()
        inventory["testNodes"][0]["children"].pop(0)
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "PullyUITests"):
                self.run_command(directory, inventory, passing_summary(2))
            self.assertFalse((Path(directory) / "evidence" / "host-test-targets.json").exists())


if __name__ == "__main__":
    unittest.main()
