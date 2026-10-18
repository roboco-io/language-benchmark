# 테스트용 가짜 앱: FrankenPHP(Caddy)처럼 관리 포트를 먼저 열고(모든 요청에 404) 2초 뒤 PORT에서 앱을 연다.
import os
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer


class Admin(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(404)
        self.end_headers()

    def log_message(self, *a):
        pass


class App(Admin):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"tags":[]}')


admin = HTTPServer(("127.0.0.1", 0), Admin)
threading.Thread(target=admin.serve_forever, daemon=True).start()
time.sleep(2)
HTTPServer(("", int(os.environ["PORT"])), App).serve_forever()
