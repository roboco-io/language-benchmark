import glob
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import unittest

from test_measure import FAKES, HURL, SKILL, write_manifest


def count_files(root):
    return sum(len(fn) for _, _, fn in os.walk(root))


class ArchiveSessionsTest(unittest.TestCase):
    def test_rearchive_does_not_nest(self):
        # R20: 두 번째 보관이 sessions-<run>/<enc>/ 아래로 중첩 복사되면 aggregate_tokens가 그 구간을 놓친다.
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp, ignore_errors=True)
        home, base = os.path.join(tmp, "home"), os.path.join(tmp, "base")
        enc = (base + "/app-r1").replace("/", "-").replace(".", "-")
        src = os.path.join(home, ".claude", "projects", enc)
        os.makedirs(os.path.join(src, "sid", "subagents"))
        os.makedirs(base)
        for p in ("a.jsonl", os.path.join("sid", "subagents", "agent-1.jsonl")):
            with open(os.path.join(src, p), "w") as f:
                f.write("{}\n")
        cmd = 'BASE="%s"; source "%s"; archive_sessions r1' % (base, os.path.join(SKILL, "scripts", "common.sh"))
        env = dict(os.environ, HOME=home)
        for _ in range(2):
            subprocess.run(["bash", "-c", cmd], env=env, check=True)
        dst = os.path.join(base, "sessions-r1")
        self.assertFalse(os.path.exists(os.path.join(dst, enc)))
        self.assertEqual(count_files(dst), 2)
        self.assertTrue(os.path.isfile(os.path.join(dst, "sid", "subagents", "agent-1.jsonl")))


