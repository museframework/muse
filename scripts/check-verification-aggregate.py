#!/usr/bin/env python3
"""Fail unless every expected discovered owner for the selected kinds passed."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def load_expected(inventory: Path, kinds: set[str]) -> list[dict]:
    payload = json.loads(inventory.read_text())
    owners = payload.get("include")
    if not isinstance(owners, list) or not owners:
        raise ValueError(f"inventory {inventory} has no owners")
    expected = [owner for owner in owners if owner.get("kind") in kinds]
    if not expected:
        raise ValueError(
            f"inventory {inventory} contains no owners for kinds {sorted(kinds)}"
        )
    return expected


def load_results(results_root: Path) -> dict[str, dict]:
    by_module: dict[str, dict] = {}
    for path in sorted(results_root.rglob("verification-run-*.json")):
        payload = json.loads(path.read_text())
        module = payload.get("module")
        if not isinstance(module, str) or not module:
            raise ValueError(f"{path}: missing module")
        if module in by_module:
            raise ValueError(f"duplicate verification result for module {module}")
        by_module[module] = payload
    return by_module


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--kind", action="append", dest="kinds", required=True)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--results-root", type=Path, default=Path("."))
    parser.add_argument(
        "--upstream-result",
        default=None,
        help="Optional GitHub needs.<job>.result; non-success fails the aggregate",
    )
    arguments = parser.parse_args()
    kinds = set(arguments.kinds)

    try:
        if arguments.upstream_result is not None and arguments.upstream_result != "success":
            raise ValueError(
                f"upstream connector-verification result is "
                f"{arguments.upstream_result!r}, expected 'success'"
            )
        expected = load_expected(arguments.inventory, kinds)
        results = load_results(arguments.results_root)
    except (OSError, ValueError, json.JSONDecodeError) as failure:
        print(f"Verification aggregate failed: {failure}", file=sys.stderr)
        return 1

    errors: list[str] = []
    for owner in expected:
        module = owner["module"]
        payload = results.get(module)
        if payload is None:
            errors.append(f"missing result for {module} ({owner['kind']}/{owner['family']})")
            continue
        if payload.get("kind") != owner["kind"]:
            errors.append(
                f"{module}: kind {payload.get('kind')!r} != expected {owner['kind']!r}"
            )
        if payload.get("status") != "passed":
            errors.append(
                f"{module}: status={payload.get('status')!r} (expected passed)"
            )

    unexpected = sorted(
        module
        for module, payload in results.items()
        if payload.get("kind") in kinds
        and module not in {owner["module"] for owner in expected}
    )
    errors.extend(f"unexpected result module {module}" for module in unexpected)

    if errors:
        print("Verification aggregate failed:", file=sys.stderr)
        for error in errors:
            print(f"  {error}", file=sys.stderr)
        return 1

    print(
        f"Verification aggregate passed: {len(expected)} expected owner result(s) "
        f"for kinds {sorted(kinds)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
