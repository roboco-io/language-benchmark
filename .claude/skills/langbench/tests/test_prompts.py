import hashlib
import json
import os
import subprocess
import sys
import unittest

SKILL = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ASSETS = os.path.join(SKILL, "assets")
sys.path.insert(0, os.path.join(SKILL, "scripts"))
import langreg  # noqa: E402

LANGS = langreg.keys()


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
        stacks = {e["key"]: e["prompt"] for e in langreg.load_all()}
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

    def test_checksums_cover_prompts(self):
        # 언어별 PROMPT 5종 + 후속 과제 PROMPT + 후속 Hurl 정본이 모두 해시로 고정돼 있어야 한다.
        entries = {}
        for line in read(os.path.join(ASSETS, "checksums.md5")).splitlines():
            if line.strip():
                md5, name = line.split(None, 1)
                entries[name.strip()] = md5
        names = [f"PROMPT-{l}.md" for l in LANGS] + ["PROMPT-followup.md", "followup-hurl/comments_update.hurl"]
        for name in names:
            self.assertIn(name, entries)
            with open(os.path.join(ASSETS, name), "rb") as f:
                self.assertEqual(hashlib.md5(f.read()).hexdigest(), entries[name], name)
        self.assertEqual(sum(1 for n in entries if n.startswith("PROMPT-")), len(LANGS) + 1)

    def test_followup_prompt_contract(self):
        text = read(os.path.join(ASSETS, "PROMPT-followup.md"))
        for word in ("PUT /api/articles/:slug/comments/:id", "403", "401", "422", "404", "154 requests",
                     "start.sh", "Dockerfile", ".ralph-done", "Do not modify PROMPT.md."):
            self.assertIn(word, text, word)
        self.assertNotIn("comments_update", text)  # 채점 정본 파일명은 에이전트에 노출하지 않는다


if __name__ == "__main__":
    unittest.main()
