import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location("host_test_results", Path(__file__).with_name("print-host-test-results.py"))
results = importlib.util.module_from_spec(spec)
spec.loader.exec_module(results)


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


if __name__ == "__main__":
    unittest.main()
