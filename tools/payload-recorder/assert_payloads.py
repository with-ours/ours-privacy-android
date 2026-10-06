#!/usr/bin/env python3

import argparse
import json
import re
from datetime import datetime
from pathlib import Path


UTC_MILLIS = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$")
PRIVATE_KEY = re.compile(
    r"(^|_)(patient|idfa|gaid|idfv|app_set_id|advertising_id|ad_id|url|route|email|phone)(_|$)",
    re.IGNORECASE,
)


def utc_millis(value: str) -> datetime:
    assert isinstance(value, str) and UTC_MILLIS.fullmatch(value), f"Invalid UTC timestamp: {value!r}"
    return datetime.strptime(value, "%Y-%m-%dT%H:%M:%S.%fZ")


def assert_no_private_keys(value: object, name: str) -> None:
    if isinstance(value, dict):
        for key, nested in value.items():
            normalized_key = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", key)
            normalized_key = normalized_key.replace("-", "_").replace(".", "_")
            assert not PRIVATE_KEY.search(normalized_key), f"{name} contains private field {key}"
            assert_no_private_keys(nested, name)
    elif isinstance(value, list):
        for nested in value:
            assert_no_private_keys(nested, name)


def validate(directory: Path, version: str, token: str = "e2e-token") -> None:
    files = sorted(directory.glob("*_ingest.json"))
    assert files, "No payloads reached the recorder"

    events = []
    for file in files:
        envelope = json.loads(file.read_text())
        assert envelope["token"] == token, file
        assert envelope.get("is_manually_set_id") is True, f"Visitor stitch missing: {file}"
        assert isinstance(envelope["data"], list), file
        events.extend(envelope["data"])

    for event in events:
        name = event["event"]
        assert "time" not in event, f"{name} sent a top-level time"
        assert event["visitor_id"], name
        assert event["distinct_id"], name
        actual_version = event["defaultProperties"]["version"]
        assert actual_version == version, f"{name} version {actual_version!r} != {version!r}"

    names = [event["event"] for event in events]
    by_name = {event["event"]: event for event in events}
    for name in ("demo_event", "$identify", "view_item", "$opt_in", "$deep_link_opened"):
        assert name in by_name, f"Missing {name}: {list(by_name)}"

    assert names.count("demo_event") == 1, "Tracking emitted after full opt-out"
    assert names.count("$mobile_first_open") == 1, "Expected one first tracked open"
    assert names.count("$ae_first_open") <= 1, "Duplicate legacy first-open"
    assert names.count("$mobile_session_start") == 1, "Expected one initial session"
    assert names.count("$mobile_app_open") == 2, "Expected cold and warm app opens"
    assert names.count("$mobile_screen_view") == 1, "Expected one Schedule screen view"
    assert "$ae_crashed" not in names, "Legacy crash telemetry was emitted"
    assert names.count("appointment_booked") == 1, "Expected one synthetic booking"
    assert any(
        event["event"] == "$mobile_screen_view"
        and (event.get("eventProperties") or {}).get("screen_name") == "Schedule"
        for event in events
    ), "Missing Schedule screen"
    assert any(
        event["event"] == "$mobile_session_engagement"
        and isinstance((event.get("eventProperties") or {}).get("engagement_duration_ms"), int)
        and event["eventProperties"]["engagement_duration_ms"] > 0
        for event in events
    ), "Missing positive engagement"
    open_indices = [
        index for index, name in enumerate(names) if name == "$mobile_app_open"
    ]
    screen_index = names.index("$mobile_screen_view")
    assert open_indices[0] < screen_index < open_indices[1], (
        "Second app open must follow the Schedule screen"
    )
    assert any(
        screen_index < index < open_indices[1]
        and event["event"] == "$mobile_session_engagement"
        and (event.get("eventProperties") or {}).get("screen_name") == "Schedule"
        and isinstance((event.get("eventProperties") or {}).get("engagement_duration_ms"), int)
        and event["eventProperties"]["engagement_duration_ms"] > 0
        for index, event in enumerate(events)
    ), "Missing positive Schedule engagement between screen view and warm open"

    visitor = f"{token}-visitor"
    first_link = names.index("$deep_link_opened")
    assert first_link < names.index("$mobile_first_open"), "Visitor link arrived after first open"
    assert events[first_link]["visitor_id"] == visitor, "Initial link did not stitch visitor"
    initial_session = by_name["$mobile_session_start"]["defaultProperties"]["sid"]
    assert initial_session, "Missing sid"
    initial_start = by_name["$mobile_session_start"]["defaultProperties"]["mobile_session_started_at"]
    for name in ("$mobile_first_open", "$mobile_app_open", "$mobile_screen_view", "appointment_booked"):
        event = by_name[name]
        assert event["visitor_id"] == visitor, f"{name} visitor was not stitched before tracking"
        assert event["defaultProperties"]["sid"] == initial_session, f"{name} used another sid"

    booking = by_name["appointment_booked"]
    assert booking["eventProperties"]["appointment_id"] == f"{token}-appointment"
    for event in events:
        if event["event"].startswith("$mobile_") or event["event"] == "appointment_booked":
            defaults = event["defaultProperties"]
            assert event["visitor_id"] == visitor, f"{event['event']} visitor was not stitched"
            assert defaults["sid"] == initial_session, f"{event['event']} used another sid"
            assert defaults["mobile_session_started_at"] == initial_start, event["event"]
            assert defaults["mobile_platform"] == "android", event["event"]
            assert defaults["mobile_contract_version"] == 1, event["event"]
            assert defaults["app_version"] == "1.0", event["event"]
            assert defaults["app_build"] == "1", event["event"]
            started = utc_millis(defaults["mobile_session_started_at"])
            occurred = utc_millis(defaults["mobile_occurred_at"])
            assert occurred >= started, f"{event['event']} occurred before its session"
        if event["event"] == "$mobile_session_engagement":
            duration = (event.get("eventProperties") or {}).get("engagement_duration_ms")
            assert isinstance(duration, int) and duration > 0, "Nonpositive engagement delta"

    for event in events:
        name = event["event"]
        if name.startswith("$mobile_") or name in (
            "$app_open", "$ae_first_open", "$ae_session", "$ae_updated", "$deep_link_opened"
        ):
            payload = json.dumps(event)
            assert not re.search(r"https?://", payload, re.IGNORECASE), f"{name} contains a raw URL"
            if name.startswith("$mobile_"):
                assert event.get("userProperties") is None, f"{name} contains user properties"
            for bag in ("eventProperties", "defaultProperties", "userProperties"):
                assert_no_private_keys(event.get(bag), name)

    assert by_name["demo_event"]["eventProperties"]["source"] == "demo"
    assert by_name["$identify"]["userProperties"]["external_id"] == "demo_user_1"
    assert by_name["$identify"]["userProperties"]["email"] == "demo@example.com"
    assert by_name["view_item"]["eventProperties"]["sku"] == "ABC-001"
    assert by_name["view_item"]["userProperties"]["phone_number"] == "+1-555-0100"
    for deep_link in (event for event in events if event["event"] == "$deep_link_opened"):
        event_properties = deep_link["eventProperties"]
        assert event_properties is None or "url" not in event_properties
        assert deep_link["defaultProperties"].get("utm_source") == "demo"
        assert deep_link["defaultProperties"].get("utm_medium") == "android"
        assert deep_link["defaultProperties"].get("gclid") == "demoGclid"
    print(f"Validated {len(events)} recorded events for SDK {version}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--token", required=True)
    args = parser.parse_args()
    validate(args.out, args.version, args.token)


if __name__ == "__main__":
    main()
