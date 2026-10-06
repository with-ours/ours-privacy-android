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
                "visitor_id": "visitor",
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
        (Path(directory) / "001_ingest.json").write_text(
            json.dumps({"token": "e2e-token", "data": events})
        )

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
            earlier = deepcopy(envelope["data"][-1])
            earlier["distinct_id"] = "earlier-deep-link"
            earlier["eventProperties"] = {
                "url": "https://example.com/landing?utm_source=demo"
            }
            envelope["data"].insert(-1, earlier)
            path.write_text(json.dumps(envelope))
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_earlier_patient_field_when_later_deep_link_is_clean(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            path = Path(directory) / "001_ingest.json"
            envelope = json.loads(path.read_text())
            earlier = deepcopy(envelope["data"][-1])
            earlier["distinct_id"] = "earlier-deep-link"
            earlier["eventProperties"] = {"patient_email": "secret"}
            envelope["data"].insert(-1, earlier)
            path.write_text(json.dumps(envelope))
            with self.assertRaises(AssertionError):
                validate(Path(directory), "2.0.0")

    def test_rejects_earlier_missing_attribution_when_later_deep_link_is_clean(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_capture(directory, "2.0.0")
            path = Path(directory) / "001_ingest.json"
            envelope = json.loads(path.read_text())
            earlier = deepcopy(envelope["data"][-1])
            earlier["distinct_id"] = "earlier-deep-link"
            del earlier["defaultProperties"]["utm_source"]
            envelope["data"].insert(-1, earlier)
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
