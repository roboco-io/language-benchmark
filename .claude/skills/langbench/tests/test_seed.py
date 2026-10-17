import collections
import json
import os
import re
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

SEED = os.path.realpath(os.path.join(os.path.dirname(__file__), "..", "scripts", "seed.py"))
COUNTS = collections.Counter()


class Fake(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def reply(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n)
        path = self.path
        # Fastify처럼: 본문 없는 요청에 Content-Type: application/json이 있으면 400
        if not raw and "json" in (self.headers.get("Content-Type") or ""):
            COUNTS["bodyless_with_content_type"] += 1
            return self.reply(400, {})
        body = json.loads(raw or b"{}")
        if path == "/api/users":
            COUNTS["user"] += 1
            u = body["user"]["username"]
            return self.reply(201, {"user": {"username": u, "token": "tok-" + u}})
        if not self.headers.get("Authorization", "").startswith("Token tok-"):
            return self.reply(401, {})
        if path == "/api/articles":
            COUNTS["article"] += 1
            return self.reply(201, {"article": {"slug": "s-%d" % COUNTS["article"]}})
        if re.fullmatch(r"/api/articles/[^/]+/comments", path):
            COUNTS["comment"] += 1
            return self.reply(201, {"comment": {"id": 1}})
        if re.fullmatch(r"/api/articles/[^/]+/favorite", path):
            COUNTS["favorite"] += 1
            return self.reply(200, {"article": {}})
        if re.fullmatch(r"/api/profiles/[^/]+/follow", path):
            COUNTS["follow"] += 1
            return self.reply(200, {"profile": {}})
        self.reply(404, {})


class SeedTest(unittest.TestCase):
    def test_seed_against_fake(self):
        COUNTS.clear()
        srv = HTTPServer(("127.0.0.1", 0), Fake)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        self.addCleanup(srv.shutdown)
        with tempfile.TemporaryDirectory() as d:
            out = os.path.join(d, "out.json")
            r = subprocess.run(["python3", SEED, "http://127.0.0.1:%d" % srv.server_port, out],
                               capture_output=True, text=True, timeout=120)
            self.assertEqual(r.returncode, 0, r.stderr)
            with open(out) as f:
                data = json.load(f)
        self.assertEqual(COUNTS["bodyless_with_content_type"], 0)
        self.assertEqual(len(data["users"][0]["favorited"]), 5)
        self.assertEqual(dict(COUNTS), {"user": 50, "article": 500, "comment": 500, "follow": 250, "favorite": 250})
        self.assertEqual(len(data["slugs"]), 500)
        self.assertEqual(len(data["users"]), 50)
        self.assertEqual(data["users"][0]["username"], "lb0")
        self.assertEqual(len(data["tags"]), 20)

    def test_failure_exit_code(self):
        # 서버가 없으면 종료 코드 1
        with tempfile.TemporaryDirectory() as d:
            r = subprocess.run(["python3", SEED, "http://127.0.0.1:1", os.path.join(d, "o.json")],
                               capture_output=True, text=True, timeout=60)
        self.assertEqual(r.returncode, 1)


if __name__ == "__main__":
    unittest.main()
