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
        # 스킬 사본에 테스트용 PROMPT와 그 해시 줄을 넣는다.
        self.skill = os.path.join(self.tmp, "skill")
        shutil.copytree(SKILL, self.skill, ignore=shutil.ignore_patterns("tests", "__pycache__"))
        assets = os.path.join(self.skill, "assets")
        # 실제 PROMPT 5종과 해시 줄은 사본에서 제거하고 테스트용 하나만 둔다.
        for name in os.listdir(assets):
            if name.startswith("PROMPT-"):
                os.remove(os.path.join(assets, name))
        sums = os.path.join(assets, "checksums.md5")
        with open(sums) as f:
            kept = [l for l in f if " PROMPT-" not in l]
        with open(sums, "w") as f:
            f.writelines(kept)
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
        # R28: 무결성 기준은 스킬 디렉터리이며 bench.env에 기록되고, 방금 만든 하네스는 그 기준과 일치한다.
        with open(os.path.join(base, "bench.env")) as f:
            self.assertIn('LB_SKILL_DIR="%s"' % self.skill, f.read())
        self.assertFalse(os.path.exists(os.path.join(base, "checksums.md5")))
        ok = subprocess.run(["bash", "-c", 'BASE="$1"; source "$BASE/common.sh"; harness_intact', "-", base])
        self.assertEqual(ok.returncode, 0)

    def test_setup_ignores_scripts_pycache(self):
        # langreg를 import하면 scripts/__pycache__/가 생긴다. setup.sh는 그 디렉터리가 있어도 끝까지 돌아야 한다(EXP-002 언어 셋).
        cache = os.path.join(self.skill, "scripts", "__pycache__")
        os.makedirs(cache)
        with open(os.path.join(cache, "langreg.cpython-314.pyc"), "wb") as f:
            f.write(b"\0")
        assets = os.path.join(self.skill, "assets")
        for l in ("java", "php", "csharp"):
            with open(os.path.join(assets, f"PROMPT-{l}.md"), "w") as f:
                f.write(f"test prompt {l}\n")
            md5 = subprocess.run(["md5", "-r", f"PROMPT-{l}.md"], cwd=assets,
                                 capture_output=True, text=True, check=True).stdout
            with open(os.path.join(assets, "checksums.md5"), "a") as f:
                f.write(md5)
        r = self.run_setup(langs="java php csharp")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("OK:", r.stdout)
        base = os.path.join(self.home, "experiments", "langbench-exp901")
        self.assertTrue(os.path.isfile(os.path.join(base, "langreg.py")))
        self.assertTrue(os.path.isfile(os.path.join(base, "languages", "java.json")))
        self.assertFalse(os.path.exists(os.path.join(base, "__pycache__")))

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

    def test_setup_fails_without_toolchain(self):
        # cmake만 숨긴 PATH(심볼릭 링크 디렉터리)에서 LB_SKIP_TOOLCHAIN 없이 실행하면 툴체인 점검에서 중단돼야 한다.
        bin_dir = os.path.join(self.tmp, "bin")
        os.makedirs(bin_dir)
        for d in ("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin", "/sbin", "/usr/sbin"):
            if not os.path.isdir(d):
                continue
            for name in os.listdir(d):
                link = os.path.join(bin_dir, name)
                if name != "cmake" and not os.path.lexists(link):
                    os.symlink(os.path.join(d, name), link)
        env_file = os.path.join(self.tmp, "bench.env")
        self.run_setup(langs="typescript cpp")  # bench.env 작성(툴체인 점검 생략 모드). cpp가 cmake를 요구한다
        shutil.rmtree(os.path.join(self.home, "experiments"))
        env = dict(os.environ, HOME=self.home, PATH=bin_dir)
        env.pop("LB_SKIP_TOOLCHAIN", None)
        r = subprocess.run(["/bin/bash", os.path.join(self.skill, "scripts", "setup.sh"), env_file],
                           env=env, capture_output=True, text=True)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("없음: cmake", r.stderr)
        # LANGS에서 cpp를 빼면 setup.sh가 그 부분집합만 점검하므로 cmake가 없어도 툴체인 점검을 통과한다
        self.run_setup(langs="typescript")
        shutil.rmtree(os.path.join(self.home, "experiments"))
        r = subprocess.run(["/bin/bash", os.path.join(self.skill, "scripts", "setup.sh"), env_file],
                           env=env, capture_output=True, text=True)
        self.assertNotIn("없음: cmake", r.stderr)


if __name__ == "__main__":
    unittest.main()
