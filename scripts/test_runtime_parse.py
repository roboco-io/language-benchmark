import json
import os
import pathlib
import shutil
import tempfile
import unittest

import runtime_parse


def summary(rate, p50, p95, p99, failed):
    return {"metrics": {
        "http_reqs": {"count": 1, "rate": rate},
        "http_req_duration": {"p(50)": p50, "p(95)": p95, "p(99)": p99},
        "http_req_failed": {"value": failed},
    }}


class RuntimeParse(unittest.TestCase):
    def write(self, data):
        fd, path = tempfile.mkstemp(suffix=".json")
        self.addCleanup(os.unlink, path)
        with os.fdopen(fd, "w") as f:
            json.dump(data, f)
        return path

    def test_k6_summary(self):
        p = self.write(summary(812.5, 12.1, 40.0, 88.8, 0.002))
        self.assertEqual(runtime_parse.k6_summary(p),
                         {"rps": 812.5, "p50_ms": 12.1, "p95_ms": 40.0, "p99_ms": 88.8, "error_rate": 0.2})

    def test_k6_summary_all_failed(self):
        p = self.write(summary(50, 1.0, 2.0, 3.0, 1.0))
        self.assertEqual(runtime_parse.k6_summary(p)["error_rate"], 100.0)

    def test_mem_mb_units(self):
        self.assertEqual(runtime_parse.mem_mb("512KiB / 1GiB"), 0.5)
        self.assertEqual(runtime_parse.mem_mb("1.5GiB / 2GiB"), 1536.0)
        self.assertEqual(runtime_parse.mem_mb("87.2MiB / 1GiB"), 87.2)

    # --- loc / deps / vulns ---
    def test_loc_primary_language_only(self):
        rust = [{"Name": "Rust", "Code": 1200}, {"Name": "TOML", "Code": 30},
                {"Name": "Dockerfile", "Code": 20}, {"Name": "Shell", "Code": 10}]
        self.assertEqual(runtime_parse.loc(rust, "rust"), 1200)
        cpp = [{"Name": "C++", "Code": 800}, {"Name": "C++ Header", "Code": 150}, {"Name": "CMake", "Code": 40}]
        self.assertEqual(runtime_parse.loc(cpp, "cpp"), 950)

    def test_loc_no_primary_language_is_zero(self):
        self.assertEqual(runtime_parse.loc([{"Name": "Shell", "Code": 10}], "go"), 0)

    def mkrepo(self, files):
        d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, d)
        for name, text in files.items():
            with open(os.path.join(d, name), "w") as f:
                f.write(text)
        return pathlib.Path(d)

    def test_deps_per_language(self):
        ts = self.mkrepo({"package.json": json.dumps({
            "dependencies": {"a": "1", "b": "1", "c": "1", "d": "1"},
            "devDependencies": {k: "1" for k in "uvwxyz"}})})
        self.assertEqual(runtime_parse.deps(ts, "typescript"), 4)
        py = self.mkrepo({"pyproject.toml": '[project]\nname="x"\ndependencies = ["a", "b>=1", "c", "d", "e[x]"]\n'})
        self.assertEqual(runtime_parse.deps(py, "python"), 5)
        go = self.mkrepo({"go.mod": "module x\n\ngo 1.22\n\nrequire (\n"
                          + "\ta.com/a v1.0.0\n\tb.com/b v1.0.0\n\tc.com/c v1.0.0\n"
                          + "".join("\ti%d.com/i v1.0.0 // indirect\n" % i for i in range(9))
                          + ")\n"})
        self.assertEqual(runtime_parse.deps(go, "go"), 3)
        rs = self.mkrepo({"Cargo.toml": '[package]\nname="x"\n\n[dependencies]\n'
                          + "".join('d%d = "1"\n' % i for i in range(6))
                          + 'd6 = { version = "1", features = ["x"] }\n\n[dev-dependencies]\nt1 = "1"\nt2 = "1"\n'})
        self.assertEqual(runtime_parse.deps(rs, "rust"), 7)
        cpp = self.mkrepo({"CMakeLists.txt": "find_package(Drogon CONFIG REQUIRED)\nfind_package(Threads REQUIRED)\n"
                           "find_package(SQLite3 REQUIRED)\nfind_package(Drogon)\n"})
        self.assertEqual(runtime_parse.deps(cpp, "cpp"), 2)

    def test_deps_python_requirements_fallback(self):
        py = self.mkrepo({"requirements.txt": "# c\n\nfastapi\n-r base.txt\nuvicorn==1\n"})
        self.assertEqual(runtime_parse.deps(py, "python"), 2)

    def test_deps_zero_vs_missing(self):
        self.assertEqual(runtime_parse.deps(self.mkrepo({"package.json": "{}"}), "typescript"), 0)
        for lang in ("typescript", "python", "go", "rust", "cpp"):
            self.assertIsNone(runtime_parse.deps(self.mkrepo({}), lang))

    def test_deps_cpp_fetchcontent_and_pkgconfig(self):
        cpp = self.mkrepo({"CMakeLists.txt": "FetchContent_Declare(json URL x)\n"
                           "pkg_check_modules(JSONCPP REQUIRED jsoncpp)\nfind_package(Threads)\n"})
        self.assertEqual(runtime_parse.deps(cpp, "cpp"), 2)

    def test_alias_groups_count_once(self):
        reports = {
            "trivy": {"Results": [{"Vulnerabilities": [{"VulnerabilityID": "CVE-2025-1"}]}, {"Vulnerabilities": None}]},
            "cargo": {"vulnerabilities": {"list": [{"advisory": {"id": "RUSTSEC-2025-9", "aliases": ["CVE-2025-1"]}}]}},
            "pip": {"dependencies": [{"name": "a", "vulns": [{"id": "PYSEC-1", "aliases": []}]}, {"name": "b", "vulns": []}]},
        }
        self.assertEqual(runtime_parse.vulns(reports), 2)

    def test_vulns_empty_reports_zero(self):
        reports = {"trivy": {"Results": []}, "npm": {"vulnerabilities": {}},
                   "pip": {"dependencies": []}, "go": [], "cargo": {"vulnerabilities": {"list": []}}}
        self.assertEqual(runtime_parse.vulns(reports), 0)

    def test_vulns_npm_and_go(self):
        npm = {"vulnerabilities": {
            "x": {"via": [{"url": "https://github.com/advisories/GHSA-aaaa-bbbb-cccc"}, "y"]},
            "y": {"via": [{"url": "https://github.com/advisories/GHSA-aaaa-bbbb-cccc"}]}}}
        self.assertEqual(runtime_parse.vulns({"npm": npm}), 1)
        go = [{"osv": {"id": "GO-1", "aliases": ["CVE-9"]}}, {"osv": {"id": "GO-2", "aliases": []}},
              {"finding": {"osv": "GO-1", "trace": [{"module": "m", "function": "F"}]}},
              {"finding": {"osv": "GO-2", "trace": [{"module": "m"}]}}]
        # GO-2는 호출 경로(function)가 없으므로 센다고 하지 않는다. trivy의 CVE-9와는 alias로 합쳐진다.
        trivy = {"Results": [{"Vulnerabilities": [{"VulnerabilityID": "CVE-9"}]}]}
        self.assertEqual(runtime_parse.vulns({"go": go}), 1)
        self.assertEqual(runtime_parse.vulns({"go": go, "trivy": trivy}), 1)

    def test_trivy_npm_linked_via_references(self):
        # 지정된 예시 ID(abcd-efgh-jkmp)는 GHSA 알파벳(a,b,d,e,k 불가)에 맞지 않아 유효한 ID로 바꿨다.
        trivy = {"Results": [{"Vulnerabilities": [{
            "VulnerabilityID": "CVE-2025-7",
            "References": ["https://github.com/advisories/GHSA-cfgh-jmpq-rvwx"]}]}]}
        npm = {"vulnerabilities": {"x": {"via": [
            {"url": "https://github.com/advisories/GHSA-cfgh-jmpq-rvwx"}]}}}
        self.assertEqual(runtime_parse.vulns({"trivy": trivy, "npm": npm}), 1)
        trivy2 = {"Results": [{"Vulnerabilities": [{
            "VulnerabilityID": "CVE-2025-8",
            "PrimaryURL": "https://github.com/advisories/ghsa-CFGH-JMPQ-RVWX"}]}]}
        self.assertEqual(runtime_parse.vulns({"trivy": trivy2, "npm": npm}), 1)

    def test_load_report_json_stream(self):
        fd, path = tempfile.mkstemp(suffix=".json")
        self.addCleanup(os.unlink, path)
        with os.fdopen(fd, "w") as f:
            f.write('{"a": 1}\n{"b": [2]}\n')
        self.assertEqual(runtime_parse.load_report(path), [{"a": 1}, {"b": [2]}])
        with open(path, "w") as f:
            f.write('{"a": 1}')
        self.assertEqual(runtime_parse.load_report(path), {"a": 1})


if __name__ == "__main__":
    unittest.main()
