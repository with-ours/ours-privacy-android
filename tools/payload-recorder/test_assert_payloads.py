from copy import deepcopy
import json
import tempfile
import unittest
from pathlib import Path

from assert_payloads import validate


class PayloadValidationTest(unittest.TestCase):
    def write_capture(self, directory, version):
        events = [
            {
                "event": "demo_event",
                "visitor_id": "e2e-token-visitor",
                "distinct_id": "one",
                "eventProperties": {"source": "demo"},
                "defaultProperties": {"version": version},
            },
            {
                "event": "$identify",
                "visitor_id": "e2e-token-visitor",
                "distinct_id": "two",
                "eventProperties": None,
                "userProperties": {"external_id": "demo_user_1", "email": "demo@example.com"},
                "defaultProperties": {"version": version},
            },
            {
                "event": "view_item",
                "visitor_id": "e2e-token-visitor",
                "distinct_id": "three",
                "eventProperties": {"sku": "ABC-001"},
                "userProperties": {"phone_number": "+1-555-0100"},
                "defaultProperties": {"version": version},
            },
            {
                "event": "$opt_in",
                "visitor_id": "e2e-token-visitor",
                "distinct_id": "four",
                "eventProperties": None,
                "userProperties": None,
                "defaultProperties": {"version": version},
            },
            {
                "event": "$deep_link_opened",
                "visitor_id": "e2e-token-visitor",
                "distinct_id": "five",
                "eventProperties": None,
                "userProperties": None,
                "defaultProperties": {
                    "version": version,
                    "utm_source": "demo",
                    "utm_medium": "android",
                    "gclid": "demoGclid",
                },
            },
        ]
        mobile_defaults = {"version": version, "sid": "1791288000000"}
        mobile_properties = {
            "mobile_occurred_at": "2026-10-06T12:00:01.000Z",
            "mobile_contract_version": 1,
            "app_version": "1.0",
            "app_build": "1",
        }
        for event in events:
            event["defaultProperties"].update(mobile_defaults)
            event["eventProperties"] = {**(event["eventProperties"] or {}), **mobile_properties}
        for index, (name, properties) in enumerate(
            [
                ("$mobile_first_open", None),
                ("$mobile_app_open", None),
                ("$mobile_session_start", None),
                ("$mobile_screen_view", {"screen_name": "Schedule"}),
                ("$mobile_session_engagement", {"engagement_duration_ms": 1000, "screen_name": "Schedule"}),
                ("appointment_booked", {"appointment_id": "e2e-token-appointment"}),
                ("$mobile_app_open", None),
            ],
            start=6,
        ):
            events.append(
                {
                    "event": name,
                    "visitor_id": "e2e-token-visitor",
                    "distinct_id": str(index),
                    "eventProperties": {**(properties or {}), **mobile_properties},
                    "defaultProperties": deepcopy(mobile_defaults),
                }
            )
        (Path(directory) / "001_ingest.json").write_text(
            json.dumps({"token": "e2e-token", "is_manually_set_id": True, "data": events})
        )

    def mutate_capture(self, directory, change):
        path = Path(directory) / "001_ingest.json"
        envelope = json.loads(path.read_text())
        change(envelope)
        path.write_text(json.dumps(envelope))

    def append_capacity_event(self, directory, count, metadata=None):
        def append(body):
            event = deepcopy(body["data"][0])
            event.update(
                event="capacity_event",
                distinct_id="capacity-event-id",
                eventProperties={f"field_{index}": f"value-{index}" for index in range(count)},
            )
            event["eventProperties"].update(metadata or {})
            body["data"].append(event)
        self.mutate_capture(directory, append)

    def test_accepts_capacity_fallback_preserving_197_to_200_caller_properties(self):
        for count in range(197, 201):
            with self.subTest(count=count), tempfile.TemporaryDirectory() as directory:
                self.write_capture(directory, "2.0.0")
                self.append_capacity_event(directory, count)
                validate(Path(directory), "2.0.0")

    def test_rejects_missing_metadata_when_four_common_fields_fit(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            self.append_capacity_event(directory, 196)
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_partial_metadata_on_capacity_fallback(self):
        for key, value in (
            ("mobile_contract_version", 1),
            ("mobile_occurred_at", "2026-10-06T12:00:01.000Z"),
            ("app_version", "1.0"),
            ("app_build", "1"),
        ):
            with self.subTest(key=key), tempfile.TemporaryDirectory() as directory:
                self.write_capture(directory, "2.0.0")
                self.append_capacity_event(directory, 197, {key: value})
                with self.assertRaises(AssertionError):
                    validate(Path(directory), "2.0.0")

    def test_rejects_sdk_metadata_that_overflows_200_event_properties(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            self.append_capacity_event(directory, 197, {
                "mobile_contract_version": 1,
                "mobile_occurred_at": "2026-10-06T12:00:01.000Z",
                "app_version": "1.0",
                "app_build": "1",
            })
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_accepts_serialized_size_fallback_preserving_one_ascii_property(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            properties = {"payload": "x" * 1048561}
            assert len(json.dumps(properties, separators=(",", ":"))) == 1048575
            self.append_capacity_event(directory, 0, properties)
            validate(Path(directory), "2.0.0")

    def test_rejects_serialized_properties_at_or_above_size_limit(self):
        for value_length, metadata in (
            (1048562, {}),
            (1048561, {
                "mobile_contract_version": 1,
                "mobile_occurred_at": "2026-10-06T12:00:01.000Z",
                "app_version": "1.0",
                "app_build": "1",
            }),
        ):
            with self.subTest(value_length=value_length), tempfile.TemporaryDirectory() as directory:
                self.write_capture(directory, "2.0.0")
                self.append_capacity_event(directory, 0, {
                    "payload": "x" * value_length, **metadata,
                })
                with self.assertRaises(AssertionError):
                    validate(Path(directory), "2.0.0")

    def test_rejects_capacity_fallback_on_canonical_and_identify_events(self):
        for name in ("$mobile_first_open", "$identify"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                self.write_capture(directory, "2.0.0")
                def remove_metadata(body):
                    event = next(event for event in body["data"] if event["event"] == name)
                    event["eventProperties"] = {
                        f"field_{index}": f"value-{index}" for index in range(197)
                    }
                self.mutate_capture(directory, remove_metadata)
                with self.assertRaises(AssertionError):
                    validate(Path(directory), "2.0.0")

    def test_rejects_uuid_or_non_string_sid(self):
        for sid in ("legacy-uuid", 1791288000000, "179128800000", "17912880000000"):
            with self.subTest(sid=sid), tempfile.TemporaryDirectory() as directory:
                self.write_capture(directory, "2.0.0")
                def change_sid(body):
                    for event in body["data"]:
                        event["defaultProperties"]["sid"] = sid
                self.mutate_capture(directory, change_sid)
                with self.assertRaises(AssertionError):
                    validate(Path(directory), "2.0.0")

    def test_rejects_old_default_metadata_and_retired_fields(self):
        for bag, key, value in (
            ("defaultProperties", "mobile_contract_version", 1),
            ("defaultProperties", "mobile_occurred_at", "2026-10-06T12:00:01.000Z"),
            ("defaultProperties", "app_version", "1.0"),
            ("defaultProperties", "app_build", "1"),
            ("defaultProperties", "mobile_platform", "android"),
            ("eventProperties", "mobile_platform", "android"),
            ("defaultProperties", "mobile_session_started_at", "2026-10-06T12:00:00.000Z"),
            ("eventProperties", "mobile_session_started_at", "2026-10-06T12:00:00.000Z"),
        ):
            with self.subTest(bag=bag, key=key), tempfile.TemporaryDirectory() as directory:
                self.write_capture(directory, "2.0.0")
                def add_old_field(body):
                    body["data"][0][bag][key] = value
                self.mutate_capture(directory, add_old_field)
                with self.assertRaises(AssertionError):
                    validate(Path(directory), "2.0.0")

    def test_rejects_mixed_visitors_in_an_envelope(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            self.mutate_capture(directory, lambda body: body["data"][0].update(visitor_id="other"))
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_each_missing_canonical_fact_and_booking(self):
        for missing in (
            "$mobile_first_open", "$mobile_session_start", "$mobile_screen_view",
            "$mobile_session_engagement", "appointment_booked"
        ):
            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as directory:
                self.write_capture(directory, "2.0.0")
                def drop_event(body):
                    body["data"] = [event for event in body["data"] if event["event"] != missing]
                self.mutate_capture(directory, drop_event)
                with self.assertRaises(AssertionError):
                    validate(Path(directory), "2.0.0")

    def test_rejects_duplicate_first_open(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            self.mutate_capture(
                directory,
                lambda body: body["data"].append(deepcopy(next(
                    event for event in body["data"] if event["event"] == "$mobile_first_open"
                ))),
            )
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_link_after_first_open(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            def delayed_link(body):
                link = next(event for event in body["data"] if event["event"] == "$deep_link_opened")
                body["data"].remove(link)
                body["data"].append(link)
            self.mutate_capture(directory, delayed_link)
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_envelope_without_manual_stitch_flag(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            self.mutate_capture(directory, lambda body: body.__setitem__("is_manually_set_id", False))
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_zero_engagement_delta(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            def zero_delta(body):
                engagement = next(event for event in body["data"] if event["event"] == "$mobile_session_engagement")
                engagement["eventProperties"]["engagement_duration_ms"] = 0
            self.mutate_capture(directory, zero_delta)
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_missing_warm_open(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            def remove_warm_open(body):
                body["data"].pop()
            self.mutate_capture(directory, remove_warm_open)
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_two_app_opens_before_schedule_engagement(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            def move_warm_open_before_screen(body):
                second_open = body["data"].pop()
                first_open_index = next(
                    index for index, event in enumerate(body["data"])
                    if event["event"] == "$mobile_app_open"
                )
                body["data"].insert(first_open_index + 1, second_open)
            self.mutate_capture(directory, move_warm_open_before_screen)
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_unnamed_engagement_after_schedule_view(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            def remove_screen_attribution(body):
                engagement = next(
                    event for event in body["data"]
                    if event["event"] == "$mobile_session_engagement"
                )
                engagement["eventProperties"].pop("screen_name")
            self.mutate_capture(directory, remove_screen_attribution)
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_schedule_engagement_before_schedule_view(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            def move_engagement_before_screen(body):
                engagement = next(
                    event for event in body["data"]
                    if event["event"] == "$mobile_session_engagement"
                )
                body["data"].remove(engagement)
                screen_index = next(
                    index for index, event in enumerate(body["data"])
                    if event["event"] == "$mobile_screen_view"
                )
                body["data"].insert(screen_index, engagement)
            self.mutate_capture(directory, move_engagement_before_screen)
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_canonical_user_properties_even_without_private_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            def add_user_properties(body):
                opening = next(
                    event for event in body["data"] if event["event"] == "$mobile_first_open"
                )
                opening["userProperties"] = {"custom_properties": {"preferred_language": "en"}}
            self.mutate_capture(directory, add_user_properties)
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_nested_private_keys_on_automatic_facts(self):
        for bag, nested in (
            ("userProperties", {"custom_properties": {"patient_email": "synthetic@example.com"}}),
            ("eventProperties", {"details": {"patient_email": "synthetic@example.com"}}),
            ("defaultProperties", {"identifiers": [{"gaid": "synthetic-ad-id"}]}),
            ("eventProperties", {"details": {"patientEmail": "synthetic@example.com"}}),
            ("defaultProperties", {"identifiers": [{"advertisingId": "synthetic-ad-id"}]}),
        ):
            with self.subTest(bag=bag), tempfile.TemporaryDirectory() as directory:
                self.write_capture(directory, "2.0.0")
                def add_nested_field(body):
                    opening = next(
                        event for event in body["data"] if event["event"] == "$mobile_first_open"
                    )
                    if bag == "defaultProperties":
                        opening[bag].update(nested)
                    else:
                        opening[bag] = nested
                self.mutate_capture(directory, add_nested_field)
                with self.assertRaises(AssertionError):
                    validate(Path(directory), "2.0.0")

    def test_rejects_booking_with_wrong_visitor(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            def wrong_visitor(body):
                booking = next(event for event in body["data"] if event["event"] == "appointment_booked")
                booking["visitor_id"] = "other-visitor"
            self.mutate_capture(directory, wrong_visitor)
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_wrong_app_build(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            def wrong_build(body):
                booking = next(event for event in body["data"] if event["event"] == "appointment_booked")
                booking["eventProperties"]["app_build"] = "999"
            self.mutate_capture(directory, wrong_build)
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_second_demo_event_after_opt_out(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            def second_demo_event(body):
                leaked = deepcopy(next(event for event in body["data"] if event["event"] == "demo_event"))
                leaked["distinct_id"] = "leaked"
                body["data"].append(leaked)
            self.mutate_capture(directory, second_demo_event)
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_raw_url_and_ad_id_on_automatic_facts(self):
        for field, value in (
            ("ad_id", "synthetic-ad-id"),
            ("link", "https://example.com/landing"),
        ):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                self.write_capture(directory, "2.0.0")
                def private_field(body):
                    opening = next(event for event in body["data"] if event["event"] == "$mobile_first_open")
                    opening["defaultProperties"][field] = value
                self.mutate_capture(directory, private_field)
                with self.assertRaises(AssertionError):
                    validate(Path(directory), "2.0.0")

    def test_rejects_booking_on_another_session(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            def wrong_session(body):
                booking = next(event for event in body["data"] if event["event"] == "appointment_booked")
                booking["defaultProperties"]["sid"] = "other-session"
            self.mutate_capture(directory, wrong_session)
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_non_utc_mobile_occurrence_and_top_level_time(self):
        for field, value in (
            ("mobile_occurred_at", "2026-10-06T12:00:01-07:00"),
            ("time", "2026-10-06T12:00:01.000Z"),
        ):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                self.write_capture(directory, "2.0.0")
                def bad_time(body):
                    screen = next(event for event in body["data"] if event["event"] == "$mobile_screen_view")
                    if field == "time":
                        screen[field] = value
                    else:
                        screen["eventProperties"][field] = value
                self.mutate_capture(directory, bad_time)
                with self.assertRaises(AssertionError):
                    validate(Path(directory), "2.0.0")

    def test_rejects_private_automatic_property(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            def private_field(body):
                opening = next(event for event in body["data"] if event["event"] == "$mobile_first_open")
                opening["defaultProperties"]["patient_email"] = "private@example.com"
            self.mutate_capture(directory, private_field)
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_legacy_crash(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            self.mutate_capture(
                directory,
                lambda body: body["data"].append({
                    "event": "$ae_crashed",
                    "visitor_id": "e2e-token-visitor",
                    "distinct_id": "crash",
                    "defaultProperties": {"version": "2.0.0"},
                }),
            )
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_accepts_demo_event_envelope(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            validate(Path(directory), "2.0.0")

    def test_rejects_wrong_version(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "old")
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_wrong_version_on_additional_event(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            path = Path(directory) / "001_ingest.json"
            envelope = json.loads(path.read_text())
            envelope["data"].append(
                {
                    "event": "$app_open",
                    "visitor_id": "e2e-token-visitor",
                    "distinct_id": "four",
                    "defaultProperties": {"version": "old"},
                }
            )
            path.write_text(json.dumps(envelope))
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_missing_deep_link_event(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            path = Path(directory) / "001_ingest.json"
            envelope = json.loads(path.read_text())
            envelope["data"] = [
                event for event in envelope["data"] if event["event"] != "$deep_link_opened"
            ]
            path.write_text(json.dumps(envelope))
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_raw_url_on_deep_link_event(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            path = Path(directory) / "001_ingest.json"
            envelope = json.loads(path.read_text())
            deep_link = next(
                event for event in envelope["data"] if event["event"] == "$deep_link_opened"
            )
            deep_link["eventProperties"] = {
                "url": "https://example.com/landing?utm_source=demo&utm_medium=android&gclid=demoGclid"
            }
            path.write_text(json.dumps(envelope))
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_patient_field_on_deep_link_event(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            path = Path(directory) / "001_ingest.json"
            envelope = json.loads(path.read_text())
            deep_link = next(
                event for event in envelope["data"] if event["event"] == "$deep_link_opened"
            )
            deep_link["eventProperties"] = {"patient_email": "secret"}
            path.write_text(json.dumps(envelope))
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_earlier_raw_url_when_later_deep_link_is_clean(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            path = Path(directory) / "001_ingest.json"
            envelope = json.loads(path.read_text())
            earlier = deepcopy(next(event for event in envelope["data"] if event["event"] == "$deep_link_opened"))
            earlier["distinct_id"] = "earlier-deep-link"
            earlier["eventProperties"] = {
                "url": "https://example.com/landing?utm_source=demo"
            }
            envelope["data"].insert(4, earlier)
            path.write_text(json.dumps(envelope))
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_earlier_patient_field_when_later_deep_link_is_clean(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            path = Path(directory) / "001_ingest.json"
            envelope = json.loads(path.read_text())
            earlier = deepcopy(next(event for event in envelope["data"] if event["event"] == "$deep_link_opened"))
            earlier["distinct_id"] = "earlier-deep-link"
            earlier["eventProperties"] = {"patient_email": "secret"}
            envelope["data"].insert(4, earlier)
            path.write_text(json.dumps(envelope))
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_earlier_missing_attribution_when_later_deep_link_is_clean(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            path = Path(directory) / "001_ingest.json"
            envelope = json.loads(path.read_text())
            earlier = deepcopy(next(event for event in envelope["data"] if event["event"] == "$deep_link_opened"))
            earlier["distinct_id"] = "earlier-deep-link"
            del earlier["defaultProperties"]["utm_source"]
            envelope["data"].insert(4, earlier)
            path.write_text(json.dumps(envelope))
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_missing_opt_in_event(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            path = Path(directory) / "001_ingest.json"
            envelope = json.loads(path.read_text())
            envelope["data"] = [
                event for event in envelope["data"] if event["event"] != "$opt_in"
            ]
            path.write_text(json.dumps(envelope))
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")
