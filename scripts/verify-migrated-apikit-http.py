#!/usr/bin/env python3
"""Check the canonical migrated Mule backend over real HTTP, without changing its fixtures."""

import argparse
import http.client
import json
import socket
import time


def request(port, method, path, body=None, headers=None):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
    try:
        connection.request(method, path, body=body, headers=headers or {})
        response = connection.getresponse()
        return response.status, response.getheader("Content-Type", ""), response.read()
    finally:
        connection.close()


def assert_media_type(actual, expected, scenario):
    media_type = actual.partition(";")[0].strip().lower()
    if media_type != expected:
        raise AssertionError(
            f"{scenario}: expected media type {expected!r}, got {actual!r}"
        )


def assert_json_error(port, method, path, expected_status, expected_message, body=None, headers=None):
    status, content_type, response_body = request(port, method, path, body, headers)
    scenario = f"{method} {path}"
    if status != expected_status:
        raise AssertionError(
            f"{scenario}: expected HTTP {expected_status}, got {status}; body={response_body!r}"
        )
    assert_media_type(content_type, "application/json", scenario)
    try:
        actual = json.loads(response_body)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise AssertionError(f"{scenario}: error response is not JSON: {response_body!r}") from exc
    expected = {"message": expected_message}
    if actual != expected:
        raise AssertionError(f"{scenario}: expected {expected!r}, got {actual!r}")
    print(f"PASS {scenario}: HTTP {status}, application/json, {expected!r}")


def await_ready(port, seconds):
    deadline = time.monotonic() + seconds
    last_error = "no HTTP response"
    while time.monotonic() < deadline:
        try:
            status, _, _ = request(port, "GET", "/api/resources")
            if status == 200:
                return
            last_error = f"HTTP {status}"
        except (OSError, http.client.HTTPException, TimeoutError) as exc:
            last_error = str(exc)
        time.sleep(0.25)
    raise AssertionError(f"Canonical backend did not become ready: {last_error}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--startup-timeout", type=float, default=35.0)
    args = parser.parse_args()

    await_ready(args.port, args.startup_timeout)
    status, content_type, body = request(args.port, "GET", "/api/resources")
    if status != 200:
        raise AssertionError(f"GET /api/resources: expected HTTP 200, got {status}")
    assert_media_type(content_type, "application/json", "GET /api/resources")
    data = json.loads(body)
    value = data.get("data", {}).get("execution_time") if isinstance(data, dict) else None
    if not isinstance(value, int) or isinstance(value, bool):
        raise AssertionError(f"GET /api/resources: expected integer execution_time, got {data!r}")
    print("PASS GET /api/resources: HTTP 200, JSON execution_time")

    payload = b"hello-binary"
    status, content_type, body = request(
        args.port, "POST", "/api/resources", payload,
        {"Content-Type": "application/octet-stream"},
    )
    if status != 200 or body != payload:
        raise AssertionError(
            f"POST /api/resources: expected HTTP 200 and binary echo, got HTTP {status}, {body!r}"
        )
    assert_media_type(content_type, "application/octet-stream", "POST /api/resources")
    print("PASS POST /api/resources: HTTP 200, exact binary echo")

    assert_json_error(
        args.port, "GET", "/api/missing", 404, "Resource not found"
    )
    assert_json_error(
        args.port, "POST", "/api/resources", 415, "Unsupported media type",
        b'{"a":1}', {"Content-Type": "application/json"},
    )
    assert_json_error(
        args.port, "PUT", "/api/resources", 405, "Method not allowed",
        b"x", {"Content-Type": "application/octet-stream"},
    )


if __name__ == "__main__":
    main()
