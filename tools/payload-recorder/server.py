#!/usr/bin/env python3
"""
Minimal HTTP recorder for OursPrivacy Android SDK payloads.

Listens for POST requests and writes each body to --out as a pretty-printed
JSON file named <timestamp>-<seq>_ingest.json.

Usage:
    python3 tools/payload-recorder/server.py --port 8765 --out /tmp/op-captures
"""

import argparse
import http.server
import json
import os
import sys
import time

seq = 0


class RecorderHandler(http.server.BaseHTTPRequestHandler):
    out_dir: str = "/tmp/op-captures"

    def do_POST(self):
        global seq
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)

        try:
            parsed = json.loads(body)
            pretty = json.dumps(parsed, indent=2)
        except json.JSONDecodeError:
            pretty = body.decode("utf-8", errors="replace")

        ts = int(time.time() * 1000)
        seq += 1
        filename = f"{ts}-{seq:03d}_ingest.json"
        path = os.path.join(self.out_dir, filename)
        with open(path, "w") as f:
            f.write(pretty)

        print(f"[recorder] {self.path}  →  {filename}", flush=True)
        print(pretty[:400], flush=True)

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"status":"ok"}')

    def log_message(self, fmt, *args):
        pass  # suppress default access log


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--out", default="/tmp/op-captures")
    args = parser.parse_args()

    os.makedirs(args.out, exist_ok=True)
    RecorderHandler.out_dir = args.out

    server = http.server.HTTPServer(("0.0.0.0", args.port), RecorderHandler)
    print(f"[recorder] listening on :{args.port}  →  {args.out}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[recorder] stopped")
        sys.exit(0)


if __name__ == "__main__":
    main()
