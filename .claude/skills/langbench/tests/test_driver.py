import csv
import json
import os
import shutil
import subprocess
import tempfile
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


if __name__ == "__main__":
    unittest.main()
