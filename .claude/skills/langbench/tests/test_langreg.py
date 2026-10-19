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
        self.assertEqual(langreg.keys(), ["typescript", "python", "go", "rust", "cpp", "java", "php", "csharp"])

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
        self.assertEqual(out.strip(), "typescript python go rust cpp java php csharp")
        self.assertEqual(subprocess.run(["python3", SCRIPT, "validate"], capture_output=True).returncode, 0)

    def test_cli_get(self):
        out = subprocess.run(["python3", SCRIPT, "get", "go", "name"], capture_output=True, text=True, check=True).stdout
        self.assertEqual(out.strip(), "Go")

    def run_cli(self, *args, env=None):
        e = dict(os.environ, **(env or {}))
        return subprocess.run(["python3", SCRIPT, *args], capture_output=True, text=True, check=True, env=e).stdout

    def test_shell_go(self):
        out = self.run_cli("shell", "go", "/tmp/src")
        self.assertIn("LR_SCAN_OK_RC='0 3'", out)
        self.assertIn("LR_SCAN_KEY=stream:config", out)

    def test_shell_python_alt(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertIn("LR_SCAN_ARGV=('' -f json .)", self.run_cli("shell", "python", d))
            open(os.path.join(d, "requirements.txt"), "w").close()
            self.assertIn("-r requirements.txt", self.run_cli("shell", "python", d))

    def test_shell_java_scans_maven_sbom(self):
        # java 스캐너: Maven(CycloneDX)이 부모 BOM·전이 의존성까지 해석한 target/bom.json을 osv-scanner가 읽는다.
        # mvn 출력은 표준 오류로 가야 한다(표준 출력은 보고서 파일이 된다). 가짜 mvn·osv-scanner로 순서와 인자만 확인한다.
        with tempfile.TemporaryDirectory() as d:
            src, fbin = os.path.join(d, "src"), os.path.join(d, "bin")
            os.makedirs(src); os.makedirs(fbin)
            with open(os.path.join(fbin, "mvn"), "w") as f:
                f.write('#!/bin/bash\necho "mvn $*"\nmkdir -p target && echo "{}" > target/bom.json\n')
            with open(os.path.join(fbin, "osv-scanner"), "w") as f:
                f.write('#!/bin/bash\n[ -f target/bom.json ] || exit 9\necho "{\\"args\\": \\"$*\\"}"\n')
            for n in ("mvn", "osv-scanner"):
                os.chmod(os.path.join(fbin, n), 0o755)
            r = subprocess.run(["bash", "-c", f'eval "$(python3 {SCRIPT} shell java "$1")"; cd "$1" && "${{LR_SCAN_ARGV[@]}}"', "-", src],
                               capture_output=True, text=True, env=dict(os.environ, PATH=fbin + ":" + os.environ["PATH"]))
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertIn("org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom", r.stderr)
            args = json.loads(r.stdout)["args"]
            self.assertIn("-L target/bom.json", args)
            self.assertIn("--allow-no-lockfiles", args)

    def test_shell_php_reports_abandoned_without_failing(self):
        # Composer 기본값(audit.abandoned=fail)이면 버려진 패키지만 있어도 0이 아닌 코드로 끝나 보고서가 버려진다.
        out = self.run_cli("shell", "php", "/tmp/src")
        self.assertIn("LR_SCAN_ARGV=(composer audit --format=json --locked --abandoned=report)", out)
        self.assertIn("LR_SCAN_OK_RC='0 1'", out)

    def test_java_toolchain_uses_path_java(self):
        # 기록되는 JDK는 bench.env가 PATH에 올린 java여야 한다(절대 경로면 PATH 설정 누락을 못 잡는다).
        self.assertEqual(langreg.get("java")["toolchain"][0]["bin"], "java")

    def test_shell_union_matches_legacy(self):
        out = self.run_cli("shell", "go", "/tmp/src")
        v = dict(l.split("=", 1) for l in out.splitlines() if l.startswith(("LR_LOC_EXCLUDE", "LR_HINTS")))
        self.assertEqual(set(v["LR_LOC_EXCLUDE"].split(",")),
                         {"test", "tests", "__tests__", "hurl", "node_modules", "target", "build", ".venv", "vendor", "bin", "obj", ".mvn", "storage", "bootstrap/cache"})
        self.assertEqual(set(v["LR_HINTS"].strip("'").split("|")),
                         set("--release|CMAKE_BUILD_TYPE|--workers|cluster|GOMAXPROCS|worker_threads|setThreadNum|journal_mode|busy_timeout|server.tomcat|hibernate|octane|ThreadPool|Kestrel".split("|")))

    def test_shell_quotes_paths(self):
        r = subprocess.run(["bash", "-c", f'eval "$(python3 {SCRIPT} shell python /tmp)"; printf %s "${{LR_SCAN_ARGV[0]}}"'],
                           capture_output=True, text=True, env=dict(os.environ, PIP_AUDIT_BIN="/tmp/a b/pip-audit"))
        self.assertEqual(r.stdout, "/tmp/a b/pip-audit")

    def test_toolchain_lines(self):
        lines = self.run_cli("toolchain").splitlines()
        self.assertTrue(any(l.startswith("go\t") for l in lines))
        self.assertIn("govulncheck\t-\t-\tGOVULNCHECK_BIN\t2\t-version", lines)
        self.assertTrue(any(l.startswith("drogon\tbrew-or-pkgconfig") for l in lines))

    def test_csharp_scanner_finds_nested_project(self):
        # EXP-002의 C# 앱은 모두 .csproj를 src/<이름>/ 아래에 둔다. 루트에서 찾으면 스캐너가 실패한다.
        # 테스트·bin·obj 아래 프로젝트는 건너뛰고, restore 뒤 list에 같은 경로를 넘기는지 가짜 dotnet으로 확인한다.
        with tempfile.TemporaryDirectory() as d:
            src, fake = os.path.join(d, "src"), os.path.join(d, "fakebin")
            for rel in ("src/Api/Api.csproj", "src/Api.Tests/Api.Tests.csproj", "src/Api/bin/X/X.csproj"):
                os.makedirs(os.path.dirname(os.path.join(d, rel)), exist_ok=True)
                open(os.path.join(d, rel), "w").close()
            os.makedirs(fake)
            with open(os.path.join(fake, "dotnet"), "w") as f:
                f.write('#!/bin/bash\n[ "$1" = restore ] && { echo "restore $2" >&2; exit 0; }\necho "{\\"projects\\": [\\"$2\\"]}"\n')
            os.chmod(os.path.join(fake, "dotnet"), 0o755)
            argv = langreg.get("csharp")["scanner"]["argv"]
            env = dict(os.environ, PATH=fake + os.pathsep + os.environ["PATH"])
            r = subprocess.run(argv, cwd=d, env=env, capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(json.loads(r.stdout), {"projects": ["./src/Api/Api.csproj"]})
            self.assertIn("restore ./src/Api/Api.csproj", r.stderr)
            open(os.path.join(src, "App.sln"), "w").close()
            r = subprocess.run(argv, cwd=d, env=env, capture_output=True, text=True)
            self.assertEqual(json.loads(r.stdout), {"projects": ["./src/App.sln"]})

    def test_cleanup_dirs(self):
        self.assertEqual(set(self.run_cli("cleanup-dirs").split()), {"target", ".venv", "build", "vendor", "bin", "obj"})

    def run_toolchain(self, **env):
        return subprocess.run(["bash", os.path.join(SKILL, "scripts", "toolchain.sh")], capture_output=True, text=True,
                              env=dict(os.environ, **env))

    def test_toolchain_langs_subset(self):
        r = self.run_toolchain(LANGS="go")
        names = [l.split()[0] for l in r.stdout.splitlines()]
        self.assertIn("go", names)
        self.assertIn("python3", names)  # 공통 도구
        self.assertEqual(names.count("python3"), 1)
        for n in ("cargo", "cmake", "node", "drogon"):
            self.assertNotIn(n, names)

    def test_toolchain_python_prints_python3_once(self):
        names = [l.split()[0] for l in self.run_toolchain(LANGS="python").stdout.splitlines()]
        self.assertEqual(names.count("python3"), 1)

    def test_toolchain_registry_failure_fails(self):
        r = self.run_toolchain(LANGS="nolang")
        self.assertEqual(r.returncode, 1)
        self.assertIn("레지스트리", r.stderr)
        # langreg.py가 없는 디렉터리의 사본도 실패해야 한다
        with tempfile.TemporaryDirectory() as d:
            import shutil
            shutil.copy(os.path.join(SKILL, "scripts", "toolchain.sh"), d)
            r = subprocess.run(["bash", os.path.join(d, "toolchain.sh")], capture_output=True, text=True)
            self.assertEqual(r.returncode, 1)
            self.assertIn("레지스트리", r.stderr)

    def test_prompts_unchanged(self):
        with open(os.path.join(SKILL, "assets", "checksums.md5")) as f:
            entries = {l.split()[1]: l.split()[0] for l in f if l.strip()}
        for lang, text in render_prompts.render().items():
            self.assertEqual(hashlib.md5(text.encode()).hexdigest(), entries[f"PROMPT-{lang}.md"], lang)


if __name__ == "__main__":
    unittest.main()
