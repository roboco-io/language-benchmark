# Phase 0 — EXP-002

- 기록: 2026-10-19 07:16:08 +0900
- claude: 2.1.302 (Claude Code)
- hurl: hurl 8.0.1 (x86_64-apple-darwin25.0) libcurl/8.7.1 (SecureTransport) LibreSSL/3.3.6 zlib/1.2.12 nghttp2/1.68.1
- 스모크: PASS claude-sonnet-5-5, PASS claude-opus-5-5 (phase0.log). 외부 LISTEN 포트 127.0.0.1:3003(DeepSRT) — 종료하지 않음, 하네스가 빈 포트 주입.
- 승인: preflight-approved (2026-10-19, 사용자 "진행")

## toolchain.txt
```
docker /usr/local/bin/docker Docker version 29.8.0, build 88096ef
k6 /opt/homebrew/bin/k6 k6 v1.1.0 (go1.24.4, darwin/arm64)
scc /opt/homebrew/bin/scc scc version 4.1.0
trivy /opt/homebrew/bin/trivy Version: 0.76.0
python3 /opt/homebrew/bin/python3 Python 3.14.4
hurl /opt/homebrew/bin/hurl hurl 8.0.1 (x86_64-apple-darwin25.0) libcurl/8.7.1 (SecureTransport) LibreSSL/3.3.6 zlib/1.2.12 nghttp2/1.68.1
java /opt/homebrew/opt/openjdk@25/bin/java openjdk version "25.0.4.1" 2026-08-18
mvn /opt/homebrew/bin/mvn WARNING: A restricted method in java.lang.System has been called
osv-scanner /opt/homebrew/bin/osv-scanner osv-scanner version: 2.7.0
php /opt/homebrew/bin/php PHP 8.5.11 (cli) (built: Sep 22 2026 13:32:06) (NTS)
composer /opt/homebrew/bin/composer Composer version 2.10.3 2026-08-27 13:34:23
dotnet /opt/homebrew/bin/dotnet 10.0.108
frankenphp /Users/dohyunjung/.local/share/langbench-tools/bin/frankenphp FrankenPHP v1.13.1 PHP 8.5.11 Caddy v2.1
```
- mvn 줄의 경고는 JDK 25의 제한 메서드 경고이며 Maven 버전은 3.9.9(`mvn -v`).
- JAVA_HOME=/opt/homebrew/opt/openjdk@25, PATH 앞에 JDK 25와 langbench-tools/bin(bench.env export).

## hello-world 빌드(호스트, 초) — 참고 기록, MAX_SEC는 EXP-001과 같은 2400 고정
- csharp: `dotnet new web` + `dotnet build -c Release` 5
- java: start.spring.io(web, data-jpa, Java 25) + `mvn -DskipTests package` 16 (로컬 ~/.m2 일부 캐시됨, 완전 콜드 아님)
- php: `composer create-project laravel/laravel` 20 + `composer require laravel/octane` 6
- docker no-cache 빌드: 미측정(상한을 바꾸지 않으므로 판정에 영향 없음)

## FrankenPHP 버전 문제 (Phase 0에서 발견·해결)
- 1.12.6 바이너리를 PATH에 두었을 때 `octane:start`가 요구 버전(1.13.0) 미달로 판단해 기존 파일을 frankenphp.backup으로 바꾸고 최신 바이너리(약 180MB)를 앱 디렉터리에 내려받기 시작함. run 안의 툴체인 다운로드에 해당하므로 1.13.1로 교체. 교체 후 `octane:start --server=frankenphp` HTTP 200, 다운로드 없음.

## Fargate 파일럿 (2026-10-19 07:16-07:21)
- 앱: EXP-001 opus55-go-1 사본(run 이름 pilot-go), --tag pilot, --soak-sec 60
- local_prep.sh(레지스트리 전환 후 첫 실행): rc=0, build.json·scc.json·go.json·trivy.json 생성
- Fargate: attempt 1, outcome=ok, files=12, relaunch=0, 앱·부하 모두 CPU part 0xd4f(Graviton4), us-east-1b, error.txt 없음
- 값: cold_start_ms 57, idle_mem 3.7 MiB (EXP-001 같은 앱 cold start 54 ms)
- 판정: Fargate 측정 경로 정상. 새 언어 스캐너는 본 측정에서 언어별 첫 run으로 확인한다.
