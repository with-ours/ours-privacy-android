#!/usr/bin/env python3

import argparse
import json
from pathlib import Path


def validate(directory: Path, version: str) -> None:
    files = sorted(directory.glob("*_ingest.json"))
    assert files, "No payloads reached the recorder"

    events = []
    for file in files:
        envelope = json.loads(file.read_text())
        assert envelope["token"] == "e2e-token", file
        assert isinstance(envelope["data"], list), file
        events.extend(envelope["data"])

    by_name = {event["event"]: event for event in events}
    for name in ("demo_event", "$identify", "view_item"):
        assert name in by_name, f"Missing {name}: {list(by_name)}"
        event = by_name[name]
        assert event["visitor_id"], name
        assert event["distinct_id"], name
        actual_version = event["defaultProperties"]["version"]
        assert actual_version == version, f"{name} version {actual_version!r} != {version!r}"

    assert by_name["demo_event"]["eventProperties"]["source"] == "demo"
    assert by_name["$identify"]["userProperties"]["external_id"] == "demo_user_1"
    assert by_name["$identify"]["userProperties"]["email"] == "demo@example.com"
    assert by_name["view_item"]["eventProperties"]["sku"] == "ABC-001"
    assert by_name["view_item"]["userProperties"]["phone_number"] == "+1-555-0100"
    print(f"Validated {len(events)} recorded events for SDK {version}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    validate(args.out, args.version)


if __name__ == "__main__":
    main()
