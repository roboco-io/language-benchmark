#!/usr/bin/env python3
"""EXP-001 런타임 측정을 Fargate Spot(ARM64)에서 병렬로 수행한다.

run마다 (1) 로컬에서 local_prep.sh를 순차 실행해 빌드 시간·이미지 크기·정적 분석을 재고 이미지를 ECR에 올린 뒤,
(2) 앱 태스크(2 vCPU, 앱 컨테이너 메모리 1 GiB 하드 제한 + probe 사이드카)와 부하 태스크(4 vCPU, k6·시드)를
같은 서브넷에 띄워 runner.sh가 3-6단계를 수행하게 하고, (3) S3의 산출물을 $BASE/runtime-fg-<run>/에 합친다.
로컬 빌드는 한 번에 하나씩(빌드 시간 측정 보호), Fargate 측정은 최대 --parallel개까지 동시에 돈다.
Spot 회수나 태스크 기동 실패면 같은 run을 최대 --attempts번까지 새 태스크로 다시 측정하고 시도 기록을 attempts.csv에 남긴다.

사용: AWS_PROFILE=roboco python3 orchestrate.py <harness BASE> [--runs r1,r2] [--parallel 20] [--attempts 3]
"""
import argparse
import csv
import os
import queue
import random
import subprocess
import sys
import threading
import time

import boto3
from botocore.config import Config

REGION = "us-east-1"
ACCT = "779411790546"
CLUSTER = "langbench"
ECR = f"{ACCT}.dkr.ecr.{REGION}.amazonaws.com/langbench"
TOOLS = f"{ECR}:tools"
BUCKET = f"langbench-exp001-{ACCT}"
EXEC_ROLE = f"arn:aws:iam::{ACCT}:role/langbench-exec"
TASK_ROLE = f"arn:aws:iam::{ACCT}:role/langbench-task"
SG = "sg-09b24a70b3cba5f6b"
SUBNETS = ["subnet-0c03e2cc84bdee50c", "subnet-0dff6bde211fad168", "subnet-0c45f96caa96468ae"]  # 1a, 1b, 1c
PROBE_PORT = 9000
MAX_RELAUNCH = 20  # run당 CPU 세대 불일치 교체 상한
HERE = os.path.dirname(os.path.abspath(__file__))

# 실험 머신의 DNS가 간헐적으로 실패하므로(2026-10-18 16:40, 17:10 관측) 연결 오류도 넉넉히 재시도한다.
CFG = Config(retries={"max_attempts": 30, "mode": "standard"}, connect_timeout=10, read_timeout=30)
ecs = boto3.client("ecs", region_name=REGION, config=CFG)
s3 = boto3.client("s3", region_name=REGION, config=CFG)
LOCK = threading.Lock()


def log(base, msg):
    line = f"{time.strftime('%Y-%m-%d %H:%M:%S')} {msg}"
    with LOCK:
        with open(os.path.join(base, "fargate.log"), "a") as f:
            f.write(line + "\n")
    print(line, flush=True)


def logcfg(prefix):
    return {"logDriver": "awslogs", "options": {"awslogs-group": "/langbench", "awslogs-region": REGION,
                                                "awslogs-stream-prefix": prefix}}


def register_app(run, port):
    family = f"lb-app-{run}"
    ecs.register_task_definition(
        family=family, networkMode="awsvpc", requiresCompatibilities=["FARGATE"], cpu="2048", memory="4096",
        runtimePlatform={"cpuArchitecture": "ARM64", "operatingSystemFamily": "LINUX"},
        executionRoleArn=EXEC_ROLE,
        containerDefinitions=[
            {"name": "probe", "image": TOOLS, "essential": True, "memoryReservation": 128,
             "command": ["python3", "/opt/lb/probe.py"],
             "environment": [{"name": "APP_PORT", "value": str(port)}, {"name": "PROBE_PORT", "value": str(PROBE_PORT)}],
             "logConfiguration": logcfg(f"probe-{run}")},
            {"name": "app", "image": f"{ECR}:{run}", "essential": True, "memory": 1024,
             "environment": [{"name": "PORT", "value": str(port)}],
             "dependsOn": [{"containerName": "probe", "condition": "START"}],
             "logConfiguration": logcfg(f"app-{run}")},
        ], tags=[{"key": "project", "value": "langbench-exp001"}])
    return family


def register_load():
    ecs.register_task_definition(
        family="lb-load", networkMode="awsvpc", requiresCompatibilities=["FARGATE"], cpu="4096", memory="8192",
        runtimePlatform={"cpuArchitecture": "ARM64", "operatingSystemFamily": "LINUX"},
        executionRoleArn=EXEC_ROLE, taskRoleArn=TASK_ROLE,
        containerDefinitions=[{"name": "runner", "image": TOOLS, "essential": True,
                               "command": ["bash", "/opt/lb/runner.sh"], "logConfiguration": logcfg("load")}],
        tags=[{"key": "project", "value": "langbench-exp001"}])
    return "lb-load"


