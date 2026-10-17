import os
import shutil
import subprocess
import tempfile
import unittest

from test_measure import SKILL


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


if __name__ == "__main__":
    unittest.main()
