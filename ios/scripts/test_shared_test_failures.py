import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("shared_test_failures", Path(__file__).with_name("print-shared-test-failures.py"))
reports = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reports)


class SharedTestFailureTests(unittest.TestCase):
    def test_junit_fields_preserve_expected_actual_and_native_stack(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "TEST-Native.xml"
            path.write_text('''<testsuite name="Native">
                <testcase classname="StatsNativeFormatTest" name="money[iosSimulatorArm64]">
                    <failure message="Expected &lt;$1,234.50&gt; but was &lt;$1234.50&gt;">kotlin.AssertionError
at StatsNativeFormatTest.originalMoneyTokensAndTimeUnitsUseFixedEnglishDecimalFormatting</failure>
                </testcase>
                <testcase classname="Other" name="passing"/>
                <testcase classname="Other" name="skipped"><skipped/></testcase>
                <testcase classname="Other" name="crash"><error message="fixture refused editing">stack</error></testcase>
            </testsuite>''')
            failures = list(reports.report_failures(path))
            self.assertEqual(len(failures), 2)
            self.assertEqual(failures[0]["suite"], "StatsNativeFormatTest")
            self.assertEqual(failures[0]["test"], "money[iosSimulatorArm64]")
            self.assertEqual(failures[0]["message"], "Expected <$1,234.50> but was <$1234.50>")
            self.assertIn("at StatsNativeFormatTest", failures[0]["details"])
            self.assertEqual(failures[1]["outcome"], "error")

    def test_bounded_stack_lines_cannot_become_workflow_commands(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "TEST-Native.xml"
            path.write_text('<testsuite><testcase name="failed"><failure message="' + 'x' * 4000 + '">'
                            + '\n::error::from assertion\n' + 'y' * 16000 + '</failure></testcase></testsuite>')
            failure = next(reports.report_failures(path))
            self.assertEqual(len(failure["message"]), 2000)
            self.assertEqual(len(failure["details"]), 8000)
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                reports.print_failure(failure)
            self.assertIn("  | ::error::from assertion", output.getvalue())
            self.assertFalse(any(line.startswith("::") for line in output.getvalue().splitlines()))

    def test_missing_or_malformed_reports_are_explicit_and_other_failures_survive(self):
        with tempfile.TemporaryDirectory() as directory:
            output = io.StringIO()
            with patch.object(reports.sys, "argv", ["print-shared-test-failures.py", directory]), contextlib.redirect_stdout(output):
                reports.main()
            self.assertIn("No shared JUnit reports", output.getvalue())
            (Path(directory) / "TEST-broken.xml").write_text("<broken")
            (Path(directory) / "TEST-valid.xml").write_text('<testsuite><testcase name="retained"><failure message="expected actual">stack</failure></testcase></testsuite>')
            output = io.StringIO()
            with patch.object(reports.sys, "argv", ["print-shared-test-failures.py", directory]), contextlib.redirect_stdout(output):
                reports.main()
            self.assertIn('"unreadableReport":', output.getvalue())
            self.assertIn('"test": "retained"', output.getvalue())
            self.assertIn('"failuresPrinted": 1', output.getvalue())


class WorkflowCommandEnvelopeTests(unittest.TestCase):
    def test_legacy_commands_in_metadata_and_details_stay_inside_the_disabled_envelope(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "TEST-Native.xml"
            path.write_text('''<testsuite><testcase name="##[add-mask]metadata">
                <failure message="##[warning]message">##[add-mask]kotlin.AssertionError
::error::modern assertion
at Native.assertion</failure></testcase></testsuite>''')
            output = io.StringIO()
            with patch.object(reports.sys, "argv", ["print-shared-test-failures.py", directory]), contextlib.redirect_stdout(output):
                reports.main()
            lines = output.getvalue().splitlines()
            self.assertRegex(lines[0], r"^::stop-commands::[0-9a-f]{64}$")
            token = lines[0].removeprefix("::stop-commands::")
            self.assertEqual(lines[-1], f"::{token}::")
            self.assertIn('"test": "##[add-mask]metadata"', output.getvalue())
            self.assertIn('"message": "##[warning]message"', output.getvalue())
            self.assertIn("  | ##[add-mask]kotlin.AssertionError", lines[1:-1])
            self.assertIn("  | ::error::modern assertion", lines[1:-1])
            self.assertIn('"failuresPrinted": 1', lines[-2])

    def test_processing_resumes_even_when_report_reading_raises(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "TEST-Native.xml").write_text("<testsuite/>")
            output = io.StringIO()
            with patch.object(reports.sys, "argv", ["print-shared-test-failures.py", directory]), \
                    patch.object(reports, "report_failures", side_effect=RuntimeError("reader failed")), \
                    contextlib.redirect_stdout(output), self.assertRaisesRegex(RuntimeError, "reader failed"):
                reports.main()
            lines = output.getvalue().splitlines()
            self.assertGreaterEqual(len(lines), 2)
            self.assertRegex(lines[0], r"^::stop-commands::[0-9a-f]{64}$")
            self.assertEqual(lines[-1], f"::{lines[0].removeprefix('::stop-commands::')}::")


if __name__ == "__main__":
    unittest.main()
