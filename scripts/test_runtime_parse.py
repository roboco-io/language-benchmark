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
            os.makedirs(os.path.dirname(os.path.join(d, name)), exist_ok=True)
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

    def test_deps_java_php_csharp(self):
        pom = ('<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
               '<dependencyManagement><dependencies><dependency><artifactId>bom1</artifactId></dependency>'
               '<dependency><artifactId>bom2</artifactId></dependency></dependencies></dependencyManagement>'
               '<dependencies>'
               '<dependency><artifactId>a</artifactId></dependency>'
               '<dependency><artifactId>b</artifactId><scope>runtime</scope></dependency>'
               '<dependency><artifactId>c</artifactId><scope>compile</scope></dependency>'
               '<dependency><artifactId>t</artifactId><scope>test</scope></dependency>'
               '<dependency><artifactId>p</artifactId><scope>provided</scope></dependency>'
               '</dependencies>'
               '<profiles><profile><dependencies><dependency><artifactId>pp</artifactId></dependency></dependencies></profile></profiles>'
               '</project>')
        self.assertEqual(runtime_parse.deps(self.mkrepo({"pom.xml": pom}), "java"), 3)
        self.assertEqual(runtime_parse.deps(self.mkrepo({"pom.xml": "<project/>"}), "java"), 0)
        self.assertIsNone(runtime_parse.deps(self.mkrepo({}), "java"))
        php = self.mkrepo({"composer.json": json.dumps({
            "require": {"php": "^8.5", "ext-pdo": "*", "laravel/framework": "^12", "laravel/octane": "^2", "x/y": "1"},
            "require-dev": {"phpunit/phpunit": "^11"}})})
        self.assertEqual(runtime_parse.deps(php, "php"), 3)
        self.assertEqual(runtime_parse.deps(self.mkrepo({"composer.json": "{}"}), "php"), 0)
        self.assertIsNone(runtime_parse.deps(self.mkrepo({}), "php"))

    def test_deps_csproj_unique_across_projects(self):
        def proj(*refs):
            return "<Project Sdk=\"Microsoft.NET.Sdk.Web\">" + "".join(refs) + "</Project>"
        ref = lambda n, extra="": '<PackageReference Include="%s" Version="1"%s />' % (n, extra)
        d = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, d)
        (d / "src" / "Api").mkdir(parents=True)
        (d / "src" / "Core").mkdir()
        (d / "tests" / "Api.Tests").mkdir(parents=True)
        (d / "src" / "Api" / "Api.csproj").write_text(proj(
            ref("Microsoft.EntityFrameworkCore.Sqlite"), ref("Serilog"), ref("Analyzer.X", ' PrivateAssets="all"'),
            '<PackageReference Include="Child.Priv" Version="1"><PrivateAssets>all</PrivateAssets></PackageReference>'))
        (d / "src" / "Core" / "Core.csproj").write_text(proj(ref("Serilog"), ref("Newtonsoft.Json")))
        (d / "tests" / "Api.Tests" / "Api.Tests.csproj").write_text(proj(ref("xunit"), ref("Moq")))
        self.assertEqual(runtime_parse.deps(d, "csharp"), 3)
        self.assertEqual(runtime_parse.deps(self.mkrepo({}), "csharp"), None)

    def test_deps_gradle_kts(self):
        kts = ('plugins { kotlin("jvm") version "2.2.20" }\n'
               'dependencies {\n'
               '    implementation("org.springframework.boot:spring-boot-starter-web")\n'
               '    implementation("org.springframework.boot:spring-boot-starter-web")\n'
               '    implementation(kotlin("reflect"))\n'
               '    implementation(platform("org.example:bom:1"))\n'
               '    api("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.0")\n'
               '    runtimeOnly("org.xerial:sqlite-jdbc:3.50.3.0")\n'
               '    compileOnly("org.example:provided:1")\n'
               '    developmentOnly("org.springframework.boot:spring-boot-devtools")\n'
               '    annotationProcessor("org.example:ap:1")\n'
               '    testImplementation("org.springframework.boot:spring-boot-starter-test")\n'
               '    testRuntimeOnly("org.junit.platform:junit-platform-launcher")\n'
               '}\n')
        repo = self.mkrepo({"build.gradle.kts": kts, "build/tmp/build.gradle.kts": 'implementation("x:ignored:1")'})
        self.assertEqual(runtime_parse.deps(repo, "kotlin"), 4)
        sub = self.mkrepo({"settings.gradle.kts": "", "app/build.gradle.kts": kts,
                           "core/build.gradle": "dependencies {\n  implementation 'org.xerial:sqlite-jdbc:3'\n"
                                                "  implementation 'org.other:lib:1'\n}\n"})
        self.assertEqual(runtime_parse.deps(sub, "kotlin"), 5)
        self.assertEqual(runtime_parse.deps(self.mkrepo({"build.gradle.kts": "plugins {}"}), "kotlin"), 0)
        self.assertIsNone(runtime_parse.deps(self.mkrepo({}), "kotlin"))

    def test_deps_sbt(self):
        sbt = ('lazy val root = (project in file(".")).enablePlugins(PlayScala)\n'
               'libraryDependencies ++= Seq(\n'
               '  guice,\n'
               '  jdbc,\n'
               '  "org.playframework" %% "play-slick" % "6.2.0",\n'
               '  "org.xerial" % "sqlite-jdbc" % sqliteVersion,\n'
               '  "com.github.jwt-scala" %% "jwt-play-json" % "10.0.1" % Compile,\n'
               '  "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.1" % Test,\n'
               '  "org.mockito" % "mockito-core" % "5.0" % "test"\n'
               ')\n'
               'libraryDependencies += "org.playframework" %% "play-slick" % "6.2.0"\n')
        repo = self.mkrepo({"build.sbt": sbt,
                            "project/plugins.sbt": 'addSbtPlugin("org.playframework" % "sbt-plugin" % "3.0.9")',
                            "project/Deps.scala": 'val bcrypt = "org.mindrot" % "jbcrypt" % "0.4"'})
        self.assertEqual(runtime_parse.deps(repo, "scala"), 6)
        self.assertEqual(runtime_parse.deps(self.mkrepo({"build.sbt": 'name := "x"'}), "scala"), 0)
        self.assertIsNone(runtime_parse.deps(self.mkrepo({}), "scala"))

    def test_vulns_osv_composer_dotnet(self):
        osv = {"results": [{"packages": [{"package": {"name": "g:a"}, "vulnerabilities": [
            {"id": "GHSA-aaaa-bbbb-cccc", "aliases": ["CVE-1"]}, {"id": "GHSA-dddd-eeee-ffff", "aliases": []}]}]}]}
        self.assertEqual(runtime_parse.vulns({"osv": osv}), 2)
        self.assertEqual(runtime_parse.vulns({"osv": {"results": []}}), 0)
        comp = {"advisories": {"guzzlehttp/guzzle": [
            {"advisoryId": "PKSA-1", "cve": "CVE-1", "sources": [{"name": "GitHub", "remoteId": "GHSA-2c3f-4g5h-6j7m"}]},
            {"advisoryId": "PKSA-2", "cve": None, "sources": []}]}, "abandoned": []}
        self.assertEqual(runtime_parse.vulns({"composer": comp}), 2)
        self.assertEqual(runtime_parse.vulns({"composer": {"advisories": [], "abandoned": []}}), 0)
        net = {"projects": [{"path": "p", "frameworks": [{"framework": "net10.0",
            "topLevelPackages": [{"id": "A", "vulnerabilities": [
                {"severity": "High", "advisoryurl": "https://github.com/advisories/GHSA-2c3f-4g5h-6j7m"}]}],
            "transitivePackages": [{"id": "B", "vulnerabilities": [
                {"severity": "Low", "advisoryurl": "https://github.com/advisories/GHSA-2c3f-4g5h-6j7m"},
                {"severity": "Low", "advisoryurl": "https://github.com/advisories/GHSA-rrrr-vvvv-wwww"}]}]}]}]}
        # 같은 GHSA는 한 번만 센다. 형식이 맞지 않는 ID(rrrr는 GHSA 알파벳 밖)는 URL 꼬리를 그대로 ID로 쓴다.
        self.assertEqual(runtime_parse.vulns({"dotnet": net}), 2)
        self.assertEqual(runtime_parse.vulns({"dotnet": {"projects": [{"path": "p"}]}}), 0)
        # trivy의 GHSA 참조와 닿으면 한 묶음이다.
        trivy = {"Results": [{"Vulnerabilities": [{"VulnerabilityID": "CVE-9",
                 "References": ["https://github.com/advisories/GHSA-2c3f-4g5h-6j7m"]}]}]}
        self.assertEqual(runtime_parse.vulns({"dotnet": net, "trivy": trivy}), 2)
        self.assertEqual(runtime_parse.vulns({"composer": comp, "trivy": trivy}), 2)

    def test_vulns_osv_spring_boot_sbom_fixture(self):
        # 실제 보고서를 줄인 고정물: Spring Boot 3.2.0 + jackson-databind 2.9.8 pom을 CycloneDX(Maven 해석)로 SBOM을 만든 뒤
        # osv-scanner 2.7.0으로 스캔한 결과(2026-10-18). 부모 BOM이 정한 버전(tomcat·logback)도 잡혀야 한다.
        path = pathlib.Path(__file__).resolve().parent / "fixtures" / "scanners" / "osv-spring-boot.json"
        osv = json.loads(path.read_text())
        names = {p["package"]["name"] for r in osv["results"] for p in r["packages"]}
        self.assertIn("org.apache.tomcat.embed:tomcat-embed-core", names)
        self.assertEqual(runtime_parse.vulns({"osv": osv}), 6)
        # trivy가 같은 CVE를 보고하면 한 묶음으로 센다.
        trivy = {"Results": [{"Vulnerabilities": [{"VulnerabilityID": "CVE-2020-8840", "References": []}]}]}
        self.assertEqual(runtime_parse.vulns({"osv": osv, "trivy": trivy}), 6)

    def test_registry_kinds_and_formats_match_parsers(self):
        import langreg
        self.assertEqual(langreg.DEPS_KINDS, set(runtime_parse.DEPS_BY_KIND))
        formats = {f for f in langreg.SCANNER_FORMATS if f != "none"}
        self.assertTrue(formats <= set(runtime_parse._EXTRACT), formats - set(runtime_parse._EXTRACT))
        # trivy는 레지스트리 형식이 아니라 공통 보고서이므로 _EXTRACT에만 있다.
        self.assertEqual(set(runtime_parse._EXTRACT) - formats, {"trivy"})

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
        # 유효한 GHSA 알파벳 ID를 쓴다.
        trivy = {"Results": [{"Vulnerabilities": [{
            "VulnerabilityID": "CVE-2025-7",
            "References": ["https://github.com/advisories/GHSA-2c3f-4g5h-6j7m"]}]}]}
        npm = {"vulnerabilities": {"x": {"via": [
            {"url": "https://github.com/advisories/GHSA-2c3f-4g5h-6j7m"}]}}}
        self.assertEqual(runtime_parse.vulns({"trivy": trivy, "npm": npm}), 1)
        trivy2 = {"Results": [{"Vulnerabilities": [{
            "VulnerabilityID": "CVE-2025-8",
            "PrimaryURL": "https://github.com/advisories/ghsa-2C3F-4G5H-6J7M"}]}]}
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
