import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("assert-e2e-reports.py")
SPEC = importlib.util.spec_from_file_location("assert_e2e_reports", SCRIPT)
assert SPEC and SPEC.loader
reports = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(reports)


class E2EReportAssertionsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.module = self.root / "module"
        self.report_dir = self.root / "reports"
        self.source_dir = self.module / "src/test/kotlin/example"
        self.source_dir.mkdir(parents=True)
        self.report_dir.mkdir()

    def source(self, filename, body):
        (self.source_dir / filename).write_text("package example\n\n" + body)

    def report(self, filename, suite, cases, **counts):
        statuses = {"failure": 0, "error": 0, "skipped": 0}
        testcase_xml = []
        for classname, name, status in cases:
            child = "" if status == "passed" else f"<{status}/>"
            testcase_xml.append(
                f'<testcase classname="{classname}" name="{name}">{child}</testcase>'
            )
            if status != "passed":
                statuses[status] += 1
        values = {
            "tests": len(cases),
            "failures": statuses["failure"],
            "errors": statuses["error"],
            "skipped": statuses["skipped"],
            **counts,
        }
        (self.report_dir / filename).write_text(
            f'<testsuite name="{suite}" tests="{values["tests"]}" '
            f'failures="{values["failures"]}" errors="{values["errors"]}" '
            f'skipped="{values["skipped"]}">{"".join(testcase_xml)}</testsuite>'
        )

    def test_accepts_backtick_name_and_xml_entities(self):
        self.source(
            "EscapedE2ETest.kt",
            "class EscapedE2ETest {\n"
            "  @Test fun `accepts A & B less than C`() = Unit\n"
            "}\n",
        )
        self.report(
            "TEST-example.EscapedE2ETest.xml",
            "example.EscapedE2ETest",
            [("example.EscapedE2ETest", "accepts A &amp; B less than C", "passed")],
        )

        self.assertIsNone(reports.assert_expected_cases(self.module, self.report_dir))

    def test_rejects_skipped_expected_method(self):
        self.source("SampleE2ETest.kt", "class SampleE2ETest { @Test fun `works`() = Unit }\n")
        self.report(
            "TEST-example.SampleE2ETest.xml",
            "example.SampleE2ETest",
            [("example.SampleE2ETest", "works", "skipped")],
        )

        with self.assertRaisesRegex(ValueError, "skipped"):
            reports.assert_expected_cases(self.module, self.report_dir)

    def test_rejects_failure_and_error(self):
        self.source(
            "SampleE2ETest.kt",
            "class SampleE2ETest {\n"
            " @Test fun `fails`() = Unit\n @Test fun `errors`() = Unit\n}\n",
        )
        self.report(
            "TEST-example.SampleE2ETest.xml",
            "example.SampleE2ETest",
            [
                ("example.SampleE2ETest", "fails", "failure"),
                ("example.SampleE2ETest", "errors", "error"),
            ],
        )

        with self.assertRaisesRegex(ValueError, "failure.*error|error.*failure"):
            reports.assert_expected_cases(self.module, self.report_dir)

    def test_rejects_missing_report(self):
        self.source("SampleE2ETest.kt", "class SampleE2ETest { @Test fun `works`() = Unit }\n")

        with self.assertRaisesRegex(ValueError, "missing testcase"):
            reports.assert_expected_cases(self.module, self.report_dir)

    def test_does_not_accept_same_method_from_another_class(self):
        self.source("WantedE2ETest.kt", "class WantedE2ETest { @Test fun `works`() = Unit }\n")
        self.report(
            "TEST-example.OtherE2ETest.xml",
            "example.OtherE2ETest",
            [("example.OtherE2ETest", "works", "passed")],
        )

        with self.assertRaisesRegex(ValueError, "example.WantedE2ETest.*works"):
            reports.assert_expected_cases(self.module, self.report_dir)

    def test_rejects_duplicate_testcase_identity(self):
        self.source("SampleE2ETest.kt", "class SampleE2ETest { @Test fun `works`() = Unit }\n")
        case = ("example.SampleE2ETest", "works", "passed")
        self.report("TEST-one.xml", "one", [case])
        self.report("TEST-two.xml", "two", [case])

        with self.assertRaisesRegex(ValueError, "duplicate"):
            reports.assert_expected_cases(self.module, self.report_dir)

    def test_discovers_inherited_local_streaming_methods(self):
        self.source(
            "StreamingSupport.kt",
            "abstract class StreamingSupport {\n"
            " @Test fun `streams locally`() = Unit\n"
            "}\n",
        )
        self.source(
            "StreamingE2ETest.kt",
            "class StreamingE2ETest : StreamingSupport() {\n"
            " @Test fun `starts locally`() = Unit\n"
            "}\n",
        )

        self.assertEqual(
            {
                ("example.StreamingE2ETest", "streams locally"),
                ("example.StreamingE2ETest", "starts locally"),
            },
            reports.expected_testcases(self.module),
        )

    def test_accepts_nested_display_names_with_enclosing_suite_counters(self):
        self.source(
            "NestedE2ETest.kt",
            'class NestedE2ETest {\n'
            ' @Nested\n @DisplayName("GET /hello")\n inner class GetHello {\n'
            '  @Test fun `returns hello`() = Unit\n }\n'
            ' @Nested\n @DisplayName("POST /hello")\n inner class PostHello {\n'
            '  @Test fun `returns hello`() = Unit\n }\n}\n',
        )
        self.report(
            "TEST-example.NestedE2ETest.xml",
            "example.NestedE2ETest",
            [
                ("GET /hello", "returns hello", "passed"),
                ("POST /hello", "returns hello", "passed"),
            ],
            tests=0,
        )

        self.assertIsNone(reports.assert_expected_cases(self.module, self.report_dir))

    def test_rejects_missing_nested_group_with_same_method_name(self):
        self.source(
            "NestedE2ETest.kt",
            'class NestedE2ETest {\n'
            ' @Nested\n @DisplayName("GET /hello")\n inner class GetHello {\n'
            '  @Test fun `returns hello`() = Unit\n }\n'
            ' @Nested\n @DisplayName("POST /hello")\n inner class PostHello {\n'
            '  @Test fun `returns hello`() = Unit\n }\n}\n',
        )
        self.report(
            "TEST-example.NestedE2ETest.xml",
            "example.NestedE2ETest",
            [("GET /hello", "returns hello", "passed")],
            tests=0,
        )

        with self.assertRaisesRegex(ValueError, "PostHello.*missing testcase"):
            reports.assert_expected_cases(self.module, self.report_dir)

    def test_accepts_nested_display_names_in_either_annotation_order(self):
        for annotations, label in (
            ('@DisplayName("GET /hello")\n @Nested', 'GET /hello'),
            ('@Nested\n @DisplayName("GET.Success")', 'GET.Success'),
        ):
            with self.subTest(annotations=annotations):
                self.source(
                    "NestedE2ETest.kt",
                    'class NestedE2ETest {\n'
                    f' {annotations}\n inner class GetHello {{\n'
                    '  @Test fun `returns hello`() = Unit\n }\n}\n',
                )
                self.report(
                    "TEST-example.NestedE2ETest.xml", "example.NestedE2ETest",
                    [(label, "returns hello", "passed")], tests=0,
                )
                self.assertIsNone(reports.assert_expected_cases(self.module, self.report_dir))

    def test_accepts_separate_binary_nested_reports_with_display_names(self):
        self.source(
            "NestedE2ETest.kt",
            'class NestedE2ETest {\n'
            ' @Nested\n @DisplayName("GET /hello")\n inner class GetHello {\n'
            '  @Test fun `returns hello`() = Unit\n }\n}\n',
        )
        self.report("TEST-parent.xml", "example.NestedE2ETest", [])
        self.report(
            "TEST-child.xml", "example.NestedE2ETest$GetHello",
            [("example.NestedE2ETest$GetHello", "returns hello", "passed")],
        )
        self.assertIsNone(reports.assert_expected_cases(self.module, self.report_dir))

    def test_requires_inherited_nested_methods(self):
        self.source(
            "Base.kt",
            'abstract class Base {\n'
            ' @Nested\n @DisplayName("GET /hello")\n inner class GetHello {\n'
            '  @Test fun `returns hello`() = Unit\n }\n}\n',
        )
        self.source(
            "NestedE2ETest.kt",
            'class NestedE2ETest : Base() { @Test fun `starts`() = Unit }\n',
        )
        self.report(
            "TEST-parent.xml", "example.NestedE2ETest",
            [("example.NestedE2ETest", "starts", "passed")],
        )
        with self.assertRaisesRegex(ValueError, "GetHello.*missing testcase"):
            reports.assert_expected_cases(self.module, self.report_dir)
        self.report(
            "TEST-parent.xml", "example.NestedE2ETest",
            [("example.NestedE2ETest", "starts", "passed"),
             ("GET /hello", "returns hello", "passed")], tests=1,
        )
        self.assertIsNone(reports.assert_expected_cases(self.module, self.report_dir))
        self.report(
            "TEST-parent.xml", "example.NestedE2ETest",
            [("example.NestedE2ETest", "starts", "passed")],
        )
        self.report(
            "TEST-child.xml", "example.Base$GetHello",
            [("example.Base$GetHello", "returns hello", "passed")],
        )
        self.assertIsNone(reports.assert_expected_cases(self.module, self.report_dir))

    def test_rejects_nested_failure_error_and_skip_with_zero_parent_counters(self):
        self.source(
            "NestedE2ETest.kt",
            'class NestedE2ETest {\n'
            ' @Nested\n @DisplayName("GET /hello")\n inner class GetHello {\n'
            '  @Test fun `returns hello`() = Unit\n }\n}\n',
        )
        for status in ("failure", "error", "skipped"):
            with self.subTest(status=status):
                self.report(
                    "TEST-example.NestedE2ETest.xml",
                    "example.NestedE2ETest",
                    [("GET /hello", "returns hello", status)],
                    tests=0,
                    failures=0,
                    errors=0,
                    skipped=0,
                )
                with self.assertRaisesRegex(ValueError, "returns hello.*" + status):
                    reports.assert_expected_cases(self.module, self.report_dir)

    def test_keeps_inherited_nested_coverage_per_concrete_class(self):
        self.source(
            "Base.kt",
            'abstract class Base {\n'
            ' @Nested\n @DisplayName("GET /hello")\n inner class GetHello {\n'
            '  @Test fun `returns hello`() = Unit\n }\n}\n',
        )
        for owner in ("FirstE2ETest", "SecondE2ETest"):
            self.source(
                f"{owner}.kt",
                f'class {owner} : Base() {{ @Test fun `starts`() = Unit }}\n',
            )
            cases = [(f"example.{owner}", "starts", "passed")]
            if owner == "FirstE2ETest":
                cases.append(("GET /hello", "returns hello", "passed"))
            self.report(f"TEST-{owner}.xml", f"example.{owner}", cases, tests=1)
        with self.assertRaisesRegex(ValueError, "SecondE2ETest.*missing testcase"):
            reports.assert_expected_cases(self.module, self.report_dir)
        self.report(
            "TEST-SecondE2ETest.xml", "example.SecondE2ETest",
            [("example.SecondE2ETest", "starts", "passed"),
             ("GET /hello", "returns hello", "passed")], tests=1,
        )
        self.assertIsNone(reports.assert_expected_cases(self.module, self.report_dir))
        self.report(
            "TEST-child.xml", "example.Base$GetHello",
            [("example.Base$GetHello", "returns hello", "passed")],
        )
        with self.assertRaisesRegex(ValueError, "ambiguous nested"):
            reports.assert_expected_cases(self.module, self.report_dir)

    def test_rejects_suite_level_teardown_error(self):
        self.source("SampleE2ETest.kt", "class SampleE2ETest { @Test fun `works`() = Unit }\n")
        (self.report_dir / "TEST-sample.xml").write_text(
            '<testsuite name="example.SampleE2ETest" tests="1" failures="0" '
            'errors="1" skipped="0"><testcase classname="example.SampleE2ETest" '
            'name="works"/><error message="afterAll failed"/></testsuite>'
        )

        with self.assertRaisesRegex(ValueError, "suite.*error|error.*suite"):
            reports.assert_expected_cases(self.module, self.report_dir)

    def test_rejects_testsuites_container_error(self):
        (self.report_dir / "TEST-container.xml").write_text(
            '<testsuites name="all" tests="0" failures="0" errors="1" skipped="0">'
            '<error message="container failed"/></testsuites>'
        )

        with self.assertRaisesRegex(ValueError, "all.*container-level error"):
            reports.read_testcases(self.report_dir)

    def test_rejects_testsuites_container_counter_mismatch(self):
        (self.report_dir / "TEST-container.xml").write_text(
            '<testsuites name="all" tests="1" failures="0" errors="1" skipped="0">'
            '<testsuite name="example.SampleE2ETest" tests="1" failures="0" '
            'errors="0" skipped="0"><testcase classname="example.SampleE2ETest" '
            'name="works"/></testsuite></testsuites>'
        )

        with self.assertRaisesRegex(ValueError, "container errors count is 1, parsed 0"):
            reports.read_testcases(self.report_dir)

    def test_rejects_contradictory_counts_and_malformed_xml(self):
        self.source("SampleE2ETest.kt", "class SampleE2ETest { @Test fun `works`() = Unit }\n")
        self.report(
            "TEST-bad-count.xml",
            "example.SampleE2ETest",
            [("example.SampleE2ETest", "works", "passed")],
            failures=1,
        )
        with self.assertRaisesRegex(ValueError, "count"):
            reports.read_testcases(self.report_dir)

        (self.report_dir / "TEST-bad-count.xml").write_text("<testsuite>")
        with self.assertRaisesRegex(ValueError, "malformed"):
            reports.read_testcases(self.report_dir)

    def test_combined_suffixes_require_it_and_e2e_reports(self):
        self.source(
            "GeneratedArtifactCompilationIT.kt",
            "class GeneratedArtifactCompilationIT {\n"
            " @Test fun `generated artifact classes load`() = Unit\n"
            "}\n",
        )
        self.source("JourneyE2ETest.kt", "class JourneyE2ETest { @Test fun `journey works`() = Unit }\n")
        self.report(
            "TEST-example.JourneyE2ETest.xml",
            "example.JourneyE2ETest",
            [("example.JourneyE2ETest", "journey works", "passed")],
        )

        with self.assertRaises(ValueError):
            reports.assert_expected_cases(
                self.module, self.report_dir, ("IT", "E2ETest")
            )

        self.report(
            "TEST-example.GeneratedArtifactCompilationIT.xml",
            "example.GeneratedArtifactCompilationIT",
            [
                (
                    "example.GeneratedArtifactCompilationIT",
                    "generated artifact classes load",
                    "passed",
                )
            ],
        )
        self.assertIsNone(
            reports.assert_expected_cases(
                self.module, self.report_dir, ("IT", "E2ETest")
            )
        )
        self.assertEqual(
            {
                (
                    "example.GeneratedArtifactCompilationIT",
                    "generated artifact classes load",
                )
            },
            reports.expected_testcases(self.module, ("IT",)),
        )

    def test_keeps_malformed_source_and_no_suite_failures(self):
        self.source("BrokenE2ETest.kt", "class BrokenE2ETest { @Test fun `works`() = Unit\n")
        with self.assertRaises(SystemExit):
            reports.concrete_suites(self.module)

        (self.source_dir / "BrokenE2ETest.kt").unlink()
        with self.assertRaises(SystemExit):
            reports.concrete_suites(self.module)

    def test_cli_returns_nonzero_for_skipped_missing_and_duplicate_cases(self):
        self.source("SampleE2ETest.kt", "class SampleE2ETest { @Test fun `works`() = Unit }\n")
        case = ("example.SampleE2ETest", "works", "skipped")
        self.report("TEST-sample.xml", "example.SampleE2ETest", [case])

        def run():
            return subprocess.run(
                [sys.executable, SCRIPT, self.module, self.report_dir],
                capture_output=True,
                text=True,
                check=False,
            )

        self.assertNotEqual(0, run().returncode)
        (self.report_dir / "TEST-sample.xml").unlink()
        self.assertNotEqual(0, run().returncode)
        case = ("example.SampleE2ETest", "works", "passed")
        self.report("TEST-one.xml", "one", [case])
        self.report("TEST-two.xml", "two", [case])
        self.assertNotEqual(0, run().returncode)


if __name__ == "__main__":
    unittest.main()
