# 테스트용 가짜 앱: PORT에서 GET /api/tags에 200을 준다 (표준 라이브러리만 사용).
import os
from http.server import BaseHTTPRequestHandler, HTTPServer


class H(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"tags":[]}')

    def log_message(self, *a):
        pass


HTTPServer(("", int(os.environ["PORT"])), H).serve_forever()
