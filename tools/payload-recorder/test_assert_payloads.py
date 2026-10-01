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
