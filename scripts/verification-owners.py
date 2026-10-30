#!/usr/bin/env python3
"""Discover verification owners from tracked Maven reactor metadata."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from pathlib import Path


KINDS = {"platform", "packaged", "composition", "plugin-realm"}
PROPERTY_PREFIX = "muse.verification."
PROPERTY_NAMES = {
    "kind": f"{PROPERTY_PREFIX}kind",
    "family": f"{PROPERTY_PREFIX}family",
    "codegen": f"{PROPERTY_PREFIX}codegen",
}
FAMILY_PATTERN = re.compile(r"[a-z][a-z0-9-]*\Z")


@dataclass(frozen=True)
class VerificationOwner:
    module: str
    artifact_id: str
    kind: str
    family: str
    codegen: bool


def _tag(element: ET.Element) -> str:
    return element.tag.rsplit("}", 1)[-1]


def _children(element: ET.Element, name: str) -> list[ET.Element]:
    return [child for child in element if _tag(child) == name]


def _child(element: ET.Element, name: str) -> ET.Element | None:
    return next(iter(_children(element, name)), None)


def _text(element: ET.Element | None) -> str | None:
    if element is None or element.text is None:
        return None
    value = element.text.strip()
    return value or None


def _parse(path: Path) -> ET.Element:
    try:
        return ET.parse(path).getroot()
    except (OSError, ET.ParseError) as failure:
        raise ValueError(f"cannot read Maven POM {path}: {failure}") from failure


def _tracked_poms(root: Path) -> set[Path]:
    result = subprocess.run(
        ["git", "-C", str(root), "ls-files", "-z", "--", "**/pom.xml", "pom.xml"],
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        raise ValueError(
            f"cannot list tracked POMs under {root}: "
            f"{result.stderr.decode(errors='replace').strip()}"
        )
    return {
        (root / value.decode()).resolve()
        for value in result.stdout.split(b"\0")
        if value
    }


def _module_pom(root: Path, reactor_pom: Path, declaration: str) -> Path:
    candidate = reactor_pom.parent / declaration
    if candidate.name != "pom.xml":
        candidate /= "pom.xml"
    candidate = candidate.resolve()
    try:
        candidate.relative_to(root)
    except ValueError as failure:
        raise ValueError(
            f"module declaration {declaration!r} in {reactor_pom} points outside repository"
        ) from failure
    return candidate


def _declared_modules(
    root: Path, pom: Path, project: ET.Element
) -> tuple[list[Path], list[Path]]:
    active: list[Path] = []
    profiles: list[Path] = []
    modules = _child(project, "modules")
    if modules is not None:
        active.extend(
            _module_pom(root, pom, value)
            for value in (_text(module) for module in _children(modules, "module"))
            if value is not None
        )
    profile_container = _child(project, "profiles")
    if profile_container is not None:
        for profile in _children(profile_container, "profile"):
            profile_modules = _child(profile, "modules")
            if profile_modules is None:
                continue
            profiles.extend(
                _module_pom(root, pom, value)
                for value in (
                    _text(module) for module in _children(profile_modules, "module")
                )
                if value is not None
            )
    return active, profiles


def _reactor_members(root: Path, tracked: set[Path]) -> tuple[set[Path], set[Path]]:
    root_pom = root / "pom.xml"
    if root_pom.resolve() not in tracked:
        raise ValueError(f"root POM is not tracked: {root_pom}")
    active: set[Path] = set()
    profile_only: set[Path] = set()
    pending = [root_pom.resolve()]
    while pending:
        pom = pending.pop()
        if pom in active:
            continue
        if pom not in tracked or not pom.is_file():
            raise ValueError(f"reactor module POM is not a tracked file: {pom}")
        active.add(pom)
        child_modules, profile_modules = _declared_modules(root, pom, _parse(pom))
        pending.extend(child_modules)
        profile_only.update(profile_modules)
    profile_only.difference_update(active)
    return active, profile_only


def _local_properties(project: ET.Element) -> dict[str, str]:
    properties = _child(project, "properties")
    if properties is None:
        return {}
    return {
        _tag(property_element): _text(property_element) or ""
        for property_element in properties
    }


def _is_conventional_owner(artifact_id: str) -> bool:
    return (
        artifact_id in {
            "muse-packaged-e2e",
            "muse-plugin-it",
            "muse-connector-composition-e2e",
        }
        or re.fullmatch(r"muse-.+-packaged-e2e", artifact_id) is not None
        or re.fullmatch(r"muse-.+-plugin-it", artifact_id) is not None
    )


def _owner(root: Path, pom: Path, project: ET.Element) -> VerificationOwner:
    properties = _local_properties(project)
    unknown_properties = sorted(
        name
        for name in properties
        if name.startswith(PROPERTY_PREFIX) and name not in PROPERTY_NAMES.values()
    )
    if unknown_properties:
        raise ValueError(
            f"{pom}: unknown ownership properties: {', '.join(unknown_properties)}"
        )
    values: dict[str, str] = {}
    for short_name in ("kind", "family"):
        property_name = PROPERTY_NAMES[short_name]
        value = properties.get(property_name)
        if not value:
            raise ValueError(f"{pom}: missing local ownership metadata {property_name}")
        if "${" in value:
            raise ValueError(f"{pom}: {property_name} must be a literal value")
        values[short_name] = value

    kind = values["kind"]
    family = values["family"]
    if kind not in KINDS:
        raise ValueError(f"{pom}: unknown verification kind {kind!r}")
    if not FAMILY_PATTERN.fullmatch(family):
        raise ValueError(f"{pom}: family must be a lowercase literal ID")
    if kind == "platform" and family != "platform":
        raise ValueError(f"{pom}: platform owner must use family 'platform'")
    if kind == "composition" and family != "composition":
        raise ValueError(f"{pom}: composition owner must use family 'composition'")

    codegen_value = properties.get(PROPERTY_NAMES["codegen"])
    if codegen_value is None and kind != "plugin-realm":
        raise ValueError(
            f"{pom}: missing local ownership metadata {PROPERTY_NAMES['codegen']}"
        )
    if codegen_value is not None:
        if "${" in codegen_value:
            raise ValueError(
                f"{pom}: {PROPERTY_NAMES['codegen']} must be a literal value"
            )
        if codegen_value not in {"true", "false"}:
            raise ValueError(
                f"{pom}: {PROPERTY_NAMES['codegen']} must be 'true' or 'false'"
            )

    artifact_id = _text(_child(project, "artifactId"))
    if artifact_id is None or "${" in artifact_id:
        raise ValueError(f"{pom}: owner requires a literal local artifactId")
    module_directory = pom.parent.relative_to(root)
    module = "." if module_directory == Path(".") else module_directory.as_posix()
    return VerificationOwner(
        module=module,
        artifact_id=artifact_id,
        kind=kind,
        family=family,
        codegen=codegen_value == "true",
    )


def _validate_unique(owners: list[VerificationOwner]) -> None:
    artifact_ids: set[str] = set()
    roles: set[tuple[str, str]] = set()
    for owner in owners:
        if owner.artifact_id in artifact_ids:
            raise ValueError(f"duplicate artifact ID among owners: {owner.artifact_id}")
        artifact_ids.add(owner.artifact_id)
        role = (owner.kind, owner.family)
        if role in roles:
            raise ValueError(
                f"duplicate verification role {owner.kind}/{owner.family}"
            )
        roles.add(role)


def discover_owners(root: Path) -> list[VerificationOwner]:
    root = root.resolve()
    tracked = _tracked_poms(root)
    active, profile_only = _reactor_members(root, tracked)
    owners: list[VerificationOwner] = []

    for pom in sorted(tracked):
        project = _parse(pom)
        properties = _local_properties(project)
        tagged = any(name.startswith(PROPERTY_PREFIX) for name in properties)
        artifact_id = _text(_child(project, "artifactId")) or ""
        conventional = _is_conventional_owner(artifact_id)
        reactor_candidate = pom in active or pom in profile_only
        if not tagged and (not reactor_candidate or not conventional):
            continue
        if pom in profile_only:
            raise ValueError(f"{pom}: verification owner is profile-only")
        if pom not in active:
            raise ValueError(f"{pom}: tagged verification owner is not a reactor module")
        if not tagged:
            raise ValueError(f"{pom}: conventional owner missing local ownership metadata")
        owners.append(_owner(root, pom, project))

    if not owners:
        raise ValueError("no verification owners discovered")
    owners.sort(key=lambda owner: owner.module)
    _validate_unique(owners)
    return owners


def validate_family_pairs(owners: list[VerificationOwner]) -> None:
    _validate_unique(owners)
    packaged = {
        owner.family: owner
        for owner in owners
        if owner.kind == "packaged" and owner.family not in {"platform", "composition"}
    }
    realms = {
        owner.family: owner
        for owner in owners
        if owner.kind == "plugin-realm" and owner.family != "platform"
    }
    for family, owner in packaged.items():
        if owner.codegen and family not in realms:
            raise ValueError(
                f"family {family} codegen-enabled packaged owner requires a plugin-realm owner"
            )
    for family in realms:
        owner = packaged.get(family)
        if owner is None or not owner.codegen:
            raise ValueError(
                f"family {family} plugin-realm owner requires a codegen-enabled packaged owner"
            )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("repository_root", type=Path)
    parser.add_argument("--format", choices=("json",), required=True)
    parser.add_argument("--require-family-pairs", action="store_true")
    arguments = parser.parse_args()

    try:
        owners = discover_owners(arguments.repository_root)
        if arguments.require_family_pairs:
            validate_family_pairs(owners)
    except ValueError as failure:
        print(f"Verification owner discovery failed: {failure}", file=sys.stderr)
        raise SystemExit(1) from failure

    print(json.dumps({"include": [asdict(owner) for owner in owners]}))


if __name__ == "__main__":
    main()
