#!/usr/bin/env python3
"""Capture and compare the same five HTTP scenarios against Mule EE and Muse.

Run each application separately, even when both bind port 8080:
  python3 compare-mule-muse-http.py capture --url http://localhost:8080 --output mule.json
  python3 compare-mule-muse-http.py capture --url http://localhost:8080 --output muse.json
  python3 compare-mule-muse-http.py compare mule.json muse.json

The original Mule EE baseline requires a separately available, licensed runtime.
"""
import argparse
import json
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

SCENARIOS = (
    ("get-resources", "GET", "/api/resources", None, None),
    ("post-binary", "POST", "/api/resources", "application/octet-stream", b"hello-binary"),
    ("get-missing", "GET", "/api/missing", None, None),
    ("post-json", "POST", "/api/resources", "application/json", b'{"a":1}'),
    ("put-resources", "PUT", "/api/resources", "application/octet-stream", b"x"),
)
EXPECTED_STATUS = (200, 200, 404, 415, 405)


def capture(url: str) -> dict:
    captured = {}
    for key, method, path, media_type, data in SCENARIOS:
        request = Request(
            url.rstrip("/") + path,
            data=data,
            method=method,
            headers={"Content-Type": media_type} if media_type else {},
        )
        try:
            response = urlopen(request, timeout=15)
        except HTTPError as exception:
            response = exception
        with response:
            body = response.read()
            captured[key] = {
                "status": response.status,
                "content_type": response.headers.get("Content-Type", "").split(";")[0].strip().lower(),
                "body_utf8": body.decode("utf-8", errors="replace"),
                "body_hex": body.hex(),
            }
    return captured


def canonical_body(case: str, result: dict):
    if case == "post-binary":
        return ("bytes", result["body_hex"])
    if result["content_type"] == "application/json":
        try:
            parsed = json.loads(result["body_utf8"])
        except json.JSONDecodeError:
            return ("invalid-json", result["body_utf8"])
        if case == "get-resources":
            value = parsed.get("data", {}).get("execution_time")
            if isinstance(value, int) and not isinstance(value, bool):
                parsed["data"]["execution_time"] = "<random-integer>"
        return ("json", parsed)
    return ("text", result["body_utf8"])


def compare(mule: dict, muse: dict) -> int:
    failures = 0
    print("Scenario               Mule  Muse  Status  Media type  Body")
    for i, (case, _, _, _, _) in enumerate(SCENARIOS):
        a, b = mule[case], muse[case]
        status_ok = a["status"] == b["status"] == EXPECTED_STATUS[i]
        media_ok = a["content_type"] == b["content_type"]
        body_ok = canonical_body(case, a) == canonical_body(case, b)
        if not (status_ok and media_ok and body_ok):
            failures += 1
        print(
            f"{case:<22} {a['status']:>4}  {b['status']:>4} "
            f"{'PASS' if status_ok else 'FAIL':<6} "
            f"{'PASS' if media_ok else 'FAIL':<10} "
            f"{'PASS' if body_ok else 'FAIL'}"
        )
    print(f"Equivalent scenarios: {len(SCENARIOS) - failures}/{len(SCENARIOS)}")
    return 0 if failures == 0 else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    capture_command = subparsers.add_parser("capture")
    capture_command.add_argument("--url", required=True)
    capture_command.add_argument("--output", type=Path, required=True)
    compare_command = subparsers.add_parser("compare")
    compare_command.add_argument("mule", type=Path)
    compare_command.add_argument("muse", type=Path)
    args = parser.parse_args()
    if args.command == "capture":
        args.output.write_text(json.dumps(capture(args.url), indent=2) + "\n")
        print(f"Captured {len(SCENARIOS)} scenarios in {args.output}")
        return 0
    return compare(json.loads(args.mule.read_text()), json.loads(args.muse.read_text()))


if __name__ == "__main__":
    raise SystemExit(main())
