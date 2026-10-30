#!/usr/bin/env python3
"""Require every E2E test method to appear in a Failsafe or Surefire report."""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

CLASS_PATTERN = re.compile(
    r"^(?P<abstract>abstract\s+)?class\s+(?P<name>\w+)\b(?P<rest>.*)$",
    re.MULTILINE,
)
SUPER_PATTERN = re.compile(r":\s*(\w+)")
PACKAGE_PATTERN = re.compile(r"^package\s+([\w.]+)", re.MULTILINE)
TEST_PATTERN = re.compile(r"@Test\s+fun `([^`]+)`")
NESTED_CLASS_PATTERN = re.compile(
    r"(?P<annotations>(?:@\w+(?:\([^)]*\))?\s*)*@Nested\b"
    r"(?:\s*@\w+(?:\([^)]*\))?)*\s*)"
    r"(?:inner\s+)?class\s+(?P<name>\w+)[^{]*\{"
)
DISPLAY_NAME_PATTERN = re.compile(r'@DisplayName\("([^"\\]*(?:\\.[^"\\]*)*)"\)')


def test_names(text: str) -> list[str]:
    return TEST_PATTERN.findall(text)


def class_body(text: str, open_brace: int) -> str:
    depth = 0
    for index in range(open_brace, len(text)):
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[open_brace : index]
    raise SystemExit("Unbalanced class body while reading E2E sources")


def split_nested_classes(body: str) -> tuple[str, list[tuple[str, str | None, str]]]:
    direct: list[str] = []
    nested: list[tuple[str, str | None, str]] = []
    cursor = 0
    while match := NESTED_CLASS_PATTERN.search(body, cursor):
        direct.append(body[cursor : match.start()])
        nested_body = class_body(body, match.end() - 1)
        display_name = DISPLAY_NAME_PATTERN.search(match.group("annotations"))
        nested.append(
            (match.group("name"), display_name.group(1) if display_name else None, nested_body)
        )
        cursor = match.end() + len(nested_body)
    direct.append(body[cursor:])
    return "".join(direct), nested


def add_nested_alias(
    aliases: dict[tuple[str, str], str | None], key: tuple[str, str], identity: str
) -> None:
    if key in aliases and aliases[key] != identity:
        aliases[key] = None
    else:
        aliases[key] = identity


def nested_test_suites(
    enclosing_class: str,
    body: str,
    aliases: dict[tuple[str, str], str | None],
    binary_class: str | None = None,
    declaring_class: str | None = None,
) -> list[tuple[str, list[str]]]:
    suites: list[tuple[str, list[str]]] = []
    for name, display_name, nested_body in split_nested_classes(body)[1]:
        nested_class = f"{binary_class or enclosing_class}${name}"
        declared_nested = f"{declaring_class or binary_class or enclosing_class}${name}"
        add_nested_alias(aliases, (enclosing_class, declared_nested), nested_class)
        add_nested_alias(aliases, (declared_nested, declared_nested), nested_class)
        if display_name:
            add_nested_alias(aliases, (enclosing_class, display_name), nested_class)
        names = test_names(split_nested_classes(nested_body)[0])
        if names:
            suites.append((nested_class, names))
        suites.extend(
            nested_test_suites(enclosing_class, nested_body, aliases, nested_class, declared_nested)
        )
    return suites


def inherited_nested_suites(
    enclosing_class: str,
    class_name: str,
    classes: dict[str, tuple[str, str | None, str]],
    aliases: dict[tuple[str, str], str | None],
    seen: set[str],
) -> list[tuple[str, list[str]]]:
    if class_name in seen or class_name not in classes:
        return []
    seen.add(class_name)
    body, superclass, declaring_class = classes[class_name]
    suites = nested_test_suites(enclosing_class, body, aliases, declaring_class=declaring_class)
    if superclass:
        suites.extend(inherited_nested_suites(enclosing_class, superclass, classes, aliases, seen))
    return suites


def index_classes(test_root: Path) -> dict[str, tuple[str, str | None, str]]:
    classes: dict[str, tuple[str, str | None, str]] = {}
    for source in test_root.rglob("*.kt"):
        text = source.read_text()
        package_match = PACKAGE_PATTERN.search(text)
        package_name = package_match.group(1) if package_match else ""
        for match in CLASS_PATTERN.finditer(text):
            name = match.group("name")
            header = match.group("rest").split("{", 1)[0]
            superclass = SUPER_PATTERN.search(header)
            if "{" in match.group(0):
                body = class_body(text, match.start() + match.group(0).index("{"))
            else:
                body = ""
            classes.setdefault(
                name, (body, superclass.group(1) if superclass else None, f"{package_name}.{name}")
            )
    return classes


