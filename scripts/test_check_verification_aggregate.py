from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("check-verification-aggregate.py")


class CheckVerificationAggregateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.inventory = self.root / "owners.json"
        self.results = self.root / "results"
        self.results.mkdir()

    def write_inventory(self, owners: list[dict]) -> None:
        self.inventory.write_text(json.dumps({"include": owners}))

    def write_result(self, module: str, kind: str, status: str) -> None:
        path = self.results / f"verification-run-{module.replace('/', '_')}.json"
        path.write_text(
            json.dumps(
                {
                    "module": module,
                    "kind": kind,
                    "status": status,
                    "commands": [],
                    "reports": [],
                    "artifacts": [],
                }
            )
        )

    def run_checker(self, kinds: list[str], *, upstream: str | None = None) -> subprocess.CompletedProcess:
        argv = [
            sys.executable,
            str(SCRIPT),
            "--inventory",
            str(self.inventory),
            "--results-root",
            str(self.results),
        ]
        for kind in kinds:
            argv.extend(["--kind", kind])
        if upstream is not None:
            argv.extend(["--upstream-result", upstream])
        return subprocess.run(argv, capture_output=True, text=True, check=False)

    def test_incomplete_packaged_inventory_fails(self):
        self.write_inventory(
            [
                {
                    "module": "muse-packaged-e2e",
                    "artifact_id": "muse-packaged-e2e",
                    "kind": "platform",
                    "family": "platform",
                    "codegen": True,
                },
                {
                    "module": "muse-db-packaged-e2e",
                    "artifact_id": "muse-db-packaged-e2e",
                    "kind": "packaged",
                    "family": "db",
                    "codegen": True,
                },
                {
                    "module": "muse-connector-composition-e2e",
                    "artifact_id": "muse-connector-composition-e2e",
                    "kind": "composition",
                    "family": "composition",
                    "codegen": False,
                },
            ]
        )
        self.write_result("muse-packaged-e2e", "platform", "passed")
        self.write_result("muse-db-packaged-e2e", "packaged", "passed")
        result = self.run_checker(["platform", "packaged", "composition"])
        self.assertNotEqual(0, result.returncode)
        self.assertIn("missing result for muse-connector-composition-e2e", result.stderr)

    def test_complete_inventory_passes(self):
        self.write_inventory(
            [
                {
                    "module": "muse-file-plugin-it",
                    "artifact_id": "muse-file-plugin-it",
                    "kind": "plugin-realm",
                    "family": "file",
                    "codegen": False,
                },
                {
                    "module": "muse-sftp-plugin-it",
                    "artifact_id": "muse-sftp-plugin-it",
                    "kind": "plugin-realm",
                    "family": "sftp",
                    "codegen": False,
                },
            ]
        )
        self.write_result("muse-file-plugin-it", "plugin-realm", "passed")
        self.write_result("muse-sftp-plugin-it", "plugin-realm", "passed")
        result = self.run_checker(["plugin-realm"])
        self.assertEqual(0, result.returncode, result.stderr)

    def test_cancelled_upstream_fails(self):
        self.write_inventory(
            [
                {
                    "module": "muse-packaged-e2e",
                    "artifact_id": "muse-packaged-e2e",
                    "kind": "platform",
                    "family": "platform",
                    "codegen": True,
                }
            ]
        )
        self.write_result("muse-packaged-e2e", "platform", "passed")
        result = self.run_checker(["platform"], upstream="cancelled")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("cancelled", result.stderr)

    def test_failed_upstream_with_complete_passed_results_fails(self):
        self.write_inventory(
            [
                {
                    "module": "muse-packaged-e2e",
                    "artifact_id": "muse-packaged-e2e",
                    "kind": "platform",
                    "family": "platform",
                    "codegen": True,
                },
                {
                    "module": "muse-db-packaged-e2e",
                    "artifact_id": "muse-db-packaged-e2e",
                    "kind": "packaged",
                    "family": "db",
                    "codegen": True,
                },
            ]
        )
        self.write_result("muse-packaged-e2e", "platform", "passed")
        self.write_result("muse-db-packaged-e2e", "packaged", "passed")
        result = self.run_checker(["platform", "packaged"], upstream="failure")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("expected 'success'", result.stderr)


if __name__ == "__main__":
    unittest.main()
