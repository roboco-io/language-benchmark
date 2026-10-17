import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "contamination_check.py"
RUN = "opus55-go-1"


def tool(name, **inp):
    return {"type": "assistant", "message": {"id": "m", "content": [{"type": "tool_use", "name": name, "input": inp}]}}


class ContaminationCheckTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name) / "langbench-exp901"
        self.sessions = self.base / ("sessions-%s" % RUN)
        self.sessions.mkdir(parents=True)

    def write(self, rel, *records):
        p = self.sessions / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text("".join(json.dumps(r) + "\n" for r in records))

    def check(self, run=RUN):
        return subprocess.run([sys.executable, str(SCRIPT), str(self.base), run], capture_output=True, text=True)

    def test_read_of_other_app_is_flagged(self):
        self.write("s.jsonl", tool("Read", file_path="%s/app-other/x" % self.base))
        r = self.check()
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("Read: %s/app-other/x" % self.base, r.stdout)
        self.assertIn("hits: 1", r.stdout)

    def test_own_app_paths_are_clean(self):
        self.write("s.jsonl", tool("Read", file_path="%s/app-%s/src/main.go" % (self.base, RUN)),
                   tool("Bash", command="cd %s/app-%s && go build ./..." % (self.base, RUN)),
                   tool("Bash", command="ls %s0/app-other" % self.base))  # 다른 BASE(접두사만 같음)
        r = self.check()
        self.assertEqual((r.returncode, r.stdout.strip()), (0, "hits: 0"), r.stdout)

    def test_subagent_relative_and_escaping_paths_are_flagged(self):
        self.write("sid/subagents/agent-1.jsonl",
                   tool("Bash", command="cat ../harness-hurl/auth.hurl"),
                   tool("Read", file_path="%s/app-%s/../metrics-%s.csv" % (self.base, RUN, RUN)),
                   tool("Glob", path="%s/app-%s-fu" % (self.base, RUN)))
        r = self.check()
        self.assertEqual(r.returncode, 1)
        self.assertIn("../harness-hurl", r.stdout)
        self.assertIn("metrics-%s.csv" % RUN, r.stdout)
        self.assertIn("app-%s-fu" % RUN, r.stdout)
        self.assertIn("hits: 3", r.stdout)

    def test_missing_sessions_is_error(self):
        r = self.check("sonnet55-go-9")
        self.assertEqual(r.returncode, 2)


if __name__ == "__main__":
    unittest.main()