class RunAllHoldTest(unittest.TestCase):
    """run-all.sh가 구독 한도 보류(exit 75)에서 배치를 멈추고, 다음 실행에서 보류 run을 처음부터 다시 돌린다(R18)."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.home = os.path.join(self.tmp, "home")
        self.base = os.path.join(self.home, "experiments", "langbench-exp902")
        os.makedirs(self.base)
        for f in os.listdir(os.path.join(SKILL, "scripts")):
            shutil.copy(os.path.join(SKILL, "scripts", f), self.base)
        with open(os.path.join(self.base, "PROMPT-typescript.md"), "w") as f:
            f.write("test prompt\n")
        os.makedirs(os.path.join(self.base, "harness-hurl"))
        shutil.copy(os.path.join(HURL, "ping.hurl"), os.path.join(self.base, "harness-hurl"))
        price = {"input": 0, "output": 0, "cache_read": 0, "cache_write": 0, "tiers": []}
        with open(os.path.join(self.base, "model-specs.json"), "w") as f:
            json.dump({"claude-sonnet-5-5": {"pricing": price}, "claude-opus-5-5": {"pricing": price}}, f)
        with open(os.path.join(self.base, "bench.env"), "w") as f:
            f.write('''EXP=902
HARNESS=claude-native
CONDITIONS="sonnet55:claude-sonnet-5-5 opus55:claude-opus-5-5"
LANGS="typescript"
N=1
MAX_ITER=1
MAX_SEC=600
STALL_SEC=120
START_SEC=5
DOCKER_BUILD_SEC=60
RUN_BUDGET_USD=2
EXP_BUDGET_USD=10
PRICE_IN=0
PRICE_OUT=0
PRICE_CACHE_READ=0
PRICE_TIERS=""
''')
        with open(os.path.join(self.base, "preflight-report.md"), "w") as f:
            f.write("report\n")
        blob = b""
        for n in ("bench.env", "model-specs.json", "preflight-report.md"):
            with open(os.path.join(self.base, n), "rb") as f:
                blob += f.read()
        with open(os.path.join(self.base, "preflight-approved"), "w") as f:
            f.write("hash=%s\n" % hashlib.sha256(blob).hexdigest())
        self.fakebin = os.path.join(self.tmp, "bin")
        os.makedirs(self.fakebin)
        shutil.copy(os.path.join(FAKES, "claude"), self.fakebin)
        write_manifest(self.base)

    def run_all(self, **extra):
        env = dict(os.environ, HOME=self.home, PATH=self.fakebin + os.pathsep + os.environ["PATH"], **extra)
        env.pop("HURL_DIRS", None)
        return subprocess.run(["bash", os.path.join(self.base, "run-all.sh")],
                              capture_output=True, text=True, env=env, timeout=300)

    def path(self, *p):
        return os.path.join(self.base, *p)

    def test_hold_stops_batch_and_rerun_restarts_fresh(self):
        r = self.run_all(FAKE_CLAUDE_LIMIT="stdout")
        self.assertEqual(r.returncode, 75, r.stderr)
        self.assertIn("HOLD: subscription limit", r.stdout)
        holds = [os.path.basename(p)[len("hold-"):] for p in glob.glob(self.path("hold-*"))]
        self.assertEqual(len(holds), 1, holds)
        first = holds[0]
        second = ({"sonnet55-typescript-1", "opus55-typescript-1"} - {first}).pop()
        with open(self.path("run-order.txt")) as f:
            self.assertEqual(f.read().split(), [first, second])  # R26: 기록된 순서대로 돈다
        self.assertFalse(os.path.exists(self.path("app-" + second)), "보류 뒤 다음 run이 시작됨")
        self.assertFalse(os.path.exists(self.path("done-all")))
        with open(self.path("orchestrator.log")) as f:
            self.assertIn("HOLD: subscription limit", f.read())

        r = self.run_all()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertFalse(os.path.exists(self.path("hold-" + first)))
        held = glob.glob(self.path("held", first + "-*"))
        self.assertEqual(len(held), 1)
        self.assertTrue(os.path.isfile(os.path.join(held[0], "hold-" + first)))
        self.assertTrue(os.path.isdir(os.path.join(held[0], "app-" + first)))
        self.assertTrue(os.path.isdir(os.path.join(held[0], "sessions-" + first)))
        for run in (first, second):
            with open(self.path("metrics-%s.csv" % run)) as f:
                self.assertEqual(f.readline().split(",")[0], "1", run)
        self.assertTrue(os.path.exists(self.path("done-all")))


    def test_existing_run_order_is_reused(self):
        # R26: 재실행은 seed를 다시 쓰지 않고 run-order.txt를 그대로 쓴다.
        with open(self.path("run-order.txt"), "w") as f:
            f.write("opus55-typescript-1\nsonnet55-typescript-1\n")
        r = self.run_all(RUN_ORDER_SEED="1")
        self.assertEqual(r.returncode, 0, r.stderr)
        with open(self.path("orchestrator.log")) as f:
            starts = [l.split()[2] for l in f if l.startswith("=== start ")]
        self.assertEqual(starts, ["opus55-typescript-1", "sonnet55-typescript-1"])


class RunOrderTest(unittest.TestCase):
    SCRIPT = os.path.join(SKILL, "scripts", "run_order.py")
    CONDS = "sonnet55:claude-sonnet-5-5 opus55:claude-opus-5-5"
    LANGS = "typescript python go rust cpp"

    def order(self, seed):
        return subprocess.run(["python3", self.SCRIPT, str(seed), self.CONDS, self.LANGS, "3"],
                              capture_output=True, text=True, check=True).stdout.split()

    def test_same_seed_same_order(self):
        a = self.order(20261018)
        self.assertEqual(a, self.order(20261018))
        self.assertEqual(len(a), 30)
        self.assertEqual(len(set(a)), 30)
        self.assertNotEqual(a, self.order(1))

    def test_rep_blocks_in_order(self):
        # R27: 반복 1, 2, 3 블록이 차례로 오고 각 블록은 그 반복의 조건×언어 10개다.
        a = self.order(20261018)
        for rep in (1, 2, 3):
            block = a[(rep - 1) * 10:rep * 10]
            self.assertEqual({r.rsplit("-", 1)[1] for r in block}, {str(rep)})
            self.assertEqual(len(set(block)), 10)

    def test_default_seed_puts_both_models_early_in_block_1(self):
        self.assertEqual({r.split("-")[0] for r in self.order(20261018)[:5]}, {"sonnet55", "opus55"})

if __name__ == "__main__":
    unittest.main()
