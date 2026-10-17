import collections
import json
import os
import re
import shutil
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from test_measure import SKILL

K6 = shutil.which("k6")
LOADJS = os.path.join(SKILL, "assets", "k6", "load.js")
LOCK = threading.Lock()
COUNTS = collections.Counter()
LIVE = {"articles": set(), "comments": set()}


class Fake(BaseHTTPRequestHandler):
    """쓰기 요청의 작성·삭제 수를 세는 최소 RealWorld 서버. 조회는 모두 200 {}다."""
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def reply(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n)

    def do_GET(self):
        self.reply(200, {})

    def do_POST(self):
        self.body()
        with LOCK:
            if self.path == "/api/articles":
                COUNTS["article_post"] += 1
                slug = "load-%d" % COUNTS["article_post"]
                LIVE["articles"].add(slug)
                return self.reply(201, {"article": {"slug": slug}})
            if re.fullmatch(r"/api/articles/[^/]+/comments", self.path):
                COUNTS["comment_post"] += 1
                cid = COUNTS["comment_post"]
                LIVE["comments"].add(cid)
                return self.reply(201, {"comment": {"id": cid}})
            COUNTS["other_post"] += 1
        self.reply(200, {"article": {}})

    def do_DELETE(self):
        self.body()
        with LOCK:
            m = re.fullmatch(r"/api/articles/[^/]+/comments/(\d+)", self.path)
            if m:
                COUNTS["comment_delete"] += 1
                LIVE["comments"].discard(int(m.group(1)))
                return self.reply(200, {})
            m = re.fullmatch(r"/api/articles/([^/]+)", self.path)
            if m:
                COUNTS["article_delete"] += 1
                LIVE["articles"].discard(m.group(1))
                return self.reply(200, {})
            COUNTS["other_delete"] += 1
        self.reply(200, {})


@unittest.skipUnless(K6, "k6 없음")
class LoadWritePairsTest(unittest.TestCase):
    def test_writes_are_undone(self):
        # R23: 아티클·코멘트 작성은 같은 VU가 곧바로 삭제해, 부하가 끝나면 남은 데이터가 없다.
        COUNTS.clear()
        LIVE["articles"].clear()
        LIVE["comments"].clear()
        srv = ThreadingHTTPServer(("127.0.0.1", 0), Fake)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        self.addCleanup(srv.shutdown)
        with tempfile.TemporaryDirectory() as d:
            seed = os.path.join(d, "seed.json")
            with open(seed, "w") as f:
                json.dump({"users": [{"username": "u%d" % i, "token": "t%d" % i, "favorited": []} for i in range(4)],
                           "slugs": ["s%d" % i for i in range(10)], "tags": ["tag00"]}, f)
            env = dict(os.environ, SEED=seed, BASE="http://127.0.0.1:%d" % srv.server_port, VUS="4", DURATION="3s")
            r = subprocess.run([K6, "run", "--quiet", "--no-color", LOADJS], capture_output=True, text=True,
                               env=env, timeout=120)
        self.assertEqual(r.returncode, 0, r.stderr[-2000:])
        self.assertGreater(COUNTS["article_post"], 0)
        self.assertGreater(COUNTS["comment_post"], 0)
        self.assertEqual(COUNTS["article_delete"], COUNTS["article_post"], dict(COUNTS))
        self.assertEqual(COUNTS["comment_delete"], COUNTS["comment_post"], dict(COUNTS))
        self.assertEqual((LIVE["articles"], LIVE["comments"]), (set(), set()))


if __name__ == "__main__":
    unittest.main()
