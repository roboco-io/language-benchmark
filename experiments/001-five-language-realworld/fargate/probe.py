#!/usr/bin/env python3
"""앱 태스크 사이드카: 콜드 스타트·메모리·생존 여부를 측정해 HTTP로 제공한다.

앱 컨테이너는 이 컨테이너의 START에 의존하므로 probe가 먼저 뜬다. probe는 ECS 태스크 메타데이터(v4)에서
앱 컨테이너의 StartedAt이 나타날 때까지 기다리면서 127.0.0.1:APP_PORT/api/tags를 50ms 간격으로 폴링한다.
콜드 스타트 = 첫 200 시각 - 앱 컨테이너 StartedAt (로컬 runtime.sh는 docker run 직전부터 잰다; 차이는 report에 적는다).
유휴 메모리는 첫 200 10초 뒤 값이다(로컬과 같은 정의).
메모리(MiB)는 docker CLI와 같은 방식으로 usage - inactive_file(cgroup v2) 또는 total_inactive_file(v1)이다.

GET /status → {"cold_start_ms", "idle_mem", "error", "cpu_part", "az"}
GET /mem    → {"mem": <MiB|null>, "running": <bool>}
"""
import json
import os
import threading
import time
import urllib.request
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

META = os.environ["ECS_CONTAINER_METADATA_URI_V4"]
APP_PORT = os.environ["APP_PORT"]
APP_NAME = os.environ.get("APP_CONTAINER", "app")
PROBE_PORT = int(os.environ.get("PROBE_PORT", "9000"))
STATE = {"cold_start_ms": None, "idle_mem": None, "error": None, "cpu_part": None, "az": None}


def get_json(url, timeout=2):
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return json.load(r)


def app_container():
    for c in get_json(META + "/task").get("Containers", []):
        if c.get("Name") == APP_NAME:
            return c
    return None


def parse_ts(s):
    # 예: 2026-10-18T07:12:06.123456789Z — 나노초는 마이크로초로 자른다
    s = s.rstrip("Z")
    if "." in s:
        head, frac = s.split(".", 1)
        s = head + "." + frac[:6]
    return datetime.fromisoformat(s + "+00:00").timestamp()


def mem_now():
    c = app_container()
    if not c or not c.get("DockerId"):
        return None
    st = get_json(META + "/task/stats").get(c["DockerId"]) or {}
    ms = st.get("memory_stats") or {}
    usage = ms.get("usage")
    if usage is None:
        return None
    inner = ms.get("stats") or {}
    cache = inner.get("inactive_file", inner.get("total_inactive_file", 0))
    return round((usage - cache) / 1048576, 1)


def running():
    c = app_container()
    return bool(c) and c.get("KnownStatus") == "RUNNING"


def tags_ok():
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{APP_PORT}/api/tags", timeout=1) as r:
            return r.status == 200
    except Exception:
        return False


def measure():
    try:
        with open("/proc/cpuinfo") as f:
            for line in f:
                if line.lower().startswith(("cpu part", "model name")):
                    STATE["cpu_part"] = line.split(":", 1)[1].strip()
                    break
        STATE["az"] = get_json(META + "/task").get("AvailabilityZone")
    except Exception:
        pass
    started = None
    deadline = time.time() + 300
    while time.time() < deadline:
        try:
            c = app_container()
            if c and c.get("StartedAt"):
                started = parse_ts(c["StartedAt"])
        except Exception:
            pass
        if tags_ok():
            t200 = time.time()
            if started is None:  # 200은 왔는데 StartedAt을 아직 못 읽은 경우 한 번 더 읽는다
                try:
                    started = parse_ts(app_container()["StartedAt"])
                except Exception:
                    STATE["error"] = "cold_start: StartedAt 없음"
                    return
            if started is not None and t200 - started > 60:
                STATE["error"] = "cold_start: 60초 안에 GET /api/tags 200 없음"
                return
            STATE["cold_start_ms"] = int((t200 - started) * 1000)
            break
        if started is not None and time.time() - started > 60:
            STATE["error"] = "cold_start: 60초 안에 GET /api/tags 200 없음"
            return
        time.sleep(0.05)
    else:
        STATE["error"] = "cold_start: 앱 컨테이너 시작 안 됨"
        return
    time.sleep(10)
    try:
        STATE["idle_mem"] = mem_now()
    except Exception as e:
        STATE["error"] = f"idle_mem: {e}"
    if STATE["idle_mem"] is None and not STATE["error"]:
        STATE["error"] = "idle_mem"


class H(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/status":
            body = STATE
        elif self.path == "/mem":
            try:
                body = {"mem": mem_now(), "running": running()}
            except Exception:
                body = {"mem": None, "running": False}
        else:
            self.send_response(404); self.end_headers(); return
        data = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    threading.Thread(target=measure, daemon=True).start()
    ThreadingHTTPServer(("0.0.0.0", PROBE_PORT), H).serve_forever()