def inherited_test_names(
    class_name: str,
    classes: dict[str, tuple[str, str | None, str]],
    seen: set[str],
) -> list[str]:
    if class_name in seen or class_name not in classes:
        return []
    seen.add(class_name)
    body, superclass, _ = classes[class_name]
    names = test_names(split_nested_classes(body)[0])
    if superclass:
        names.extend(inherited_test_names(superclass, classes, seen))
    return names


def concrete_suites(module: Path) -> list[tuple[str, list[str]]]:
    return suites_with_suffixes(module, ("E2ETest",))


def suites_with_suffixes(
    module: Path,
    suffixes: tuple[str, ...],
    aliases: dict[tuple[str, str], str | None] | None = None,
) -> list[tuple[str, list[str]]]:
    test_root = module / "src" / "test"
    if not test_root.is_dir():
        raise SystemExit(f"Test sources not found: {test_root}")

    classes = index_classes(test_root)
    if aliases is None:
        aliases = {}
    suites: list[tuple[str, list[str]]] = []
    sources = {
        source
        for suffix in suffixes
        for source in test_root.rglob(f"*{suffix}.kt")
    }
    for source in sorted(sources):
        text = source.read_text()
        package_match = PACKAGE_PATTERN.search(text)
        if package_match is None:
            raise SystemExit(f"Missing package declaration: {source}")
        package_name = package_match.group(1)
        for match in CLASS_PATTERN.finditer(text):
            if match.group("abstract"):
                continue
            class_name = match.group("name")
            if not class_name.endswith(suffixes):
                continue
            names = inherited_test_names(class_name, classes, set())
            qualified_name = f"{package_name}.{class_name}"
            nested = inherited_nested_suites(qualified_name, class_name, classes, aliases, set())
            if not names and not nested:
                raise SystemExit(f"{package_name}.{class_name} declares no test methods")
            if names:
                suites.append((qualified_name, names))
            suites.extend(nested)
    if not suites:
        raise SystemExit(f"No test classes ending in {suffixes} found under {test_root}")
    return suites


def expected_testcases(
    module: Path,
    suffixes: tuple[str, ...] = ("E2ETest",),
) -> set[tuple[str, str]]:
    return {
        (class_name, test_name)
        for class_name, names in suites_with_suffixes(module, suffixes)
        for test_name in names
    }


def _tag(element: ET.Element) -> str:
    return element.tag.rsplit("}", 1)[-1]


