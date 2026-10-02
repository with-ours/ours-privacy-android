import json
import tempfile
import threading
import unittest
from http.server import HTTPServer
from urllib.request import Request, urlopen

from server import RecorderHandler


class RecorderStatusTest(unittest.TestCase):
    def test_events_endpoint_reports_captured_post(self):
        with tempfile.TemporaryDirectory() as directory:
            RecorderHandler.out_dir = directory
            server = HTTPServer(("127.0.0.1", 0), RecorderHandler)
            thread = threading.Thread(target=server.serve_forever)
            thread.start()
            try:
                base = f"http://127.0.0.1:{server.server_port}"
                envelope = {
                    "token": "e2e-token",
                    "data": [{"event": "demo_event"}],
                }
                request = Request(
                    base + "/ingest",
                    data=json.dumps(envelope).encode(),
                    method="POST",
                )
                with urlopen(request) as response:
                    self.assertEqual(response.status, 200)
                with urlopen(base + "/events") as response:
                    self.assertEqual(
                        json.load(response),
                        {"events": ["demo_event"]},
                    )
            finally:
                server.shutdown()
                thread.join()
                server.server_close()
