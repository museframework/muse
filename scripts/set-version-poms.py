#!/usr/bin/env python3
"""Update Muse Maven coordinates while preserving POM formatting."""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

MUSE_GROUP = "dev.museframework"


def is_muse_group(group: str | None) -> bool:
    return bool(group and (group == MUSE_GROUP or group.startswith(MUSE_GROUP + ".")))


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def child_text(element: ET.Element, name: str) -> str | None:
    return next(
        (child.text.strip() for child in element if local_name(child.tag) == name and child.text),
        None,
    )


def child_element(element: ET.Element, name: str) -> ET.Element | None:
    return next((child for child in element if local_name(child.tag) == name), None)


def read_xml(content: str, path: Path) -> ET.Element:
    try:
        root = ET.fromstring(content)
    except ET.ParseError as exc:
        raise ValueError(f"{path}: invalid POM XML: {exc}") from exc
    if local_name(root.tag) != "project":
        raise ValueError(f"{path}: expected a Maven <project> root")
    return root


def current_version(bom_path: Path) -> str:
    if not bom_path.is_file():
        raise ValueError(f"Missing Muse BOM: {bom_path}")
    root = read_xml(bom_path.read_bytes().decode("utf-8"), bom_path)
    if child_text(root, "groupId") != MUSE_GROUP:
        raise ValueError(f"{bom_path}: unexpected BOM groupId")
    version = child_text(root, "version")
    properties = child_element(root, "properties")
    property_version = child_text(properties, "muse.version") if properties is not None else None
    if not version or property_version != version:
        raise ValueError(f"{bom_path}: BOM version and muse.version must match")
    return version


def allowed_occurrences(root: ET.Element, old: str) -> int:
    """Count version text nodes belonging to Muse, not external coordinates."""
    parents = {child: parent for parent in root.iter() for child in parent}
    parent_pom = child_element(root, "parent")
    project_group = child_text(root, "groupId") or (
        child_text(parent_pom, "groupId") if parent_pom is not None else None
    )
    count = 0
    for node in root.iter():
        if not node.text or old not in node.text:
            continue
        parent = parents.get(node)
        if parent is None:
            continue
        node_name = local_name(node.tag)
        parent_name = local_name(parent.tag)
        allowed = (
            (node_name == "muse.version" and parent_name == "properties")
            or (
                node_name == "version"
                and (
                    (parent is root and is_muse_group(project_group))
                    or (
                        parent_name in {"parent", "dependency", "plugin", "extension"}
                        and is_muse_group(child_text(parent, "groupId"))
                    )
                )
            )
        )
        if not allowed or node.text.strip() != old:
            continue
        count += 1
    return count


def plan_changes(root_path: Path, new_version: str) -> tuple[str, list[tuple[Path, str]]]:
    old_version = current_version(root_path / "muse-bom" / "pom.xml")
    root_pom = root_path / "pom.xml"
    root_xml = read_xml(root_pom.read_bytes().decode("utf-8"), root_pom)
    parent = child_element(root_xml, "parent")
    if parent is None or child_text(parent, "groupId") != MUSE_GROUP or child_text(parent, "version") != old_version:
        raise ValueError(f"{root_pom}: root parent version does not match BOM ({old_version})")

    indexed = subprocess.run(
        ["git", "-C", str(root_path), "ls-files", "-z"],
        check=True, capture_output=True,
    ).stdout
    poms = sorted(
        root_path / Path(os.fsdecode(item))
        for item in indexed.split(b"\0") if item and Path(os.fsdecode(item)).name == "pom.xml"
    )
    if not poms or root_pom not in poms:
        raise ValueError("No tracked Maven POMs found; run from a Git checkout")

    updates: list[tuple[Path, str]] = []
    for path in poms:
        content = path.read_bytes().decode("utf-8")
        if old_version not in content:
            continue
        xml = read_xml(content, path)
        expected = allowed_occurrences(xml, old_version)
        actual = content.count(old_version)
        if actual != expected:
            raise ValueError(
                f"{path}: {actual} occurrences of {old_version}, but only {expected} "
                "are recognized Muse coordinates; aborting without changes"
            )
        if new_version != old_version:
            updates.append((path, content.replace(old_version, new_version)))
    return old_version, updates


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("repository", type=Path)
    parser.add_argument("version")
    args = parser.parse_args()
    try:
        old, changes = plan_changes(args.repository.resolve(), args.version)
        if not changes:
            print(f"Muse is already at {old}; nothing to change.")
            return 0
        action = "Would update" if args.dry_run else "Updating"
        print(f"{action} {len(changes)} POMs: {old} -> {args.version}")
        for path, content in changes:
            if not args.dry_run:
                path.write_bytes(content.encode("utf-8"))
            print(f"  {path.relative_to(args.repository.resolve())}")
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError) as exc:
        print(f"set-version: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
