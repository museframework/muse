#!/usr/bin/env python3
"""Prepare and verify one discovered connector verification owner."""

from __future__ import annotations

import argparse
import importlib.util
import json
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Callable


SCRIPT_DIR = Path(__file__).resolve().parent


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


owners_module = _load("verification_owners", SCRIPT_DIR / "verification-owners.py")
e2e_reports = _load("assert_e2e_reports", SCRIPT_DIR / "assert-e2e-reports.py")
invoker_reports = _load("assert_invoker_reports", SCRIPT_DIR / "assert-invoker-reports.py")

VerificationOwner = owners_module.VerificationOwner
Execute = Callable[..., subprocess.CompletedProcess]


@dataclass(frozen=True)
class CommandRecord:
    argv: list[str]
    exit_code: int
    log_path: str


@dataclass(frozen=True)
class VerificationRun:
    module: str
    kind: str
    status: str
    commands: list[CommandRecord]
    reports: list[str]
    artifacts: list[str]


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


def _parse_pom(path: Path) -> ET.Element:
    try:
        return ET.parse(path).getroot()
    except (OSError, ET.ParseError) as failure:
        raise ValueError(f"cannot read Maven POM {path}: {failure}") from failure


def plugin_seed_artifact_ids(owner_pom: Path) -> list[str]:
    """Return muse-maven-plugin dependency artifactIds declared on the owner POM."""
    project = _parse_pom(owner_pom)
    build = _child(project, "build")
    if build is None:
        return []
    plugins = _child(build, "plugins")
    if plugins is None:
        return []
    seeds: list[str] = []
    for plugin in _children(plugins, "plugin"):
        artifact_id = _text(_child(plugin, "artifactId"))
        if artifact_id != "muse-maven-plugin":
            continue
        dependencies = _child(plugin, "dependencies")
        if dependencies is None:
            continue
        for dependency in _children(dependencies, "dependency"):
            seed = _text(_child(dependency, "artifactId"))
            if seed and seed not in seeds:
                seeds.append(seed)
    return seeds


def invoker_local_repository_path(owner_pom: Path) -> str | None:
    project = _parse_pom(owner_pom)
    build = _child(project, "build")
    if build is None:
        return None
    plugins = _child(build, "plugins")
    if plugins is None:
        return None
    for plugin in _children(plugins, "plugin"):
        if _text(_child(plugin, "artifactId")) != "maven-invoker-plugin":
            continue
        configuration = _child(plugin, "configuration")
        if configuration is None:
            return None
        return _text(_child(configuration, "localRepositoryPath"))
    return None


def validate_owner_fixture(owner: VerificationOwner, root: Path) -> None:
    pom = (root / owner.module / "pom.xml").resolve()
    if owner.kind != "plugin-realm":
        return
    local_repo = invoker_local_repository_path(pom)
    if local_repo is None:
        raise ValueError(
            f"{pom}: plugin-realm owner must declare maven-invoker-plugin "
            "localRepositoryPath under project.build.directory"
        )
    normalized = local_repo.replace("\\", "/")
    if "${project.build.directory}" not in normalized:
        raise ValueError(
            f"{pom}: Invoker localRepositoryPath must stay under "
            f"project.build.directory (private Invoker repository); got {local_repo!r}"
        )
    if normalized.startswith("/") or ".." in normalized.split("/"):
        raise ValueError(
            f"{pom}: forbidden shared private Invoker repository path {local_repo!r}"
        )


def resolve_owner(module_directory: Path, root: Path) -> VerificationOwner:
    root = root.resolve()
    module_directory = module_directory.resolve()
    try:
        relative = module_directory.relative_to(root).as_posix()
    except ValueError as failure:
        raise ValueError(
            f"{module_directory} is outside repository root {root}"
        ) from failure
    if relative == ".":
        raise ValueError(f"{module_directory} is not a discovered verification owner")
    for owner in owners_module.discover_owners(root):
        if owner.module == relative:
            return owner
    raise ValueError(f"{module_directory} is not a discovered verification owner")


def _pl_selector(owner: VerificationOwner, seeds: list[str]) -> str:
    selectors = [f":{owner.artifact_id}", *[f":{seed}" for seed in seeds]]
    return ",".join(selectors)


def prepare_argv(
    owner: VerificationOwner,
    root: Path,
    repository: Path,
) -> list[str]:
    seeds = plugin_seed_artifact_ids(root / owner.module / "pom.xml")
    return [
        "mvn",
        "clean",
        "install",
        "--batch-mode",
        "-ntp",
        "-DskipTests",
        "-Dkotlin.daemon.enabled=false",
        f"-pl={_pl_selector(owner, seeds)}",
        "-am",
        f"-Dmaven.repo.local={repository}",
    ]


def verify_argv(owner: VerificationOwner, repository: Path) -> list[str]:
    common = [
        "mvn",
        "clean",
        "verify",
        "--batch-mode",
        "-ntp",
        "-Dkotlin.daemon.enabled=false",
        f"-pl=:{owner.artifact_id}",
        f"-Dmaven.repo.local={repository}",
    ]
    if owner.kind == "plugin-realm":
        return [
            "mvn",
            "clean",
            "verify",
            "--batch-mode",
            "-ntp",
            "-DskipTests",
            "-Dkotlin.daemon.enabled=false",
            "-Pplugin-it",
            f"-pl=:{owner.artifact_id}",
            "-am",
            f"-Dmaven.repo.local={repository}",
        ]
    return [*common[:6], "-Pe2e", *common[6:]]


