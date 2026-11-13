#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || ! $1 =~ ^[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?$ ]]; then
  echo "Usage: $0 <major.minor.patch[-SNAPSHOT]>" >&2
  exit 2
fi

cd "$(dirname "${BASH_SOURCE[0]}")/.."
old=$(sed -n 's/.*<muse.version>\([^<]*\)<\/muse.version>.*/\1/p' muse-bom/pom.xml)
[[ -n "$old" ]] || { echo 'Cannot read muse.version from muse-bom/pom.xml' >&2; exit 1; }
[[ "$old" != "$1" ]] || { echo "Already at $1"; exit 0; }

count=0
while IFS= read -r -d '' pom; do
  if grep -Fq "$old" "$pom"; then
    sed -i.bak "s/${old//./\\.}/$1/g" "$pom"
    rm -f -- "$pom.bak"
    ((count+=1))
  fi
done < <(git ls-files -z -- 'pom.xml' ':(glob)**/pom.xml')

echo "Updated $count POMs: $old -> $1"
