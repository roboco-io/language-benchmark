import os
import shutil
import subprocess
import tempfile
import unittest

from test_measure import FAKES, SKILL

SCC = shutil.which("scc") is not None


class RuntimeStaticTest(unittest.TestCase):
    """Dockerfile 없는 리포로 1부를 즉시 실패시키고 2부(정적 분석)와 내보내기만 확인한다."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.base = os.path.join(self.tmp, "base")
        shutil.copytree(os.path.join(SKILL, "scripts"), self.base)
        self.repo = os.path.join(self.tmp, "app")
        os.makedirs(self.repo)

    def write(self, rel, text="x\n"):
        p = os.path.join(self.repo, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w") as f:
            f.write(text)

    def git_repo(self):
        git = ["git", "-c", "user.name=t", "-c", "user.email=t@t", "-C", self.repo]
        subprocess.run(git + ["init", "-q"], check=True)
        self.write("go.mod", "module x\n\ngo 1.22\n")
        self.write("main.go", "package main\n\nfunc main() {}\n")
        self.write(".gitignore", "node_modules/\n")
        subprocess.run(git + ["add", "-A"], check=True)
        subprocess.run(git + ["commit", "-qm", "init"], check=True)

    def runtime(self, run="sonnet55-go-1", **env):
        e = dict(os.environ, GOVULNCHECK_BIN=os.path.join(FAKES, "govulncheck"), **env)
        r = subprocess.run(["bash", os.path.join(self.base, "runtime.sh"), self.repo, run],
                           capture_output=True, text=True, env=e, timeout=300)
        return r, os.path.join(self.base, "runtime-" + run)

    def read(self, path):
        with open(path) as f:
            return f.read()

    @unittest.skipUnless(SCC, "scc 없음")
    def test_govulncheck_report_kept_on_rc_0_and_3(self):
        self.git_repo()
        for rc in ("0", "3"):
            r, out = self.runtime(FAKE_GOVULN_RC=rc)
            self.assertTrue(os.path.isfile(os.path.join(out, "go.json")), (rc, r.stderr))
            self.assertNotIn("scan:go", self.read(os.path.join(out, "error.txt")))

    @unittest.skipUnless(SCC, "scc 없음")
    def test_govulncheck_failure_is_missing_report(self):
        # R22: 비정상 종료 코드, 또는 config 메시지 없는 스트림은 "취약점 0"이 아니라 보고서 없음(빈 칸)이다.
        self.git_repo()
        for env in ({"FAKE_GOVULN_RC": "1"}, {"FAKE_GOVULN_STREAM": "progress"}):
            r, out = self.runtime(**env)
            self.assertFalse(os.path.exists(os.path.join(out, "go.json")), (env, r.stderr))
            self.assertIn("scan:go: ", self.read(os.path.join(out, "error.txt")), env)

    @unittest.skipUnless(SCC, "scc 없음")
    def test_exports_working_tree_not_head(self):
        # R25: 커밋하지 않은 수정·미추적 파일은 내보내고, .gitignore가 가리는 파일은 뺀다.
        self.git_repo()
        self.write("main.go", "package main\n\nfunc main() { println(1) }\n")  # 수정(dirty)
        self.write("extra.go", "package main\n")  # 미추적
        self.write("node_modules/big.js", "ignored\n")  # 무시됨
        r, out = self.runtime()
        src = dict(l.split("=", 1) for l in self.read(os.path.join(out, "source.txt")).split())
        head = subprocess.run(["git", "-C", self.repo, "rev-parse", "HEAD"],
                              capture_output=True, text=True).stdout.strip()
        self.assertEqual(src, {"head": head, "dirty": "2", "files": "4"}, r.stderr)
        self.assertIn("build: Dockerfile이 작업 트리에 없음", self.read(os.path.join(out, "error.txt")))

    def test_non_git_repo_is_export_error(self):
        self.write("main.go", "package main\n")
        r, out = self.runtime()
        self.assertEqual(r.returncode, 1)
        self.assertEqual(self.read(os.path.join(out, "error.txt")).strip(), "export: not a git repo")


if __name__ == "__main__":
    unittest.main()