def run_task(family, subnet, overrides=None):
    kw = dict(cluster=CLUSTER, taskDefinition=family,
              capacityProviderStrategy=[{"capacityProvider": "FARGATE_SPOT", "weight": 1}],
              networkConfiguration={"awsvpcConfiguration": {"subnets": [subnet], "securityGroups": [SG],
                                                            "assignPublicIp": "ENABLED"}},
              tags=[{"key": "project", "value": "langbench-exp001"}])
    if overrides:
        kw["overrides"] = overrides
    for _ in range(30):  # 용량 부족·launch rate 제한이면 잠시 뒤 재시도
        r = ecs.run_task(**kw)
        if r["tasks"]:
            return r["tasks"][0]["taskArn"]
        time.sleep(20)
    raise RuntimeError(f"run_task 실패: {r.get('failures')}")


def describe(arn):
    return ecs.describe_tasks(cluster=CLUSTER, tasks=[arn])["tasks"][0]


def wait_running(arn, timeout=600):
    t0 = time.time()
    while time.time() - t0 < timeout:
        t = describe(arn)
        if t["lastStatus"] == "RUNNING":
            return t
        if t["lastStatus"] in ("STOPPED", "DEPROVISIONING"):
            return t
        time.sleep(5)
    return describe(arn)


def private_ip(task):
    for a in task.get("attachments", []):
        for d in a.get("details", []):
            if d["name"] == "privateIPv4Address":
                return d["value"]
    return None


def stop(arn, reason):
    try:
        ecs.stop_task(cluster=CLUSTER, task=arn, reason=reason)
    except Exception:
        pass


def download(prefix, dest):
    os.makedirs(dest, exist_ok=True)
    n = 0
    for page in s3.get_paginator("list_objects_v2").paginate(Bucket=BUCKET, Prefix=prefix + "/"):
        for o in page.get("Contents", []):
            s3.download_file(BUCKET, o["Key"], os.path.join(dest, os.path.basename(o["Key"])))
            n += 1
    return n


def attempt_row(base, row):
    path = os.path.join(base, "fargate-attempts.csv")
    new = not os.path.exists(path)
    with LOCK:
        with open(path, "a", newline="") as f:
            w = csv.writer(f)
            if new:
                w.writerow(["run", "attempt", "start", "end", "app_stop", "load_stop", "load_exit", "files", "outcome"])
            w.writerow(row)


