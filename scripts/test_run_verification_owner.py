from __future__ import annotations

import importlib.util
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace


SCRIPT = Path(__file__).with_name("run-verification-owner.py")
OWNERS_SCRIPT = Path(__file__).with_name("verification-owners.py")


def load_module(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


owners_module = load_module(OWNERS_SCRIPT, "verification_owners_for_runner_tests")
VerificationOwner = owners_module.VerificationOwner


class RunVerificationOwnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "repo root"
        self.root.mkdir()
        self.scratch = Path(self.temp.name) / "scratch dir"
        self.scratch.mkdir()
        self.repository = Path(self.temp.name) / "outer repo"
        self.repository.mkdir()
        subprocess.run(["git", "init", "-q", self.root], check=True)
        self.runner = load_module(SCRIPT, "run_verification_owner")

    def write_pom(
        self,
        module: str,
        artifact_id: str,
        *,
        kind: str | None = None,
        family: str | None = None,
        codegen: str | None = "true",
        plugin_deps: tuple[str, ...] = (),
        invoker_local_repo: str | None = None,
        modules: tuple[str, ...] = (),
    ) -> Path:
        directory = self.root / module
        directory.mkdir(parents=True, exist_ok=True)
        properties: dict[str, str] = {}
        if kind is not None and family is not None:
            properties = {
                "muse.verification.kind": kind,
                "muse.verification.family": family,
            }
            if codegen is not None:
                properties["muse.verification.codegen"] = codegen
        properties_xml = "".join(f"<{k}>{v}</{k}>" for k, v in properties.items())
        plugin_dep_xml = "".join(
            "<dependency><groupId>dev.museframework</groupId>"
            f"<artifactId>{dep}</artifactId><version>0.1.0</version></dependency>"
            for dep in plugin_deps
        )
        muse_plugin = ""
        if plugin_deps:
            muse_plugin = (
                "<plugin><groupId>dev.museframework.maven</groupId>"
                "<artifactId>muse-maven-plugin</artifactId>"
                f"<dependencies>{plugin_dep_xml}</dependencies></plugin>"
            )
        invoker = ""
        if invoker_local_repo is not None:
            invoker = (
                "<plugin><groupId>org.apache.maven.plugins</groupId>"
                "<artifactId>maven-invoker-plugin</artifactId>"
                f"<configuration><localRepositoryPath>{invoker_local_repo}"
                "</localRepositoryPath></configuration></plugin>"
            )
        modules_xml = "".join(f"<module>{value}</module>" for value in modules)
        (directory / "pom.xml").write_text(
            "<project xmlns='http://maven.apache.org/POM/4.0.0'>"
            f"<artifactId>{artifact_id}</artifactId>"
            f"<properties>{properties_xml}</properties>"
            f"<modules>{modules_xml}</modules>"
            f"<build><plugins>{muse_plugin}{invoker}</plugins></build>"
            "</project>"
        )
        return directory

    def track(self) -> None:
        subprocess.run(["git", "-C", self.root, "add", "."], check=True)

    def write_failsafe(
        self,
        module_dir: Path,
        suite: str,
        cases: list[str],
        *,
        skipped: frozenset[str] = frozenset(),
    ) -> Path:
        reports = module_dir / "target" / "failsafe-reports"
        reports.mkdir(parents=True, exist_ok=True)
        path = reports / f"TEST-{suite}.xml"
        cases_xml = []
        for name in cases:
            if name in skipped:
                cases_xml.append(
                    f'<testcase classname="{suite}" name="{name}">'
                    "<skipped/></testcase>"
                )
            else:
                cases_xml.append(f'<testcase classname="{suite}" name="{name}"/>')
        path.write_text(
            f'<testsuite name="{suite}" tests="{len(cases)}" failures="0" '
            f'errors="0" skipped="{len(skipped)}">{"".join(cases_xml)}</testsuite>'
        )
        return reports

    def write_kotlin_suite(
        self,
        module_dir: Path,
        package: str,
        class_name: str,
        methods: list[str],
    ) -> None:
        source_dir = module_dir / "src" / "test" / "kotlin" / package.replace(".", "/")
        source_dir.mkdir(parents=True, exist_ok=True)
        methods_src = "\n".join(
            f'    @Test\n    fun `{name}`() {{}}\n' for name in methods
        )
        (source_dir / f"{class_name}.kt").write_text(
            f"package {package}\n\nimport org.junit.jupiter.api.Test\n\n"
            f"class {class_name} {{\n{methods_src}}}\n"
        )

    def write_invoker_fixture(
        self,
        module_dir: Path,
        name: str,
        *,
        build_result: str = "success",
    ) -> None:
        fixture = module_dir / "src" / "it" / name
        fixture.mkdir(parents=True, exist_ok=True)
        (fixture / "pom.xml").write_text("<project><artifactId>x</artifactId></project>")
        if build_result != "success":
            (fixture / "invoker.properties").write_text(
                f"invoker.buildResult={build_result}\n"
            )
        reports = module_dir / "target" / "invoker-reports"
        reports.mkdir(parents=True, exist_ok=True)
        (reports / f"BUILD-{name}.xml").write_text(
            f'<build-job project="target/it/{name}/pom.xml" result="success"/>'
        )

    def test_profile_selection_by_role(self):
        packaged = VerificationOwner(
            "muse-sftp-packaged-e2e", "muse-sftp-packaged-e2e", "packaged", "sftp", True
        )
        realm = VerificationOwner(
            "muse-file-plugin-it", "muse-file-plugin-it", "plugin-realm", "file", False
        )
        platform = VerificationOwner(
            "muse-packaged-e2e", "muse-packaged-e2e", "platform", "platform", True
        )
        composition = VerificationOwner(
            "muse-connector-composition-e2e",
            "muse-connector-composition-e2e",
            "composition",
            "composition",
            False,
        )

        for owner in (packaged, platform, composition):
            argv = self.runner.verify_argv(owner, self.repository)
            self.assertIn("-Pe2e", argv)
            self.assertNotIn("-Pplugin-it", argv)
            self.assertNotIn("-DskipTests", argv)

        realm_argv = self.runner.verify_argv(realm, self.repository)
        self.assertIn("-Pplugin-it", realm_argv)
        self.assertIn("-DskipTests", realm_argv)
        self.assertNotIn("-Pe2e", realm_argv)

    def test_prepare_includes_plugin_provider_seeds_without_connector_switch(self):
        owner = VerificationOwner(
            "muse-sftp-packaged-e2e", "muse-sftp-packaged-e2e", "packaged", "sftp", True
        )
        module = self.write_pom(
            "muse-sftp-packaged-e2e",
            "muse-sftp-packaged-e2e",
            kind="packaged",
            family="sftp",
            plugin_deps=("muse-http-babelfish-mappers", "muse-sftp-babelfish-mappers"),
        )
        argv = self.runner.prepare_argv(owner, self.root, self.repository)
        self.assertEqual("mvn", argv[0])
        self.assertIn("clean", argv)
        self.assertIn("install", argv)
        self.assertIn("-DskipTests", argv)
        joined = " ".join(argv)
        self.assertIn(":muse-sftp-packaged-e2e", joined)
        self.assertIn(":muse-http-babelfish-mappers", joined)
        self.assertIn(":muse-sftp-babelfish-mappers", joined)
        self.assertIn(f"-Dmaven.repo.local={self.repository}", argv)
        self.assertNotIn("sftp", [part for part in argv if part.startswith("-Dfamily")])
        source = SCRIPT.read_text()
        self.assertNotRegex(source, r'if\s+owner\.family\s*==\s*[\'"]sftp[\'"]')
        self.assertIsInstance(module, Path)

    def test_path_with_spaces_uses_argv_arrays(self):
        self.write_pom("", "root", modules=("owner mod",))
        self.write_pom(
            "owner mod",
            "muse-packaged-e2e",
            kind="platform",
            family="platform",
            plugin_deps=("muse-http-babelfish-mappers",),
        )
        self.track()
        module_dir = self.root / "owner mod"
        self.write_kotlin_suite(
            module_dir,
            "dev.example",
            "GeneratedKotlinCompilationIT",
            ["generated configuration and application classes load from compiled bytecode"],
        )
        self.write_kotlin_suite(
            module_dir,
            "dev.example",
            "PackagedApplicationE2ETest",
            ["packaged simple echo preserves request payload"],
        )
        self.write_failsafe(
            module_dir,
            "dev.example.GeneratedKotlinCompilationIT",
            ["generated configuration and application classes load from compiled bytecode"],
        )
        self.write_failsafe(
            module_dir,
            "dev.example.PackagedApplicationE2ETest",
            ["packaged simple echo preserves request payload"],
        )

        calls: list[list[str]] = []

        def fake_run(argv, **kwargs):
            calls.append(list(argv))
            return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")

        result = self.runner.run_owner(
            owners_module.discover_owners(self.root)[0],
            self.root,
            self.scratch,
            repository=self.repository,
            execute=fake_run,
        )
        self.assertEqual("passed", result.status)
        self.assertTrue(calls)
        for argv in calls:
            self.assertIsInstance(argv, list)
            self.assertFalse(any(" && " in part for part in argv))
            self.assertTrue(any("outer repo" in part for part in argv))

    def test_missing_owner_fails(self):
        self.write_pom("", "root", modules=("muse-packaged-e2e",))
        self.write_pom(
            "muse-packaged-e2e",
            "muse-packaged-e2e",
            kind="platform",
            family="platform",
        )
        self.track()
        with self.assertRaisesRegex(ValueError, "not a discovered verification owner"):
            self.runner.resolve_owner(self.root / "missing", self.root)

    def test_native_maven_failure_fails_run(self):
        self.write_pom("", "root", modules=("muse-packaged-e2e",))
        self.write_pom(
            "muse-packaged-e2e",
            "muse-packaged-e2e",
            kind="platform",
            family="platform",
        )
        self.track()
        owner = owners_module.discover_owners(self.root)[0]

        def fake_run(argv, **kwargs):
            return SimpleNamespace(returncode=1, stdout=b"", stderr=b"boom")

        result = self.runner.run_owner(
            owner,
            self.root,
            self.scratch,
            repository=self.repository,
            execute=fake_run,
        )
        self.assertEqual("failed", result.status)
        self.assertTrue(result.commands)
        self.assertEqual(1, result.commands[0].exit_code)

    def test_successful_maven_with_missing_reports_fails(self):
        self.write_pom("", "root", modules=("muse-packaged-e2e",))
        module_dir = self.write_pom(
            "muse-packaged-e2e",
            "muse-packaged-e2e",
            kind="platform",
            family="platform",
        )
        self.write_kotlin_suite(
            module_dir,
            "dev.example",
            "PackagedApplicationE2ETest",
            ["packaged simple echo preserves request payload"],
        )
        self.track()
        owner = owners_module.discover_owners(self.root)[0]

        def fake_run(argv, **kwargs):
            return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")

        result = self.runner.run_owner(
            owner,
            self.root,
            self.scratch,
            repository=self.repository,
            execute=fake_run,
        )
        self.assertEqual("failed", result.status)

    def test_e2e_without_compilation_it_fails_report_gate(self):
        self.write_pom("", "root", modules=("muse-packaged-e2e",))
        module_dir = self.write_pom(
            "muse-packaged-e2e",
            "muse-packaged-e2e",
            kind="platform",
            family="platform",
        )
        self.write_kotlin_suite(
            module_dir,
            "dev.example",
            "GeneratedKotlinCompilationIT",
            ["generated configuration and application classes load from compiled bytecode"],
        )
        self.write_kotlin_suite(
            module_dir,
            "dev.example",
            "PackagedApplicationE2ETest",
            ["packaged simple echo preserves request payload"],
        )
        self.write_failsafe(
            module_dir,
            "dev.example.PackagedApplicationE2ETest",
            ["packaged simple echo preserves request payload"],
        )
        self.track()
        owner = owners_module.discover_owners(self.root)[0]

        def fake_run(argv, **kwargs):
            return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")

        result = self.runner.run_owner(
            owner,
            self.root,
            self.scratch,
            repository=self.repository,
            execute=fake_run,
        )
        self.assertEqual("failed", result.status)

    def test_negative_invoker_child_expected_failure_passes(self):
        self.write_pom("", "root", modules=("muse-file-plugin-it",))
        module_dir = self.write_pom(
            "muse-file-plugin-it",
            "muse-file-plugin-it",
            kind="plugin-realm",
            family="file",
            codegen=None,
            invoker_local_repo="${project.build.directory}/local-repo",
        )
        self.write_invoker_fixture(module_dir, "ok-fixture")
        self.write_invoker_fixture(module_dir, "bad-fixture", build_result="failure")
        self.track()
        owner = owners_module.discover_owners(self.root)[0]

        def fake_run(argv, **kwargs):
            return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")

        result = self.runner.run_owner(
            owner,
            self.root,
            self.scratch,
            repository=self.repository,
            execute=fake_run,
        )
        self.assertEqual("passed", result.status)

    def test_scratch_logs_survive_clean_and_are_copied(self):
        self.write_pom("", "root", modules=("muse-packaged-e2e",))
        module_dir = self.write_pom(
            "muse-packaged-e2e",
            "muse-packaged-e2e",
            kind="platform",
            family="platform",
        )
        self.write_kotlin_suite(
            module_dir,
            "dev.example",
            "GeneratedKotlinCompilationIT",
            ["generated configuration and application classes load from compiled bytecode"],
        )
        self.write_kotlin_suite(
            module_dir,
            "dev.example",
            "PackagedApplicationE2ETest",
            ["packaged simple echo preserves request payload"],
        )
        self.track()
        owner = owners_module.discover_owners(self.root)[0]
        sentinel = module_dir / "target" / "pre-existing-sentinel"
        sentinel.parent.mkdir(parents=True, exist_ok=True)
        sentinel.write_text("sentinel\n")
        rmtree_calls: list[Path] = []
        original_rmtree = shutil.rmtree

        def tracking_rmtree(path, *args, **kwargs):
            rmtree_calls.append(Path(path))
            return original_rmtree(path, *args, **kwargs)

        shutil.rmtree = tracking_rmtree
        self.addCleanup(lambda: setattr(shutil, "rmtree", original_rmtree))

        def execute(argv, **kwargs):
            log_path = Path(kwargs["log_path"])
            target = module_dir / "target"
            if "clean" in argv and target.exists():
                shutil.rmtree(target)
            body = f"log-for-{'prepare' if 'install' in argv else 'verify'}\n"
            log_path.write_text(body)
            # Recreate target content after clean so the next clean has something to remove.
            (module_dir / "target" / "prepare-marker").parent.mkdir(parents=True, exist_ok=True)
            (module_dir / "target" / "prepare-marker").write_text("prepare-marker\n")
            if "verify" in argv:
                child = module_dir / "target" / "packaged-e2e"
                child.mkdir(parents=True, exist_ok=True)
                (child / "application.log").write_text("child-application\n")
                self.write_failsafe(
                    module_dir,
                    "dev.example.GeneratedKotlinCompilationIT",
                    [
                        "generated configuration and application classes load "
                        "from compiled bytecode"
                    ],
                )
                self.write_failsafe(
                    module_dir,
                    "dev.example.PackagedApplicationE2ETest",
                    ["packaged simple echo preserves request payload"],
                )
            return SimpleNamespace(returncode=0, stdout=b"ok", stderr=b"")

        result = self.runner.run_owner(
            owner,
            self.root,
            self.scratch,
            repository=self.repository,
            execute=execute,
        )
        self.assertEqual("passed", result.status)
        self.assertGreaterEqual(len(rmtree_calls), 2)
        self.assertFalse(sentinel.exists())
        scratch_resolved = self.scratch.resolve()
        bodies = []
        for command in result.commands:
            log_path = Path(command.log_path)
            self.assertTrue(log_path.is_file())
            self.assertEqual(scratch_resolved, log_path.resolve().parent)
            bodies.append(log_path.read_text())
        self.assertEqual(["log-for-prepare\n", "log-for-verify\n"], bodies)
        verification_dir = module_dir / "target" / "verification"
        copied = {
            path.name: path.read_text()
            for path in verification_dir.glob("*.log")
        }
        self.assertIn("01-muse-packaged-e2e-prepare.log", copied)
        self.assertIn("02-muse-packaged-e2e-verify.log", copied)
        self.assertEqual("log-for-prepare\n", copied["01-muse-packaged-e2e-prepare.log"])
        self.assertEqual("log-for-verify\n", copied["02-muse-packaged-e2e-verify.log"])
        child_copy = verification_dir / "child-logs" / "packaged-e2e" / "application.log"
        self.assertTrue(child_copy.is_file())
        self.assertEqual("child-application\n", child_copy.read_text())

    def test_successful_maven_with_skipped_report_fails(self):
        self.write_pom("", "root", modules=("muse-packaged-e2e",))
        module_dir = self.write_pom(
            "muse-packaged-e2e",
            "muse-packaged-e2e",
            kind="platform",
            family="platform",
        )
        self.write_kotlin_suite(
            module_dir,
            "dev.example",
            "GeneratedKotlinCompilationIT",
            ["generated configuration and application classes load from compiled bytecode"],
        )
        self.write_kotlin_suite(
            module_dir,
            "dev.example",
            "PackagedApplicationE2ETest",
            ["packaged simple echo preserves request payload"],
        )
        self.write_failsafe(
            module_dir,
            "dev.example.GeneratedKotlinCompilationIT",
            ["generated configuration and application classes load from compiled bytecode"],
        )
        self.write_failsafe(
            module_dir,
            "dev.example.PackagedApplicationE2ETest",
            ["packaged simple echo preserves request payload"],
            skipped=frozenset({"packaged simple echo preserves request payload"}),
        )
        self.track()
        owner = owners_module.discover_owners(self.root)[0]

        def execute(argv, **kwargs):
            return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")

        result = self.runner.run_owner(
            owner,
            self.root,
            self.scratch,
            repository=self.repository,
            execute=execute,
        )
        self.assertEqual("failed", result.status)
        self.assertTrue(
            any(command.argv[:1] == ["report-gate"] for command in result.commands)
        )

    def test_forbidden_shared_private_invoker_repository(self):
        self.write_pom("", "root", modules=("muse-file-plugin-it",))
        self.write_pom(
            "muse-file-plugin-it",
            "muse-file-plugin-it",
            kind="plugin-realm",
            family="file",
            codegen=None,
            invoker_local_repo="/shared/invoker-repo",
        )
        self.track()
        owner = owners_module.discover_owners(self.root)[0]
        with self.assertRaisesRegex(ValueError, "private Invoker repository"):
            self.runner.validate_owner_fixture(owner, self.root)

    def test_cli_emits_result_json_and_nonzero_on_failure(self):
        self.write_pom("", "root", modules=("muse-packaged-e2e",))
        self.write_pom(
            "muse-packaged-e2e",
            "muse-packaged-e2e",
            kind="platform",
            family="platform",
        )
        self.track()
        missing = subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                str(self.root / "missing-owner"),
                "--repository",
                str(self.repository),
                "--scratch",
                str(self.scratch),
                "--repository-root",
                str(self.root),
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertNotEqual(0, missing.returncode)
        self.assertIn("not a discovered verification owner", missing.stderr)


if __name__ == "__main__":
    unittest.main()
