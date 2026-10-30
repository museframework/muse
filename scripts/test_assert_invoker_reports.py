import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("assert-invoker-reports.py")


class InvokerReportAssertionsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("assert_invoker_reports", SCRIPT)
        assert spec and spec.loader
        cls.reports = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.reports)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.module = self.root / "owner"
        self.report_dir = self.root / "reports"
        self.report_dir.mkdir()

    def fixture(self, name, expected_child_result="success"):
        directory = self.module / "src/it" / name
        directory.mkdir(parents=True)
        (directory / "pom.xml").write_text("<project/>")
        if expected_child_result != "success":
            (directory / "invoker.properties").write_text(
                f"invoker.buildResult={expected_child_result}\n"
            )

    def report(self, fixture, result="success"):
        (self.report_dir / f"BUILD-{fixture}.xml").write_text(
            f'<build-job project="{fixture}/pom.xml" result="{result}"/>'
        )

    def test_accepts_positive_and_expected_failing_child_fixtures(self):
        self.fixture("positive")
        self.fixture("missing-provider", "failure")
        self.report("positive")
        self.report("missing-provider")

        self.assertIsNone(
            self.reports.assert_expected_fixtures(self.module, self.report_dir)
        )

    def test_rejects_missing_fixture_report(self):
        self.fixture("positive")

        with self.assertRaisesRegex(ValueError, "missing.*positive"):
            self.reports.assert_expected_fixtures(self.module, self.report_dir)

    def test_rejects_failed_or_skipped_outer_build_job(self):
        for result in ("failure", "skipped"):
            with self.subTest(result=result):
                self.fixture(result)
                self.report(result, result)
                with self.assertRaisesRegex(ValueError, result):
                    self.reports.assert_expected_fixtures(
                        self.module, self.report_dir
                    )
                (self.module / "src/it" / result / "pom.xml").unlink()
                (self.report_dir / f"BUILD-{result}.xml").unlink()

    def test_rejects_unknown_fixture(self):
        self.fixture("known")
        self.report("known")
        self.report("unknown")

        with self.assertRaisesRegex(ValueError, "unknown.*unknown"):
            self.reports.assert_expected_fixtures(self.module, self.report_dir)

    def test_rejects_negative_child_that_incorrectly_succeeded(self):
        self.fixture("missing-provider", "failure")
        self.report("missing-provider", "failure")

        with self.assertRaisesRegex(ValueError, "missing-provider.*failure"):
            self.reports.assert_expected_fixtures(self.module, self.report_dir)

    def test_rejects_duplicate_fixture_identity(self):
        self.fixture("known")
        self.report("known")
        (self.report_dir / "BUILD-copy.xml").write_text(
            '<build-job project="known/pom.xml" result="success"/>'
        )

        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.reports.assert_expected_fixtures(self.module, self.report_dir)

    def test_cli_returns_nonzero_for_invalid_outer_result(self):
        self.fixture("known")
        self.report("known", "failure")

        result = subprocess.run(
            [sys.executable, SCRIPT, self.module, self.report_dir],
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("known", result.stderr)


if __name__ == "__main__":
    unittest.main()