def measure(base, run, attempts, tag, soak):
    out = os.path.join(base, f"runtime-fg-{run}")
    port = 40000 + random.randrange(10000)
    family = register_app(run, port)
    k = 0
    relaunch = 0  # CPU 세대 불일치로 측정 전에 바꾼 태스크 수(시도로 세지 않는다)
    while k < attempts:
        k += 1
        subnet = SUBNETS[hash((run, k, relaunch)) % len(SUBNETS)]
        start = time.strftime("%H:%M:%S")
        app = run_task(family, subnet)
        t = wait_running(app)
        ip = private_ip(t)
        if t["lastStatus"] != "RUNNING" or not ip:
            reason = f"{t.get('stopCode')}: {t.get('stoppedReason')}"
            log(base, f"{run} a{k} 앱 태스크 기동 실패 — {reason}")
            stop(app, "app start failed")
            attempt_row(base, [run, k, start, time.strftime("%H:%M:%S"), reason, "", "", 0, "app-start-failed"])
            continue
        prefix = f"runtime/{tag}/{run}/a{k}"
        env = [{"name": "RUN", "value": f"{tag}/{run}/a{k}"}, {"name": "APP_URL", "value": f"http://{ip}:{port}"},
               {"name": "PROBE_URL", "value": f"http://{ip}:{PROBE_PORT}"}, {"name": "BUCKET", "value": BUCKET}]
        if soak:
            env.append({"name": "SOAK_SEC", "value": str(soak)})
        while True:  # 부하 태스크 CPU 불일치(42)면 부하 태스크만 새로 띄운다
            load = run_task("lb-load", subnet, {"containerOverrides": [{"name": "runner", "environment": env}]})
            log(base, f"{run} a{k} 앱 {app.split('/')[-1]} ({ip}:{port}) / 부하 {load.split('/')[-1]} 시작")
            app_stop = ""
            while True:
                lt = describe(load)
                if lt["lastStatus"] == "STOPPED":
                    break
                at = describe(app)
                if at["lastStatus"] == "STOPPED" and not app_stop:
                    app_stop = f"{at.get('stopCode')}: {at.get('stoppedReason')}"
                    log(base, f"{run} a{k} 앱 태스크 중지 — {app_stop}")
                time.sleep(15)
            code = (lt.get("containers") or [{}])[0].get("exitCode")
            if code == 42 and relaunch < MAX_RELAUNCH and not app_stop:
                relaunch += 1
                log(base, f"{run} a{k} 부하 태스크 CPU 세대 불일치 — 부하 태스크 교체 ({relaunch})")
                continue
            break
        if code == 43 and relaunch < MAX_RELAUNCH:
            relaunch += 1
            stop(app, "cpu generation mismatch")
            log(base, f"{run} a{k} 앱 태스크 CPU 세대 불일치 — 앱 태스크 교체 ({relaunch})")
            k -= 1  # 측정 전 교체는 시도로 세지 않는다
            continue
        at = describe(app)
        if not app_stop and at["lastStatus"] == "STOPPED":
            app_stop = f"{at.get('stopCode')}: {at.get('stoppedReason')}"
        stop(app, "measurement finished")
        load_stop = f"{lt.get('stopCode')}: {lt.get('stoppedReason')}"
        spot = "SpotInterruption" in app_stop or "SpotInterruption" in load_stop
        n = download(prefix, os.path.join(base, "fargate-raw", run, f"a{k}"))
        outcome = "spot-interrupted" if spot else ("ok" if code == 0 else ("cpu-unavailable" if code in (42, 43) else "failed"))
        attempt_row(base, [run, k, start, time.strftime("%H:%M:%S"), app_stop, load_stop, code, n, outcome])
        log(base, f"{run} a{k} 종료 exit={code} files={n} outcome={outcome} relaunch={relaunch}")
        if spot or n == 0:
            continue  # Spot 회수·CPU 세대 확보 실패·산출물 없음 → 새 태스크로 재측정
        # 측정 실패(앱 문제)도 그 시도의 결과로 확정한다. 로컬과 같은 규칙(error.txt).
        # error.txt는 덮어쓰지 않고 이어 붙인다: 1부(원격) 실패 문구를 앞에, 로컬 2부의 scan: 줄을 뒤에 둔다.
        os.makedirs(out, exist_ok=True)
        raw = os.path.join(base, "fargate-raw", run, f"a{k}")
        local_err = os.path.join(out, "error.txt")
        prev = open(local_err).read() if os.path.exists(local_err) else ""
        for f in os.listdir(raw):
            if f != "error.txt":
                subprocess.run(["cp", os.path.join(raw, f), os.path.join(out, f)], check=True)
        remote = open(os.path.join(raw, "error.txt")).read() if os.path.exists(os.path.join(raw, "error.txt")) else ""
        if remote or prev:
            with open(local_err, "w") as fh:
                fh.write(remote + prev)
        return outcome
    log(base, f"{run} {attempts}회 시도 모두 무효 — Fargate 측정 없음")
    return "no-result"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("base")
    ap.add_argument("--runs")
    ap.add_argument("--parallel", type=int, default=20)
    ap.add_argument("--attempts", type=int, default=3)
    ap.add_argument("--skip-prep", action="store_true")
    ap.add_argument("--tag", default="main", help="S3 접두어 구분(파일럿은 pilot)")
    ap.add_argument("--soak-sec", type=int, help="파일럿 전용: soak 길이 축소")
    a = ap.parse_args()
    base = os.path.abspath(os.path.expanduser(a.base))
    runs = a.runs.split(",") if a.runs else open(os.path.join(base, "run-order.txt")).read().split()
    register_load()
    q = queue.Queue()
    results = {}

    def worker():
        while True:
            run = q.get()
            if run is None:
                return
            try:
                results[run] = measure(base, run, a.attempts, a.tag, a.soak_sec)
            except Exception as e:
                results[run] = f"error: {e}"
                log(base, f"{run} 오케스트레이터 오류: {e}")

    threads = [threading.Thread(target=worker, daemon=True) for _ in range(a.parallel)]
    for t in threads:
        t.start()
    for run in runs:
        if not a.skip_prep:
            log(base, f"{run} 로컬 준비 시작")
            env = dict(os.environ, ECR_REPO=ECR)
            r = subprocess.run(["bash", os.path.join(HERE, "local_prep.sh"), base, os.path.join(base, f"app-{run}"), run],
                               env=env, stdout=open(os.path.join(base, f"runtime-fg-{run}.stdout"), "w"),
                               stderr=subprocess.STDOUT)
            err = os.path.join(base, f"runtime-fg-{run}", "error.txt")
            first = open(err).readline().strip() if os.path.exists(err) else ""
            log(base, f"{run} 로컬 준비 끝 rc={r.returncode} {first}")
            # 빌드·push가 실패하면 이미지가 없으므로 Fargate 측정을 하지 않는다(로컬 runtime.sh와 같은 빈 칸).
            if first.startswith(("build", "push", "export")):
                results[run] = "prep-failed"
                continue
        q.put(run)
    for _ in threads:
        q.put(None)
    for t in threads:
        t.join()
    log(base, "ALL DONE " + " ".join(f"{k}={v}" for k, v in sorted(results.items())))


if __name__ == "__main__":
    sys.exit(main())
