import csv
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import time
import unittest

from test_measure import DOCKER, FAKES, HURL, SKILL


@unittest.skipUnless(DOCKER, "docker 미기동")
class DriverGateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.home = os.path.join(self.tmp, "home")
        self.base = os.path.join(self.home, "experiments", "langbench-exp901")
        os.makedirs(self.base)
        for f in os.listdir(os.path.join(SKILL, "scripts")):
            p = os.path.join(SKILL, "scripts", f)
            if os.path.isfile(p):
                shutil.copy(p, self.base)
        with open(os.path.join(self.base, "PROMPT-typescript.md"), "w") as f:
            f.write("test prompt\n")
        # R2: 단가 0인 테스트 사양으로 cost_guard가 0을 돌려주게 한다(가드 자체는 약화하지 않는다).
        price = {"input": 0, "output": 0, "cache_read": 0, "cache_write": 0, "tiers": []}
        with open(os.path.join(self.base, "model-specs.json"), "w") as f:
            json.dump({"claude-sonnet-5-5": {"pricing": price}}, f)
        with open(os.path.join(self.base, "bench.env"), "w") as f:
            f.write('''EXP=901
HARNESS=claude-native
CONDITIONS="sonnet55:claude-sonnet-5-5"
MAX_ITER=1
FU_MAX_ITER=1
FU_MAX_SEC=600
MAX_SEC=600
STALL_SEC=120
START_SEC=60
DOCKER_BUILD_SEC=300
RUN_BUDGET_USD=2
EXP_BUDGET_USD=10
PRICE_IN=0
PRICE_OUT=0
PRICE_CACHE_READ=0
PRICE_TIERS=""
''')
        self.fakebin = os.path.join(self.tmp, "bin")
        os.makedirs(self.fakebin)
        shutil.copy(os.path.join(FAKES, "claude"), self.fakebin)

    def tearDown(self):
        ids = subprocess.run(["docker", "ps", "-aq", "--filter", "name=lb-sonnet55-typescript"],
                             capture_output=True, text=True).stdout.split()
        if ids:
            subprocess.run(["docker", "rm", "-f", *ids], capture_output=True)
        subprocess.run(["bash", "-c", "docker image rm -f $(docker image ls 'lb/sonnet55-typescript*' -q) 2>/dev/null"],
                       capture_output=True)

    def drive(self, run, app_src):
        env = dict(os.environ, HOME=self.home, HURL_DIRS=HURL, FAKE_CLAUDE_COPY=app_src,
                   PATH=self.fakebin + os.pathsep + os.environ["PATH"])
        r = subprocess.run(["bash", os.path.join(self.base, "driver.sh"), run],
                           capture_output=True, text=True, env=env, timeout=600)
        with open(os.path.join(self.base, "metrics-%s.csv" % run)) as f:
            rows = list(csv.reader(f))
        return r, rows[-1]

    def test_gate_requires_both(self):
        # Dockerfile만 없는 사본: 호스트는 통과하지만 Docker 채점이 0,0이라 기각된다.
        nodocker = os.path.join(self.tmp, "app-nodocker")
        shutil.copytree(os.path.join(FAKES, "app-ok"), nodocker)
        os.remove(os.path.join(nodocker, "Dockerfile"))
        r, row = self.drive("sonnet55-typescript-1", nodocker)
        # 열: iter,종료시각,exit,host_succ,host_reqs,docker_succ,docker_reqs,완료 선언,게이트
        self.assertEqual(row[3:9], ["1", "1", "0", "0", "1", "rejected"], (r.stderr, row))
        r, row = self.drive("sonnet55-typescript-2", os.path.join(FAKES, "app-ok"))
        self.assertEqual(row[3:9], ["1", "1", "1", "1", "1", "pass"], (r.stderr, row))

    def test_bench_env_start_sec_reaches_measure(self):
        # bench.env의 START_SEC=5가 measure.sh까지 전달되면 40초 뒤 LISTEN하는 앱은 0,0이다(기본 600이면 1,1).
        with open(os.path.join(self.base, "bench.env")) as f:
            env_txt = f.read().replace("START_SEC=60", "START_SEC=5")
        with open(os.path.join(self.base, "bench.env"), "w") as f:
            f.write(env_txt)
        shutil.copytree(os.path.join(FAKES, "app-ok"), os.path.join(self.base, "app-ok"))
        r, row = self.drive("sonnet55-typescript-3", os.path.join(FAKES, "app-slow"))
        self.assertEqual(row[3:5], ["0", "0"], (r.stderr, row))
        self.assertEqual(row[-1], "rejected")

    def test_dead_start_sh_ends_iteration_early(self):
        # driver 경로(cwd=리포)에서 즉시 죽는 start.sh는 START_SEC=300을 다 기다리지 않는다.
        with open(os.path.join(self.base, "bench.env")) as f:
            env_txt = f.read().replace("START_SEC=60", "START_SEC=300")
        with open(os.path.join(self.base, "bench.env"), "w") as f:
            f.write(env_txt)
        t0 = time.time()
        r, row = self.drive("sonnet55-typescript-4", os.path.join(FAKES, "app-dies"))
        self.assertLess(time.time() - t0, 60, (r.stderr, row))
        self.assertEqual(row[3:5], ["0", "0"])

    def followup_setup(self, run, hurl_dirs=True):
        """원 run의 앱(.git 포함, .ralph-done 있음)과 채점 디렉터리 2개를 BASE에 만든다."""
        with open(os.path.join(self.base, "PROMPT-followup.md"), "w") as f:
            f.write("followup prompt\n")
        app = os.path.join(self.base, "app-" + run)
        shutil.copytree(os.path.join(FAKES, "app-ok"), app)
        git = ["git", "-c", "user.name=t", "-c", "user.email=t@t", "-C", app]
        subprocess.run(["git", "-C", app, "init", "-q"], check=True)
        open(os.path.join(app, ".ralph-done"), "w").close()
        subprocess.run(git + ["add", "-A"], check=True)
        subprocess.run(git + ["commit", "-qm", "orig"], check=True)
        for name, src in (("harness-hurl", "ping.hurl"), ("followup-hurl", "ping2.hurl")):
            d = os.path.join(self.base, name)
            os.makedirs(d)
            shutil.copy(os.path.join(HURL, "ping.hurl"), os.path.join(d, src))
        return app

    def tree_hash(self, root):
        h = hashlib.md5()
        for dp, dn, fn in sorted(os.walk(root)):
            dn.sort()
            for n in sorted(fn):
                p = os.path.join(dp, n)
                h.update(os.path.relpath(p, root).encode())
                with open(p, "rb") as f:
                    h.update(f.read())
        return h.hexdigest()

    def drive_followup(self, run, extra=None):
        env = dict(os.environ, HOME=self.home, FOLLOWUP="1",
                   PATH=self.fakebin + os.pathsep + os.environ["PATH"], **(extra or {}))
        env.pop("HURL_DIRS", None)
        r = subprocess.run(["bash", os.path.join(self.base, "driver.sh"), run],
                           capture_output=True, text=True, env=env, timeout=600)
        with open(os.path.join(self.base, "metrics-%s-fu.csv" % run)) as f:
            rows = list(csv.reader(f))
        return r, rows

    def test_followup_copies_and_gates_on_both_dirs(self):
        run = "sonnet55-typescript-1"
        orig = self.followup_setup(run)
        before = self.tree_hash(orig)
        r, rows = self.drive_followup(run, {"FAKE_CLAUDE_COPY": os.path.join(FAKES, "app-ok")})
        self.assertEqual(self.tree_hash(orig), before, "원 run 앱이 바뀌었다")
        fu = os.path.join(self.base, "app-%s-fu" % run)
        self.assertTrue(os.path.isdir(os.path.join(fu, ".git")), r.stderr)
        # 두 디렉터리(harness-hurl 1 + followup-hurl 1)를 호스트·Docker 모두 채점한다.
        self.assertEqual(rows[-1][3:9], ["2", "2", "2", "2", "1", "pass"], (r.stderr, rows))
        with open(os.path.join(fu, "PROMPT.md")) as f:
            self.assertEqual(f.read(), "followup prompt\n")
        self.assertTrue(os.path.exists(os.path.join(self.base, "done-%s-fu" % run)))
        self.assertFalse(os.path.exists(os.path.join(self.base, "done-%s" % run)))

    def test_followup_respects_iter_cap(self):
        run = "sonnet55-typescript-1"
        orig = self.followup_setup(run)
        os.remove(os.path.join(orig, "Dockerfile"))  # Docker 빌드를 건너뛰어 빠르게 한다
        subprocess.run(["git", "-C", orig, "-c", "user.name=t", "-c", "user.email=t@t",
                        "commit", "-aqm", "rm"], check=True)
        # bench.env의 MAX_ITER=1과 다른 FU_MAX_ITER=2가 적용돼야 한다(driver가 bench.env를 source한다).
        with open(os.path.join(self.base, "bench.env")) as f:
            env_txt = f.read().replace("FU_MAX_ITER=1", "FU_MAX_ITER=2")
        with open(os.path.join(self.base, "bench.env"), "w") as f:
            f.write(env_txt)
        r, rows = self.drive_followup(run)
        self.assertEqual(len(rows), 2, (r.stderr, rows))
        self.assertEqual(rows[-1][-1], "na")
        # 복사본에서 원 run의 .ralph-done이 지워져 있어야 한다
        self.assertFalse(os.path.exists(os.path.join(self.base, "app-%s-fu" % run, ".ralph-done")))


if __name__ == "__main__":
    unittest.main()