def _default_execute(argv: list[str], *, cwd: Path, log_path: Path) -> subprocess.CompletedProcess:
    with log_path.open("wb") as log_file:
        completed = subprocess.run(
            argv,
            cwd=str(cwd),
            stdout=log_file,
            stderr=subprocess.STDOUT,
            check=False,
        )
    return completed


def _copy_diagnostics(module_dir: Path, scratch: Path, commands: list[CommandRecord]) -> list[str]:
    destination = module_dir / "target" / "verification"
    destination.mkdir(parents=True, exist_ok=True)
    artifacts: list[str] = []
    for index, command in enumerate(commands, start=1):
        source = Path(command.log_path)
        if not source.is_file():
            continue
        target = destination / f"{index:02d}-{source.name}"
        shutil.copy2(source, target)
        artifacts.append(str(target))

    # Preserve packaged child-process logs generically (any *.log under target/,
    # excluding Maven Surefire/Failsafe/Invoker report trees and this destination).
    excluded_roots = {
        (module_dir / "target" / name).resolve()
        for name in (
            "verification",
            "failsafe-reports",
            "surefire-reports",
            "invoker-reports",
        )
    }
    child_root = destination / "child-logs"
    target_root = (module_dir / "target").resolve()
    if target_root.is_dir():
        for source in sorted(target_root.rglob("*.log")):
            resolved = source.resolve()
            if any(
                resolved == excluded or excluded in resolved.parents
                for excluded in excluded_roots
            ):
                continue
            relative = resolved.relative_to(target_root)
            target = child_root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(resolved, target)
            artifacts.append(str(target))
    return artifacts


def _assert_reports(owner: VerificationOwner, root: Path) -> list[str]:
    module_dir = root / owner.module
    if owner.kind == "plugin-realm":
        report_directory = module_dir / "target" / "invoker-reports"
        invoker_reports.assert_expected_fixtures(module_dir, report_directory)
        return sorted(str(path) for path in report_directory.glob("BUILD-*.xml"))

    report_directory = module_dir / "target" / "failsafe-reports"
    e2e_reports.assert_expected_cases(
        module_dir,
        report_directory,
        suffixes=("IT", "E2ETest"),
    )
    return sorted(str(path) for path in report_directory.glob("TEST-*.xml"))


def run_owner(
    owner: VerificationOwner,
    root: Path,
    scratch: Path,
    *,
    repository: Path,
    execute: Execute | None = None,
) -> VerificationRun:
    root = root.resolve()
    scratch = scratch.resolve()
    repository = repository.resolve()
    scratch.mkdir(parents=True, exist_ok=True)
    repository.mkdir(parents=True, exist_ok=True)
    validate_owner_fixture(owner, root)

    executor = execute or _default_execute
    commands: list[CommandRecord] = []
    status = "passed"
    reports: list[str] = []
    artifacts: list[str] = []

    planned = (
        ("prepare", prepare_argv(owner, root, repository)),
        ("verify", verify_argv(owner, repository)),
    )
    for label, argv in planned:
        log_path = scratch / f"{owner.artifact_id}-{label}.log"
        completed = executor(argv, cwd=root, log_path=log_path)
        if not isinstance(completed, subprocess.CompletedProcess) and hasattr(
            completed, "returncode"
        ):
            exit_code = int(completed.returncode)
        else:
            exit_code = int(getattr(completed, "returncode", 1))
        if not log_path.is_file():
            log_path.write_text("")
        record = CommandRecord(argv=list(argv), exit_code=exit_code, log_path=str(log_path))
        commands.append(record)
        if exit_code != 0:
            status = "failed"
            break

    if status == "passed":
        try:
            reports = _assert_reports(owner, root)
        except (ValueError, SystemExit) as failure:
            status = "failed"
            failure_log = scratch / f"{owner.artifact_id}-report-gate.log"
            failure_log.write_text(str(failure))
            commands.append(
                CommandRecord(
                    argv=["report-gate", owner.module],
                    exit_code=1,
                    log_path=str(failure_log),
                )
            )

    artifacts = _copy_diagnostics(root / owner.module, scratch, commands)
    return VerificationRun(
        module=owner.module,
        kind=owner.kind,
        status=status,
        commands=commands,
        reports=reports,
        artifacts=artifacts,
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("module_directory", type=Path)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--scratch", type=Path, required=True)
    parser.add_argument(
        "--repository-root",
        type=Path,
        default=None,
        help="Git repository root containing verification owners (default: cwd)",
    )
    parser.add_argument(
        "--result-json",
        type=Path,
        default=None,
        help="Optional path for the VerificationRun JSON result",
    )
    arguments = parser.parse_args(argv)

    root = (arguments.repository_root or Path.cwd()).resolve()
    try:
        owner = resolve_owner(arguments.module_directory, root)
        result = run_owner(
            owner,
            root,
            arguments.scratch,
            repository=arguments.repository,
        )
    except ValueError as failure:
        print(f"Verification owner run failed: {failure}", file=sys.stderr)
        return 1

    payload = {
        "module": result.module,
        "kind": result.kind,
        "status": result.status,
        "commands": [asdict(command) for command in result.commands],
        "reports": result.reports,
        "artifacts": result.artifacts,
    }
    encoded = json.dumps(payload, indent=2)
    if arguments.result_json is not None:
        arguments.result_json.parent.mkdir(parents=True, exist_ok=True)
        arguments.result_json.write_text(encoded + "\n")
    print(encoded)
    return 0 if result.status == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
