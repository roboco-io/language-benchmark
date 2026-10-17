import hashlib
import json
import os
import subprocess
import sys
import unittest

SKILL = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ASSETS = os.path.join(SKILL, "assets")
LANGS = ["typescript", "python", "go", "rust", "cpp"]


def read(path):
    with open(path, newline="") as f:
        return f.read()


class PromptTests(unittest.TestCase):
    def test_render_matches_committed(self):
        r = subprocess.run([sys.executable, os.path.join(SKILL, "scripts", "render_prompts.py"), "--check"],
                           capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_variants_differ_only_in_slots(self):
        template = read(os.path.join(ASSETS, "prompt", "template.md"))
        with open(os.path.join(ASSETS, "prompt", "stacks.json")) as f:
            stacks = json.load(f)
        for lang in LANGS:
            text = read(os.path.join(ASSETS, f"PROMPT-{lang}.md"))
            for slot in ("STACK", "WORKERS"):
                self.assertEqual(text.count(stacks[lang][slot]), 1, f"{lang} {slot}")
                text = text.replace(stacks[lang][slot], "{{%s}}" % slot)
            self.assertEqual(text, template, lang)

    def test_prompt_requires_contract(self):
        for lang in LANGS:
            text = read(os.path.join(ASSETS, f"PROMPT-{lang}.md"))
            for word in ("start.sh", "Dockerfile", "PORT", "WAL", "busy_timeout", "5000", ".ralph-done"):
                self.assertIn(word, text, f"{lang}: {word}")

    def test_checksums_cover_language_prompts(self):
        entries = {}
        for line in read(os.path.join(ASSETS, "checksums.md5")).splitlines():
            if line.strip():
                md5, name = line.split(None, 1)
                entries[name.strip()] = md5
        for lang in LANGS:
            name = f"PROMPT-{lang}.md"
            self.assertIn(name, entries)
            with open(os.path.join(ASSETS, name), "rb") as f:
                self.assertEqual(hashlib.md5(f.read()).hexdigest(), entries[name], name)


if __name__ == "__main__":
    unittest.main()
