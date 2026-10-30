from __future__ import annotations

import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("verification-owners.py")
SPEC = importlib.util.spec_from_file_location("verification_owners", SCRIPT)
assert SPEC and SPEC.loader
owners_module = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = owners_module
SPEC.loader.exec_module(owners_module)

VerificationOwner = owners_module.VerificationOwner


class VerificationOwnersTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        subprocess.run(["git", "init", "-q", self.root], check=True)

    def pom(
        self,
        module: str,
        artifact_id: str,
        *,
        modules: tuple[str, ...] = (),
        profile_modules: tuple[str, ...] = (),
        properties: dict[str, str] | None = None,
    ) -> None:
        directory = self.root / module
        directory.mkdir(parents=True, exist_ok=True)
        module_xml = "".join(f"<module>{value}</module>" for value in modules)
        profile_xml = "".join(f"<module>{value}</module>" for value in profile_modules)
        properties_xml = "".join(
            f"<{name}>{value}</{name}>" for name, value in (properties or {}).items()
        )
        (directory / "pom.xml").write_text(
            "<project xmlns='http://maven.apache.org/POM/4.0.0'>"
            "<modelVersion>4.0.0</modelVersion>"
            f"<artifactId>{artifact_id}</artifactId>"
            f"<properties>{properties_xml}</properties>"
            f"<modules>{module_xml}</modules>"
            f"<profiles><profile><id>optional</id><modules>{profile_xml}</modules>"
            "</profile></profiles>"
            "</project>"
        )

    def track(self) -> None:
        subprocess.run(["git", "-C", self.root, "add", "."], check=True)

    @staticmethod
    def metadata(kind: str, family: str, codegen: str | None = None) -> dict[str, str]:
        values = {
            "muse.verification.kind": kind,
            "muse.verification.family": family,
        }
        if codegen is not None:
            values["muse.verification.codegen"] = codegen
        return values

    def test_discovers_nested_reactor_and_file_realm_in_module_order(self):
        self.pom("", "root", modules=("nested", "muse-file-plugin-it"))
        self.pom("nested", "nested", modules=("muse-z-packaged-e2e",))
        self.pom(
            "nested/muse-z-packaged-e2e",
            "z-owner",
            properties=self.metadata("packaged", "z", "true"),
        )
        self.pom(
            "muse-file-plugin-it",
            "file-owner",
            properties=self.metadata("plugin-realm", "file"),
        )
        self.track()

        self.assertEqual(
            [
                VerificationOwner(
                    "muse-file-plugin-it", "file-owner", "plugin-realm", "file", False
                ),
                VerificationOwner(
                    "nested/muse-z-packaged-e2e", "z-owner", "packaged", "z", True
                ),
            ],
            owners_module.discover_owners(self.root),
        )

    def test_rejects_missing_unknown_and_expression_metadata(self):
        cases = (
            ({"muse.verification.kind": "packaged"}, "family"),
            (self.metadata("mystery", "file", "true"), "kind"),
            (
                {
                    **self.metadata("packaged", "file", "true"),
                    "muse.verification.extra": "unexpected",
                },
                "unknown ownership",
            ),
            (self.metadata("packaged", "${family}", "true"), "literal"),
            (self.metadata("packaged", "file", "${codegen}"), "literal"),
        )
        for index, (properties, message) in enumerate(cases):
            with self.subTest(properties=properties):
                root = f"case-{index}"
                self.pom(root, "reactor", modules=("muse-file-packaged-e2e",))
                self.pom(
                    f"{root}/muse-file-packaged-e2e",
                    f"owner-{index}",
                    properties=properties,
                )
        self.track()

        for index, (_, message) in enumerate(cases):
            with self.subTest(index=index):
                with self.assertRaisesRegex(ValueError, message):
                    owners_module.discover_owners(self.root / f"case-{index}")

    def test_rejects_duplicate_artifact_ids_and_roles(self):
        self.pom("", "root", modules=("muse-a-packaged-e2e", "muse-b-packaged-e2e"))
        self.pom(
            "muse-a-packaged-e2e",
            "duplicate",
            properties=self.metadata("packaged", "same", "false"),
        )
        self.pom(
            "muse-b-packaged-e2e",
            "duplicate",
            properties=self.metadata("packaged", "same", "false"),
        )
        self.track()

        with self.assertRaisesRegex(ValueError, "duplicate artifact"):
            owners_module.discover_owners(self.root)

        self.pom(
            "muse-b-packaged-e2e",
            "different",
            properties=self.metadata("packaged", "same", "false"),
        )
        self.track()
        with self.assertRaisesRegex(ValueError, "duplicate.*role"):
            owners_module.discover_owners(self.root)

    def test_rejects_empty_discovery(self):
        self.pom("", "root")
        self.track()

        with self.assertRaisesRegex(ValueError, "no verification owners"):
            owners_module.discover_owners(self.root)

    def test_rejects_profile_only_owner(self):
        self.pom("", "root", profile_modules=("muse-file-packaged-e2e",))
        self.pom(
            "muse-file-packaged-e2e",
            "file-owner",
            properties=self.metadata("packaged", "file", "true"),
        )
        self.track()

        with self.assertRaisesRegex(ValueError, "profile-only"):
            owners_module.discover_owners(self.root)

    def test_rejects_tagged_non_reactor_child_but_ignores_untagged_fixture(self):
        self.pom("", "root", modules=("owner",))
        self.pom(
            "owner",
            "owner",
            properties=self.metadata("plugin-realm", "platform"),
        )
        self.pom(
            "owner/src/it/tagged",
            "tagged-child",
            properties=self.metadata("plugin-realm", "file"),
        )
        self.pom("owner/src/it/plain", "plain-child")
        self.track()

        with self.assertRaisesRegex(ValueError, "not a reactor module"):
            owners_module.discover_owners(self.root)

        (self.root / "owner/src/it/tagged/pom.xml").unlink()
        subprocess.run(
            ["git", "-C", self.root, "rm", "--cached", "owner/src/it/tagged/pom.xml"],
            check=True,
            capture_output=True,
        )
        self.assertEqual(1, len(owners_module.discover_owners(self.root)))

    def test_ignores_untagged_non_reactor_with_conventional_artifact_id(self):
        self.pom("", "root", modules=("owner",))
        self.pom(
            "owner",
            "owner",
            properties=self.metadata("plugin-realm", "platform"),
        )
        self.pom("owner/src/it/plain", "muse-file-plugin-it")
        self.track()

        self.assertEqual(1, len(owners_module.discover_owners(self.root)))

    def test_conventional_reactor_owner_without_tags_fails_closed(self):
        self.pom("", "root", modules=("muse-new-packaged-e2e",))
        self.pom("muse-new-packaged-e2e", "muse-new-packaged-e2e")
        self.track()

        with self.assertRaisesRegex(ValueError, "missing local ownership metadata"):
            owners_module.discover_owners(self.root)

    def test_rejects_module_path_outside_repository(self):
        outside = self.root.parent / f"{self.root.name}-outside"
        self.addCleanup(lambda: shutil.rmtree(outside, ignore_errors=True))
        outside.mkdir()
        (outside / "pom.xml").write_text("<project/>")
        self.pom("", "root", modules=(f"../{outside.name}",))
        self.track()

        with self.assertRaisesRegex(ValueError, "outside repository"):
            owners_module.discover_owners(self.root)

    def test_family_pair_validation(self):
        packaged = VerificationOwner("pkg", "pkg", "packaged", "file", True)
        realm = VerificationOwner("realm", "realm", "plugin-realm", "file", False)
        runtime_only = VerificationOwner(
            "runtime", "runtime", "packaged", "sftp", False
        )
        platform = VerificationOwner(
            "platform", "platform", "platform", "platform", True
        )
        composition = VerificationOwner(
            "composition", "composition", "composition", "composition", False
        )

        owners_module.validate_family_pairs(
            [packaged, realm, runtime_only, platform, composition]
        )
        with self.assertRaisesRegex(ValueError, "file.*plugin-realm"):
            owners_module.validate_family_pairs([packaged])
        with self.assertRaisesRegex(ValueError, "file.*codegen-enabled packaged"):
            owners_module.validate_family_pairs([realm])
        with self.assertRaisesRegex(ValueError, "duplicate.*packaged.*file"):
            owners_module.validate_family_pairs(
                [
                    packaged,
                    VerificationOwner("pkg-2", "pkg-2", "packaged", "file", True),
                ]
            )

    def test_cli_emits_json_and_optional_pair_validation(self):
        self.pom("", "root", modules=("muse-file-packaged-e2e",))
        self.pom(
            "muse-file-packaged-e2e",
            "file-owner",
            properties=self.metadata("packaged", "file", "true"),
        )
        self.track()

        result = subprocess.run(
            [sys.executable, SCRIPT, self.root, "--format", "json"],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            {
                "include": [
                    {
                        "module": "muse-file-packaged-e2e",
                        "artifact_id": "file-owner",
                        "kind": "packaged",
                        "family": "file",
                        "codegen": True,
                    }
                ]
            },
            json.loads(result.stdout),
        )

        strict = subprocess.run(
            [
                sys.executable,
                SCRIPT,
                self.root,
                "--format",
                "json",
                "--require-family-pairs",
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertNotEqual(0, strict.returncode)
        self.assertIn("plugin-realm", strict.stderr)

    def test_synthetic_family_pair_discovered_without_workflow_lists(self):
        self.pom(
            "",
            "root",
            modules=("muse-zeta-packaged-e2e", "muse-zeta-plugin-it"),
        )
        self.pom(
            "muse-zeta-packaged-e2e",
            "muse-zeta-packaged-e2e",
            properties=self.metadata("packaged", "zeta", "true"),
        )
        self.pom(
            "muse-zeta-plugin-it",
            "muse-zeta-plugin-it",
            properties=self.metadata("plugin-realm", "zeta"),
        )
        self.track()

        owners = owners_module.discover_owners(self.root)
        owners_module.validate_family_pairs(owners)
        self.assertEqual(
            {
                ("packaged", "zeta", True),
                ("plugin-realm", "zeta", False),
            },
            {(owner.kind, owner.family, owner.codegen) for owner in owners},
        )

        # Missing mate fails under strict pairing.
        shutil.rmtree(self.root / "muse-zeta-plugin-it")
        self.pom("", "root", modules=("muse-zeta-packaged-e2e",))
        self.track()
        incomplete = owners_module.discover_owners(self.root)
        with self.assertRaisesRegex(ValueError, "zeta.*plugin-realm"):
            owners_module.validate_family_pairs(incomplete)

        # Unknown/malformed roles remain rejected.
        self.pom(
            "muse-zeta-packaged-e2e",
            "muse-zeta-packaged-e2e",
            properties=self.metadata("mystery", "zeta", "true"),
        )
        self.track()
        with self.assertRaisesRegex(ValueError, "kind"):
            owners_module.discover_owners(self.root)


if __name__ == "__main__":
    unittest.main()
