## Phase 0 절차 편차 (2026-10-20)

- Fargate 파일럿 첫 시도는 ECR 로그인 토큰 만료로 이미지 push에 실패했다(`prep-failed`, 산출물은 `pilot/failed-push/`). `docker login` 후 같은 사양으로 다시 실행해 통과했다. 앱·측정과 무관한 인증 문제다.
- `tools` 이미지는 새로 빌드하지 않고 EXP-002가 2026-10-19에 push한 이미지를 재사용했다. 그 뒤 `Dockerfile.tools`, `probe.py`, `runner.sh`, `seed.py`, `load.js`에는 커밋 변경이 없다.

## 오염 검사 참조 1건: opus55-scala-3 (2026-10-20)

`contamination_check.py`가 `opus55-scala-3` 세션의 Bash 호출 1건을 잡았다. 명령은 `ls /Users/dohyunjung/experiments/langbench-exp003/ | head -50`이고, 같은 명령에서 Coursier 캐시에 `sqlite-jdbc`·`jwt-scala`·`java-jwt`·`jbcrypt`가 있는지 확인했다. 하네스 디렉터리의 이름 목록만 보았고, 다른 `app-*`의 파일이나 `harness-hurl/`·`metrics-*`를 연 호출은 없다(참조 1건). 다른 run의 코드나 채점 정본 내용을 얻지 않았으므로 결과에 포함한다. Coursier·Gradle 캐시는 run 사이에 공유되며, 이는 EXP-002의 Maven `~/.m2` 공유와 같은 조건이다.

## Fargate 측정 인프라 실패와 재측정 (2026-10-20 16:05-21:07)

언어별 첫 run 측정에서 `sonnet55-scala-1`이, 나머지 측정에서 `opus55-scala-1`·`sonnet55-kotlin-2`·`opus55-kotlin-3`·`sonnet55-kotlin-3`이 세 시도 모두 측정값 없이 끝났다. 사유는 Graviton4 태스크를 교체 상한 20회 안에 얻지 못한 `cpu-unavailable`과 Spot 회수(`spot-interrupted`)다. 측정값이 하나도 없으므로 앱과 무관한 인프라 실패로 보고 같은 사양으로 다시 측정했다(EXP-001·EXP-002와 같은 처리). `sonnet55-kotlin-2`·`opus55-kotlin-3`은 2라운드(3회)와 3라운드(5회)에서도 실패해, 사용자 결정("지금 재시도")에 따라 4라운드(5회)에서 측정했다. 최종 12/12 Graviton4(CPU part 0xd4f). 시도 기록은 `fargate/fargate-attempts.csv`, 라운드별 로그는 `fargate/fargate-*.log`에 있다. 첫 측정에 실패한 `sonnet55-scala-1` 산출물(정적 분석만 있음)은 `invalid/fargate-first-sonnet55-scala-1/`에 둔다.

`opus55-kotlin-3`의 3라운드 3번째 시도(`fargate/raw/opus55-kotlin-3/a3/`)는 Spot 회수 중 k6가 요청 실패 경고를 대량으로 남겨 `k6.log`가 311MB였다. 측정값으로 쓰지 않는 시도이며, 보관 용량 때문에 `k6.log.gz`로 압축했다.

## 보관 시 비밀값 마스킹 (2026-10-20)

`opus55-kotlin-2`·`sonnet55-kotlin-3`(과 각 후속 과제 앱)은 `application.properties`에 JWT 서명 비밀값의 개발용 기본값(`${JWT_SECRET:<기본값>}` 형식)을 넣었다. 실제 자격 증명은 아니지만 pre-commit 키 검사가 막으므로, 앱 소스와 해당 세션 기록(`sessions/*.tar.gz`)에서 그 두 값을 `<REDACTED:JWT_SECRET>`으로 바꿨다. 측정은 마스킹 전 원본으로 했으며 결과에는 영향이 없다.