def read_testcases(
    report_directory: Path, aliases: dict[tuple[str, str], str | None] | None = None
) -> dict[tuple[str, str], str]:
    if not report_directory.is_dir():
        raise ValueError(f"report directory does not exist: {report_directory}")

    testcases: dict[tuple[str, str], str] = {}
    aliases = aliases or {}
    errors: list[str] = []
    for report in sorted(report_directory.glob("TEST-*.xml")):
        try:
            root = ET.parse(report).getroot()
        except ET.ParseError as failure:
            raise ValueError(f"malformed XML report {report}: {failure}") from failure

        suites = [root] if _tag(root) == "testsuite" else [
            element for element in root.iter() if _tag(element) == "testsuite"
        ]
        for suite in suites:
            suite_name = suite.get("name", report.name)
            children = list(suite)
            cases = [child for child in children if _tag(child) == "testcase"]
            outcomes = {"passed": 0, "skipped": 0, "failure": 0, "error": 0}
            direct_outcomes = dict(outcomes)
            has_nested_cases = False
            for testcase in cases:
                class_name = testcase.get("classname", "")
                alias = (suite_name, class_name)
                identity_class = aliases.get(alias, class_name)
                if identity_class is None:
                    raise ValueError(f"{suite_name}: ambiguous nested testcase class {class_name}")
                nested_case = alias in aliases or identity_class.startswith(f"{suite_name}$")
                has_nested_cases = has_nested_cases or nested_case
                identity = (identity_class, testcase.get("name", ""))
                status = "passed"
                for child in testcase:
                    if _tag(child) in ("skipped", "failure", "error"):
                        status = _tag(child)
                        break
                outcomes[status] += 1
                if not nested_case:
                    direct_outcomes[status] += 1
                if identity in testcases:
                    errors.append(f"duplicate testcase {identity[0]}.{identity[1]}")
                else:
                    testcases[identity] = status

            for status in ("failure", "error", "skipped"):
                if any(_tag(child) == status for child in children):
                    errors.append(f"{suite_name}: suite-level {status}")
            expected_counts = {
                "tests": len(cases),
                "failures": outcomes["failure"],
                "errors": outcomes["error"],
                "skipped": outcomes["skipped"],
            }
            # Failsafe nested reports may count only the enclosing class while
            # retaining all child testcases. Their individual outcomes still gate success.
            direct_counts = {
                "tests": sum(direct_outcomes.values()),
                "failures": direct_outcomes["failure"],
                "errors": direct_outcomes["error"],
                "skipped": direct_outcomes["skipped"],
            }
            if has_nested_cases and all(
                suite.get(attribute) == str(count)
                for attribute, count in direct_counts.items()
                if attribute in suite.attrib
            ):
                expected_counts = direct_counts
            for attribute, actual in expected_counts.items():
                if attribute in suite.attrib:
                    try:
                        declared = int(suite.get(attribute, ""))
                    except ValueError:
                        errors.append(f"{suite_name}: invalid {attribute} count")
                    else:
                        if declared != actual:
                            errors.append(
                                f"{suite_name}: {attribute} count is {declared}, "
                                f"parsed {actual}"
                            )

        if _tag(root) == "testsuites":
            container_name = root.get("name", report.name)
            children = list(root)
            for status in ("failure", "error", "skipped"):
                if any(_tag(child) == status for child in children):
                    errors.append(f"{container_name}: container-level {status}")

            cases = [
                element for element in root.iter() if _tag(element) == "testcase"
            ]
            outcomes = {"passed": 0, "skipped": 0, "failure": 0, "error": 0}
            for testcase in cases:
                status = "passed"
                for child in testcase:
                    if _tag(child) in ("skipped", "failure", "error"):
                        status = _tag(child)
                        break
                outcomes[status] += 1

            expected_counts = {
                "tests": len(cases),
                "failures": outcomes["failure"],
                "errors": outcomes["error"],
                "skipped": outcomes["skipped"],
            }
            for attribute, actual in expected_counts.items():
                if attribute in root.attrib:
                    try:
                        declared = int(root.get(attribute, ""))
                    except ValueError:
                        errors.append(
                            f"{container_name}: invalid container {attribute} count"
                        )
                    else:
                        if declared != actual:
                            errors.append(
                                f"{container_name}: container {attribute} count is "
                                f"{declared}, parsed {actual}"
                            )

    if errors:
        raise ValueError("; ".join(errors))
    return testcases


def assert_expected_cases(
    module: Path,
    report_directory: Path,
    suffixes: tuple[str, ...] = ("E2ETest",),
) -> None:
    aliases: dict[tuple[str, str], str | None] = {}
    expected = {
        (class_name, name)
        for class_name, names in suites_with_suffixes(module, suffixes, aliases)
        for name in names
    }
    actual = read_testcases(report_directory, aliases)
    errors = [
        f"{class_name}: missing testcase {name}"
        for class_name, name in sorted(expected - actual.keys())
    ]
    errors.extend(
        f"{class_name}.{name}: {status}"
        for (class_name, name), status in sorted(actual.items())
        if status != "passed"
    )
    if errors:
        raise ValueError("; ".join(errors))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("module", type=Path)
    parser.add_argument("report_directory", type=Path)
    parser.add_argument(
        "--suite-suffix",
        action="append",
        choices=("IT", "E2ETest"),
        dest="suffixes",
    )
    arguments = parser.parse_args()
    suffixes = tuple(arguments.suffixes or ("E2ETest",))

    try:
        expected = expected_testcases(arguments.module, suffixes)
        assert_expected_cases(arguments.module, arguments.report_directory, suffixes)
    except ValueError as failure:
        print(f"Test report assertion failed: {failure}", file=sys.stderr)
        raise SystemExit(1) from failure

    print(
        f"Test report assertion passed: {len(expected)} expected testcase(s)."
    )


if __name__ == "__main__":
    main()
