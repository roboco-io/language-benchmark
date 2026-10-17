import os
import shutil
import subprocess
import tempfile
import unittest

SKILL = os.path.realpath(os.path.join(os.path.dirname(__file__), ".."))


class SetupTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.home = os.path.join(self.tmp, "home")
        os.makedirs(self.home)
        # 스킬 사본에 테스트용 PROMPT와 그 해시 줄을 넣는다(실제 PROMPT는 후속 과제가 추가).
        self.skill = os.path.join(self.tmp, "skill")
        shutil.copytree(SKILL, self.skill, ignore=shutil.ignore_patterns("tests", "__pycache__"))
        assets = os.path.join(self.skill, "assets")
        with open(os.path.join(assets, "PROMPT-typescript.md"), "w") as f:
            f.write("test prompt\n")
        md5 = subprocess.run(["md5", "-r", "PROMPT-typescript.md"], cwd=assets,
                             capture_output=True, text=True, check=True).stdout
        with open(os.path.join(assets, "checksums.md5"), "a") as f:
            f.write(md5)

    def run_setup(self, harness="claude-native", langs="typescript"):
        env_file = os.path.join(self.tmp, "bench.env")
        with open(env_file, "w") as f:
            f.write(f'''EXP=901
HARNESS={harness}
CONDITIONS="sonnet55:claude-sonnet-5-5 opus55:claude-opus-5-5"
LANGS="{langs}"
N=1
MAX_ITER=3
MAX_SEC=60
STALL_SEC=30
START_SEC=60
DOCKER_BUILD_SEC=60
FU_MAX_ITER=1
FU_MAX_SEC=60
MODEL_SPECS="{os.path.join(self.skill, "assets", "model-specs.example.json")}"
RUN_BUDGET_USD=2
EXP_BUDGET_USD=10
PRICE_IN=0
PRICE_OUT=0
PRICE_CACHE_READ=0
PRICE_TIERS=""
''')
        env = dict(os.environ, HOME=self.home, LB_SKIP_TOOLCHAIN="1")
        return subprocess.run(["bash", os.path.join(self.skill, "scripts", "setup.sh"), env_file],
                              env=env, capture_output=True, text=True)

    def test_setup_copies_and_verifies(self):
        r = self.run_setup()
        self.assertEqual(r.returncode, 0, r.stderr)
        base = os.path.join(self.home, "experiments", "langbench-exp901")
        hurls = [x for x in os.listdir(os.path.join(base, "harness-hurl")) if x.endswith(".hurl")]
        self.assertEqual(len(hurls), 13)
        self.assertTrue(os.path.isfile(os.path.join(base, "bench.env")))
        self.assertTrue(os.path.isfile(os.path.join(base, "driver.sh")))
        self.assertTrue(os.path.isfile(os.path.join(base, "PROMPT-typescript.md")))

    def test_setup_rejects_tampered_hurl(self):
        path = os.path.join(self.skill, "assets", "harness-hurl", "auth.hurl")
        with open(path, "a") as f:
            f.write("# tampered\n")
        r = self.run_setup()
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("정본 해시 불일치", r.stderr)

    def test_setup_rejects_non_native_harness(self):
        r = self.run_setup("codex")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("unknown HARNESS", r.stderr)

    def test_setup_rejects_lang_without_prompt(self):
        # python은 PROMPT 파일이 없다. 해시 검증은 통과하므로 PROMPT 존재 검사에서 중단돼야 한다.
        r = self.run_setup(langs="typescript python")
        self.assertNotEqual(r.returncode, 0)
        self.assertNotIn("정본 해시 불일치", r.stderr)
        self.assertIn("PROMPT-python.md 없음 또는 checksums.md5에 미등록", r.stderr)

    def test_setup_rejects_unregistered_prompt(self):
        # 파일은 있으나 checksums.md5에 없는 PROMPT도 중단돼야 한다.
        with open(os.path.join(self.skill, "assets", "PROMPT-python.md"), "w") as f:
            f.write("unregistered\n")
        r = self.run_setup(langs="typescript python")
        self.assertNotEqual(r.returncode, 0)
        self.assertNotIn("정본 해시 불일치", r.stderr)
        self.assertIn("PROMPT-python.md 없음 또는 checksums.md5에 미등록", r.stderr)


if __name__ == "__main__":
    unittest.main()
