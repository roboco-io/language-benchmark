import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest

SKILL = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.join(SKILL, "scripts"))
import langreg  # noqa: E402
import render_prompts  # noqa: E402

SCRIPT = os.path.join(SKILL, "scripts", "langreg.py")


class LangregTest(unittest.TestCase):
    def test_keys_order(self):
        self.assertEqual(langreg.keys(), ["typescript", "python", "go", "rust", "cpp"])

    def test_all_entries_valid(self):
        for e in langreg.load_all():
            self.assertEqual(langreg.validate(e), [], e["key"])

    def test_unknown_key_raises(self):
        with self.assertRaisesRegex(KeyError, "zig"):
            langreg.get("zig")

    def test_validate_rejects_unknown_kind(self):
        e = dict(langreg.get("go"), deps={"kind": "pom-xml"})
        self.assertTrue(any("deps.kind" in m for m in langreg.validate(e)))

    def test_validate_requires_fields(self):
        self.assertTrue(langreg.validate({"key": "x"}))

    def test_detect_exact_segment(self):
        base = langreg.get("go")
        with tempfile.TemporaryDirectory() as d:
            for k in ("c", "cpp"):
                with open(os.path.join(d, f"{k}.json"), "w") as f:
                    json.dump(dict(base, key=k, order=1 if k == "c" else 2), f)
            old = os.environ.get("LB_LANG_DIR")
            os.environ["LB_LANG_DIR"] = d
            try:
                self.assertEqual(langreg.detect("sonnet55-cpp-3"), "cpp")
                self.assertEqual(langreg.detect("sonnet55-c-1"), "c")
            finally:
                if old is None:
                    del os.environ["LB_LANG_DIR"]
                else:
                    os.environ["LB_LANG_DIR"] = old

    def test_detect_unknown_raises(self):
        with self.assertRaises(ValueError):
            langreg.detect("sonnet55-zig-1")

    def test_cli_keys_and_validate(self):
        out = subprocess.run(["python3", SCRIPT, "keys"], capture_output=True, text=True, check=True).stdout
        self.assertEqual(out.strip(), "typescript python go rust cpp")
        self.assertEqual(subprocess.run(["python3", SCRIPT, "validate"], capture_output=True).returncode, 0)

    def test_cli_get(self):
        out = subprocess.run(["python3", SCRIPT, "get", "go", "name"], capture_output=True, text=True, check=True).stdout
        self.assertEqual(out.strip(), "Go")

    def test_prompts_unchanged(self):
        with open(os.path.join(SKILL, "assets", "checksums.md5")) as f:
            entries = {l.split()[1]: l.split()[0] for l in f if l.strip()}
        for lang, text in render_prompts.render().items():
            self.assertEqual(hashlib.md5(text.encode()).hexdigest(), entries[f"PROMPT-{lang}.md"], lang)


if __name__ == "__main__":
    unittest.main()
