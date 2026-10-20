# Phase 0 — EXP-003

- 기록: 2026-10-20 13:42:54 +0900
- claude: 2.1.304 (Claude Code)
- hurl: hurl 8.0.1 (x86_64-apple-darwin25.0) libcurl/8.7.1 (SecureTransport) LibreSSL/3.3.6 zlib/1.2.12 nghttp2/1.68.1
- 스모크: PASS claude-sonnet-5-5, PASS claude-opus-5-5 (phase0.log). 3000번대 외부 LISTEN 포트는 종료하지 않음, 하네스가 빈 포트 주입.
- 승인: preflight-approved (2026-10-20, 사용자 "진행 (Recommended)"). Phase 0 문제 시 본 run 전에 멈추고 보고하는 조건.

## toolchain.txt
```
docker /usr/local/bin/docker Docker version 29.8.0, build 88096ef
k6 /opt/homebrew/bin/k6 k6 v1.1.0 (go1.24.4, darwin/arm64)
scc /opt/homebrew/bin/scc scc version 4.1.0
trivy /opt/homebrew/bin/trivy Version: 0.76.0
python3 /opt/homebrew/bin/python3 Python 3.14.4
hurl /opt/homebrew/bin/hurl hurl 8.0.1 (x86_64-apple-darwin25.0) libcurl/8.7.1 (SecureTransport) LibreSSL/3.3.6 zlib/1.2.12 nghttp2/1.68.1
java /opt/homebrew/opt/openjdk@25/bin/java openjdk version "25.0.4.1" 2026-08-18
gradle /opt/homebrew/bin/gradle 
osv-scanner /opt/homebrew/bin/osv-scanner osv-scanner version: 2.7.0
java /opt/homebrew/opt/openjdk@25/bin/java openjdk version "25.0.4.1" 2026-08-18
sbt /opt/homebrew/bin/sbt 2.0.11
osv-scanner /opt/homebrew/bin/osv-scanner osv-scanner version: 2.7.0
```
- gradle 줄이 비어 있는 것은 `gradle --version` 첫 줄이 빈 줄이기 때문이다. 버전은 9.8.2(Launcher JVM 25.0.4.1).
- JAVA_HOME=/opt/homebrew/opt/openjdk@25, PATH 앞에 JDK 25(bench.env export). Gradle·sbt 모두 이 JDK로 실행됨을 확인.

## hello-world 빌드(호스트, 초) — 참고 기록, MAX_SEC는 EXP-001과 같은 2400 고정
- kotlin: start.spring.io(gradle-project-kotlin, web, data-jpa, Java 25; Spring Boot 4.1.1, Kotlin 2.3.21) + 설치된 `gradle bootJar -x test` 35
- scala: Play 3.0.9 + Scala 3.3.6 + play-slick 6.2.0 + sqlite-jdbc, sbt 1.11.7(build.properties) `sbt stage` 10(스캐너 검증으로 Coursier 캐시가 일부 채워져 완전 콜드 아님). JDK 25에서 기동해 HTTP 200 확인
- docker no-cache 빌드: 미측정(상한을 바꾸지 않으므로 판정에 영향 없음)

## 스캐너 검증 (2026-10-20)
- kotlin: Gradle init 스크립트 dependency locking → productionRuntimeClasspath lockfile → osv-scanner. log4j-core 2.14.1 탐지, testImplementation(junit)·developmentOnly(devtools) 제외 확인
- scala: `sbt export Runtime/managedClasspath` jar 좌표 → lockfile → osv-scanner. log4j-core 2.14.1 탐지, Test 의존성 제외 확인

## Fargate 파일럿 (2026-10-20 13:36-13:42)
- 앱: EXP-001 opus55-go-1 사본(run 이름 pilot-go), --tag pilot, --soak-sec 60
- 첫 시도는 ECR 로그인 토큰 만료로 push 실패(prep-failed, pilot/failed-push/). docker login 후 재시도
- 재시도: attempt 1, outcome=ok, files=12, relaunch=0, CPU part 0xd4f(Graviton4), us-east-1a, error.txt 없음
