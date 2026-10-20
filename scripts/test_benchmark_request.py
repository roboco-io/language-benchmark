"""벤치마크 요청 이슈의 중복 판정 테스트."""
import json
import tempfile
import unittest
from pathlib import Path

import benchmark_request as br

ROOT = Path(__file__).resolve().parent.parent

FORM = """### 언어 / Language

C#

### 웹 프레임워크 / Web framework

ASP.NET Core

### DB 접근 라이브러리 / DB library

_No response_

### 요청 이유·비교하고 싶은 것 / Why, and what to compare

궁금해서
"""


class BenchmarkRequest(unittest.TestCase):
    def mkroot(self):
        d = Path(tempfile.mkdtemp())
        langs = d / ".claude" / "skills" / "langbench" / "languages"
        langs.mkdir(parents=True)
        for key, name, fw in [("csharp", "C#", "ASP.NET Core"), ("cpp", "C++", "Drogon (C++20)"),
                              ("java", "Java", "Spring Boot"), ("php", "PHP", "Laravel (Octane, FrankenPHP)"),
                              ("scala", "Scala", "Play Framework")]:
            (langs / f"{key}.json").write_text(json.dumps({"key": key, "name": name, "stack": {"framework": fw}}))
        exp = d / "experiments" / "002-x"
        exp.mkdir(parents=True)
        (exp / "experiment.json").write_text(json.dumps({"exp": "EXP-002", "folder": "002-x"}))
        (d / "analysis" / "metrics").mkdir(parents=True)
        (d / "analysis" / "metrics" / "runs.csv").write_text(
            "exp,run,language\nEXP-002,a,csharp\nEXP-002,b,cpp\nEXP-002,c,java\nEXP-002,d,php\n")
        return d

    def test_parse_form(self):
        f = br.parse_form(FORM)
        self.assertEqual(f["language"], "C#")
        self.assertEqual(f["framework"], "ASP.NET Core")
        self.assertEqual(f["db"], "")

    def test_duplicate_language_and_framework(self):
        root = self.mkroot()
        cases = [("C#", "ASP.NET Core"), ("csharp", "asp.net core"), ("c sharp", "ASP.NET"),
                 ("C++", "Drogon"), ("cpp", "drogon (c++20)"), ("java", "Spring Boot 3"),
                 ("PHP", "Laravel"), ("php", "Laravel Octane")]
        for lang, fw in cases:
            with self.subTest(lang=lang, fw=fw):
                m = br.find_existing(lang, fw, root)
                self.assertIsNotNone(m)
                self.assertEqual(m["exp"], "EXP-002")
                self.assertTrue(m["report"].endswith("/experiments/002-x/report.md"))

    def test_not_duplicate(self):
        root = self.mkroot()
        for lang, fw in [("Java", "Quarkus"), ("Java", "Spring WebFlux"), ("Kotlin", "Spring Boot"),
                         ("Scala", "Play Framework"),  # 레지스트리에만 있고 결과가 없다
                         ("Elixir", "Phoenix"), ("C#", ""), ("", "Laravel"), ("PHP", "La")]:
            with self.subTest(lang=lang, fw=fw):
                self.assertIsNone(br.find_existing(lang, fw, root))

    def test_message_is_bilingual(self):
        root = self.mkroot()
        msg = br.message(br.find_existing("C#", "ASP.NET Core", root))
        self.assertIn("C# + ASP.NET Core", msg)
        self.assertIn("EXP-002", msg)
        self.assertIn("이미", msg)
        self.assertIn("already", msg)

    def test_real_repo_has_results_for_registry_languages(self):
        self.assertIsNotNone(br.find_existing("Go", "Gin", ROOT))
        self.assertIsNotNone(br.find_existing("TypeScript", "Fastify", ROOT))


if __name__ == "__main__":
    unittest.main()
