#!/usr/bin/env python3
"""Regression tests for the Muse version bump command."""

import subprocess
import shutil
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("set-version.sh")
OLD = "0.3.0-SNAPSHOT"
NEW = "0.4.0-SNAPSHOT"


def pom(body: str) -> str:
    return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            '<project xmlns="http://maven.apache.org/POM/4.0.0">\n'
            f"{body}\n</project>\n")


class SetVersionTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.write("muse-bom/pom.xml", pom(f"""
  <groupId>dev.museframework</groupId>
  <artifactId>muse-bom</artifactId>
  <version>{OLD}</version>
  <properties><muse.version>{OLD}</muse.version></properties>"""))
        self.write("pom.xml", pom(f"""
  <parent><groupId>dev.museframework</groupId><artifactId>muse-bom</artifactId><version>{OLD}</version></parent>
  <artifactId>muse-parent</artifactId>"""))
        self.write("muse-engine/pom.xml", pom(f"""
  <parent><groupId>dev.museframework</groupId><artifactId>muse-parent</artifactId><version>{OLD}</version></parent>
  <artifactId>muse-engine</artifactId>
  <dependencies>
    <dependency><groupId>dev.museframework.test</groupId><artifactId>muse-dsl</artifactId><version>{OLD}</version></dependency>
    <dependency><groupId>com.other</groupId><artifactId>lib</artifactId><version>1.2.3</version></dependency>
  </dependencies>"""))
        self.write("muse-http/pom.xml", pom(f"""
  <parent><groupId>dev.museframework</groupId><artifactId>muse-parent</artifactId><version>{OLD}</version></parent>
  <properties><muse.version>{OLD}</muse.version></properties>"""))
        self.write("muse-maven-plugin/muse-plugin-it/src/it/basic/pom.xml", pom(f"""
  <groupId>dev.museframework.it</groupId><artifactId>fixture</artifactId><version>{OLD}</version>
  <build><plugins><plugin><groupId>dev.museframework.maven</groupId><artifactId>muse-maven-plugin</artifactId><version>{OLD}</version></plugin></plugins></build>
  <dependencies><dependency><groupId>dev.museframework</groupId><artifactId>muse-dsl</artifactId><version>{OLD}</version></dependency></dependencies>"""))
        self.write("muse-maven-plugin/muse-plugin-it/src/it/odd-version/pom.xml", pom(f"""
  <groupId>io.muse.it</groupId><artifactId>odd-version</artifactId><version>{OLD}-SNAPSHOT</version>
  <dependencies><dependency><groupId>dev.museframework</groupId><artifactId>muse-engine</artifactId><version>{OLD}</version></dependency></dependencies>"""))
        self.write("examples/mule-original/pom.xml", pom("""
  <groupId>org.mule</groupId><artifactId>original</artifactId><version>1.0.0-SNAPSHOT</version>"""))
        self.root.joinpath("scripts").mkdir(parents=True, exist_ok=True)
        for script in ("set-version.sh", "set-version-poms.py"):
            shutil.copy2(SCRIPT.with_name(script), self.root / "scripts" / script)
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "add", "."], check=True)

    def write(self, name: str, content: str) -> None:
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def run_script(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(["bash", str(self.root / "scripts" / "set-version.sh"), *args], cwd="/", capture_output=True, text=True)

    def test_updates_all_tracked_muse_poms_without_changing_external_versions(self) -> None:
        result = self.run_script(NEW)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Updating 6 POMs", result.stdout)
        for file in ["pom.xml", "muse-bom/pom.xml", "muse-engine/pom.xml",
                     "muse-http/pom.xml", "muse-maven-plugin/muse-plugin-it/src/it/basic/pom.xml"]:
            data = (self.root / file).read_text()
            self.assertNotIn(OLD, data)
            self.assertIn(NEW, data)
        unusual = (self.root / "muse-maven-plugin/muse-plugin-it/src/it/odd-version/pom.xml").read_text()
        self.assertIn(f"<version>{OLD}-SNAPSHOT</version>", unusual)
        self.assertIn(f"<artifactId>muse-engine</artifactId><version>{NEW}</version>", unusual)
        self.assertIn("1.0.0-SNAPSHOT", (self.root / "examples/mule-original/pom.xml").read_text())
        self.assertIn("<version>1.2.3</version>", (self.root / "muse-engine/pom.xml").read_text())
        self.assertEqual(self.run_script(NEW).returncode, 0)

    def test_dry_run_preserves_poms(self) -> None:
        before = (self.root / "muse-bom/pom.xml").read_bytes()
        result = self.run_script("--dry-run", NEW)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Would update 6 POMs", result.stdout)
        self.assertEqual((self.root / "muse-bom/pom.xml").read_bytes(), before)

    def test_invalid_version_is_rejected(self) -> None:
        result = self.run_script("../0.4.0")
        self.assertEqual(result.returncode, 2)
        self.assertIn("Invalid Muse version", result.stderr)

    def test_unrecognized_old_version_occurrence_aborts_without_writes(self) -> None:
        path = self.root / "muse-engine/pom.xml"
        path.write_text(path.read_text().replace("<version>1.2.3</version>",
                  f"<version>{OLD}</version>"), encoding="utf-8")
        result = self.run_script(NEW)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("only", result.stderr)
        self.assertIn(OLD, (self.root / "muse-bom/pom.xml").read_text())

    def test_mismatched_bom_property_aborts_without_writes(self) -> None:
        path = self.root / "muse-bom/pom.xml"
        path.write_text(path.read_text().replace(f"<muse.version>{OLD}</muse.version>",
                  "<muse.version>0.2.0-SNAPSHOT</muse.version>"), encoding="utf-8")
        result = self.run_script(NEW)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("must match", result.stderr)
        self.assertIn(OLD, (self.root / "pom.xml").read_text())


if __name__ == "__main__":
    unittest.main()
