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

    for event in events:
        name = event["event"]
        assert event["visitor_id"], name
        assert event["distinct_id"], name
        actual_version = event["defaultProperties"]["version"]
        assert actual_version == version, f"{name} version {actual_version!r} != {version!r}"

    by_name = {event["event"]: event for event in events}
    for name in ("demo_event", "$identify", "view_item", "$opt_in", "$deep_link_opened"):
        assert name in by_name, f"Missing {name}: {list(by_name)}"

    assert by_name["demo_event"]["eventProperties"]["source"] == "demo"
    assert by_name["$identify"]["userProperties"]["external_id"] == "demo_user_1"
    assert by_name["$identify"]["userProperties"]["email"] == "demo@example.com"
    assert by_name["view_item"]["eventProperties"]["sku"] == "ABC-001"
    assert by_name["view_item"]["userProperties"]["phone_number"] == "+1-555-0100"
    deep_link = by_name["$deep_link_opened"]
    event_properties = deep_link["eventProperties"]
    assert event_properties is None or "url" not in event_properties
    assert "https://example.com/landing" not in json.dumps(deep_link)
    assert "patient_email" not in json.dumps(deep_link)
    assert deep_link["defaultProperties"]["utm_source"] == "demo"
    assert deep_link["defaultProperties"]["utm_medium"] == "android"
    assert deep_link["defaultProperties"]["gclid"] == "demoGclid"
    print(f"Validated {len(events)} recorded events for SDK {version}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    validate(args.out, args.version)


if __name__ == "__main__":
    main()
