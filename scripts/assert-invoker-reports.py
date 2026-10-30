#!/usr/bin/env python3
"""Require one successful Maven Invoker build job per local fixture."""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def expected_fixtures(module: Path) -> dict[str, str]:
    fixture_root = module / "src" / "it"
    fixtures: dict[str, str] = {}
    if fixture_root.is_dir():
        for pom in sorted(fixture_root.glob("*/pom.xml")):
            properties = pom.with_name("invoker.properties")
            child_result = "success"
            if properties.is_file():
                for line in properties.read_text().splitlines():
                    key, separator, value = line.partition("=")
                    if separator and key.strip() == "invoker.buildResult":
                        child_result = value.strip()
            if child_result not in ("success", "failure"):
                raise ValueError(
                    f"{pom.parent.name}: unsupported invoker.buildResult={child_result}"
                )
            fixtures[pom.parent.name] = child_result
    if not fixtures:
        raise ValueError(f"no Invoker fixtures found under {fixture_root}")
    return fixtures


def read_results(report_directory: Path) -> dict[str, str]:
    if not report_directory.is_dir():
        raise ValueError(f"report directory does not exist: {report_directory}")

    results: dict[str, str] = {}
    for report in sorted(report_directory.glob("BUILD-*.xml")):
        try:
            root = ET.parse(report).getroot()
        except ET.ParseError as failure:
            raise ValueError(f"malformed XML report {report}: {failure}") from failure
        if root.tag.rsplit("}", 1)[-1] != "build-job":
            raise ValueError(f"{report}: expected build-job root")
        project = root.get("project")
        result = root.get("result")
        if not project or not result:
            raise ValueError(f"{report}: missing project or result")
        fixture = Path(project).parent.name
        if fixture in results:
            raise ValueError(f"duplicate Invoker fixture identity: {fixture}")
        results[fixture] = result
    return results


def assert_expected_fixtures(module: Path, report_directory: Path) -> None:
    expected = expected_fixtures(module)
    actual = read_results(report_directory)
    errors = [
        f"missing fixture report: {fixture}"
        for fixture in sorted(expected.keys() - actual.keys())
    ]
    errors.extend(
        f"unknown fixture report: {fixture}"
        for fixture in sorted(actual.keys() - expected.keys())
    )
    errors.extend(
        f"{fixture}: outer result {result}, expected success"
        for fixture, result in sorted(actual.items())
        if fixture in expected and result != "success"
    )
    if errors:
        raise ValueError("; ".join(errors))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("module", type=Path)
    parser.add_argument("report_directory", type=Path)
    arguments = parser.parse_args()

    try:
        expected = expected_fixtures(arguments.module)
        assert_expected_fixtures(arguments.module, arguments.report_directory)
    except ValueError as failure:
        print(f"Invoker report assertion failed: {failure}", file=sys.stderr)
        raise SystemExit(1) from failure

    negative_count = sum(result == "failure" for result in expected.values())
    print(
        f"Invoker report assertion passed: {len(expected)} fixture(s), "
        f"{negative_count} expected child failure(s)."
    )


if __name__ == "__main__":
    main()
