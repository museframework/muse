#!/usr/bin/env bash

set -euo pipefail

if (( $# < 2 )); then
    echo "Usage: $0 <report-directory> <module> [<module> ...]" >&2
    exit 2
fi

report_directory=$1
shift

failure_count=0

for module_directory in "$@"; do
    if [[ ! -d "$module_directory" ]]; then
        echo "Test report assertion failed: module directory does not exist: $module_directory" >&2
        failure_count=$((failure_count + 1))
        continue
    fi

    report_path="$module_directory/target/$report_directory"
    report_count=0
    non_empty_report_count=0

    if [[ -d "$report_path" ]]; then
        while IFS= read -r -d '' report; do
            report_count=$((report_count + 1))
            # Some JUnit Platform providers write tests="0" for a container
            # while still recording its nested tests as <testcase> elements.
            if grep -Eq '<testcase[[:space:]>]' "$report"; then
                non_empty_report_count=$((non_empty_report_count + 1))
            fi
        done < <(find "$report_path" -maxdepth 1 -type f -name 'TEST-*.xml' -print0)
    fi

    if (( non_empty_report_count == 0 )); then
        echo "Test report assertion failed: $module_directory produced no non-empty $report_directory reports ($report_count report files found)." >&2
        failure_count=$((failure_count + 1))
        continue
    fi

    echo "Test report assertion passed: $module_directory produced $non_empty_report_count non-empty $report_directory report(s)."
done

if (( failure_count > 0 )); then
    exit 1
fi
