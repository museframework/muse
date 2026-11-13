#!/usr/bin/env bash
set -euo pipefail

usage() {
    echo "Usage: $0 [--dry-run] <major.minor.patch[-SNAPSHOT]>" >&2
}

if [[ $# -eq 1 ]]; then
    dry_run=()
    version=$1
elif [[ $# -eq 2 && $1 == --dry-run ]]; then
    dry_run=(--dry-run)
    version=$2
else
    usage
    exit 2
fi

if [[ ! $version =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-SNAPSHOT)?$ ]]; then
    usage
    echo "Invalid Muse version: '$version'" >&2
    exit 2
fi

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
exec python3 "$repo_root/scripts/set-version-poms.py" "${dry_run[@]}" "$repo_root" "$version"
