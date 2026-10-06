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
                "visitor_id": "visitor",
                "distinct_id": "one",
                "eventProperties": {"source": "demo"},
                "defaultProperties": {"version": version},
            },
            {
                "event": "$identify",
                "visitor_id": "visitor",
                "distinct_id": "two",
                "eventProperties": None,
                "userProperties": {"external_id": "demo_user_1", "email": "demo@example.com"},
                "defaultProperties": {"version": version},
            },
            {
                "event": "view_item",
                "visitor_id": "visitor",
                "distinct_id": "three",
                "eventProperties": {"sku": "ABC-001"},
                "userProperties": {"phone_number": "+1-555-0100"},
                "defaultProperties": {"version": version},
            },
            {
                "event": "$opt_in",
                "visitor_id": "visitor",
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
        mobile_defaults = {
            "version": version,
            "sid": "session-one",
            "mobile_session_started_at": "2026-10-06T12:00:00.000Z",
            "mobile_occurred_at": "2026-10-06T12:00:01.000Z",
            "mobile_platform": "android",
            "mobile_contract_version": 1,
            "app_version": "1.0",
            "app_build": "1",
        }
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
                    "eventProperties": properties,
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
                booking["defaultProperties"]["app_build"] = "999"
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
                        screen["defaultProperties"][field] = value
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
                    "visitor_id": "visitor",
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
