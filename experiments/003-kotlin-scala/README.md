# EXP-003: Kotlin·Scala RealWorld 백엔드 Ralph 루프

> [실험 품질 규칙](../../docs/experiment-quality-rules.md)을 적용한다. 미측정·미적용 항목에는 이유를 쓴다. 이 문서의 조건·상한·판정 기준은 2026-10-20 실행 전에 고정한다. 절차는 스킬 [langbench-add-language](../../.claude/skills/langbench-add-language/SKILL.md)를 따른다.

## 가설

- 가설 코드·문장: [L-03](../../hypotheses/catalog.md). 같은 RealWorld 백엔드 과제에서 바이브 코딩 적합성(만드는 과정 지표와 결과물 지표의 종합 점수)은 Kotlin과 Scala 사이에 차이가 있으며, 두 언어의 순위는 Sonnet 5.5와 Opus 5.5에서 같다.
- 질문 유형: 실무 조합 효율. EXP-001·EXP-002와 같은 질문을 JVM 계열 두 언어로 넓힌다.
- 실무 선택 질문: "Claude Code(Sonnet 5.5 또는 Opus 5.5)로 CRUD 중심 REST 백엔드를 처음부터 만들 때 Kotlin과 Scala 중 무엇을 고르는가. 그리고 EXP-001·EXP-002의 여덟 언어와 비교하면 어디쯤인가."
- 실험 단계: 탐색 파일럿. 조건×언어당 3회는 순위의 방향을 보는 규모이며 차이를 확정하지 않는다.
- 주 지표와 사전 판정 기준: 주 지표는 **두 언어(Kotlin, Scala)만 비교 대상으로 둔** 대시보드 종합 점수(EXP-001과 같은 24개 지표 균등 가중, 상수 지표 제외)의 언어별 순위다. 판정 규칙은 EXP-001·EXP-002와 같다. (1) 한 언어가 두 모델 모두에서 1위이고 2위와의 차이가 run 간 범위(최솟값-최댓값)와 겹치지 않으면 "지지(탐색)". (2) 두 모델의 1위가 다르거나 순위가 범위 안에서 겹치면 "부분 지지" 또는 "결론 보류". (3) 합격률이 0인 언어는 결과물 지표가 빈 칸이므로 그 사실을 따로 적는다. 두 언어만 비교하면 정규화한 종합 점수가 지표마다 0 또는 1에 몰리므로, 보조 분석의 10개 언어 순위를 함께 보고한다.
- 보조 분석(판정에 쓰지 않음): EXP-001·EXP-002 여덟 언어와 합친 10개 언어 순위. 실행 시점이 다르므로 **다른 시점의 탐색적 비교**로만 보고한다(아래 버전 차이 표). Kotlin과 EXP-002 Java는 같은 프레임워크(Spring Boot + JPA)를 쓰므로 두 언어의 차이에는 언어와 빌드 도구(Gradle 대 Maven)가 함께 섞여 있다.
- 보조 지표: EXP-001과 같다. 정의는 [SCHEMA](../../analysis/metrics/SCHEMA.md).

## 과제·완료 기준

- 과제·후속 과제·완료 기준·평가 과제 분리: EXP-001과 같다([EXP-001 README](../001-five-language-realworld/README.md) "과제·완료 기준").
- 프롬프트 정본: `.claude/skills/langbench/assets/PROMPT-{kotlin,scala,followup}.md`. 두 언어 프롬프트는 EXP-001과 같은 템플릿(`assets/prompt/template.md`)에 레지스트리(`languages/<key>.json`)의 `STACK`·`WORKERS`만 채운다. md5는 `assets/checksums.md5`에 있다.
- 채점 정본: EXP-001과 같은 Orange Hurl 8.0.1, `/opt/homebrew/bin/hurl`, 본 과제 13파일 154요청, 후속 과제 14파일.

## 조건 (Conditions)

| 조건 | 모델·리비전·추론 설정 | 실행 도구·버전 | 작업 전략 | 제공자·연결 계층 | 원자료 |
|------|--------------------|--------------|----------|----------------|--------|
| sonnet55 | `claude-sonnet-5-5`, Claude Code 기본 effort | Claude Code 2.1.303 `claude -p --dangerously-skip-permissions` | Ralph 루프(iteration마다 새 세션) | Anthropic 구독(claude-native, API 키 제거) | `runs/` |
| opus55 | `claude-opus-5-5`, Claude Code 기본 effort | 같음 | 같음 | 같음 | `runs/` |

언어 축: `kotlin scala`. 스택(사용자 결정 2026-10-20)은 다음과 같고 모두 SQLite다.

| 언어 | 스택(프롬프트 STACK) | 워커 지시(WORKERS) |
|------|------|------|
| Kotlin | Kotlin (JVM, Java 25) + Spring Boot + Spring Data JPA (Hibernate) + SQLite, Gradle(Kotlin DSL), 설치된 `gradle` 명령 사용 | 내장 Tomcat 스레드 풀을 코어 수에 맞춤 |
| Scala | Scala 3 (JVM, Java 25) + Play Framework + Slick + SQLite, sbt | Play의 Pekko HTTP 서버와 기본 디스패처 스레드 풀을 코어 수에 맞춤 |

Kotlin 사용자 선택지는 Ktor + Exposed, Spring Boot + JPA(Gradle), Spring Boot + JPA(Maven)였고 Spring Boot + JPA(Gradle)를 골랐다. Scala 선택지는 Play + Slick, http4s + Doobie, Pekko HTTP + Slick이었고 Play + Slick을 골랐다. Kotlin STACK에 "설치된 `gradle` 명령"을 넣은 이유는 Gradle wrapper(`gradlew`)가 run 안에서 Gradle 배포판을 내려받는 것을 막기 위해서다. sbt는 프로젝트의 `project/build.properties`가 정한 sbt 버전과 Scala 컴파일러를 의존성처럼 내려받는다. 이것은 sbt의 표준 동작이고 Maven 플러그인·Gradle Kotlin 플러그인 다운로드와 같은 범주라 막지 않고 측정 대상에 둔다.

## EXP-001·EXP-002 대비 버전 차이

| 항목 | EXP-001 | EXP-002 | EXP-003 | 비고 |
|------|------|------|------|------|
| 실행일 | 2026-10-18 | 2026-10-19 | 2026-10-20 | |
| Claude Code | 2.1.302 | 2.1.302 | 2.1.303 | 패치 버전이 하나 올랐다 |
| 모델 ID | `claude-sonnet-5-5`, `claude-opus-5-5` | 같음 | 같음 | 서비스 측 모델 갱신 여부는 관측 불가 |
| 프롬프트 템플릿·채점 정본·k6 | 기준 | 커밋 변경 없음 | 커밋 변경 없음 | Phase 0에 해시 재확인 |
| 하네스 스크립트 | 레지스트리 전환 전 | 레지스트리 전환 후 | EXP-002 수정(`measure.sh` 포트 탐지 `75d0a42`, C# 스캐너) 포함 | 포트 탐지 수정은 단일 포트 앱의 채점 동작을 바꾸지 않는다 |
| 런타임 측정 | Fargate Spot Graviton4, 앱 2 vCPU·1 GiB | 같음 | 같음 | 실행 시점의 Spot 용량은 다를 수 있다 |
| 언어 툴체인 | 해당 없음 | 해당 없음 | OpenJDK 25.0.4.1, Gradle 9.8.2, sbt 런처 2.0.11, osv-scanner 2.7.0 | 아래 "환경" |
| 스캐너 | pip-audit·govulncheck·cargo-audit·npm audit | osv(SBOM)·composer audit·dotnet list package | osv(빌드 도구가 해석한 런타임 클래스패스) | 스캐너가 달라 취약점 수의 언어 간 직접 비교에는 한계가 있다 |

## 환경·비교 범위

- 바꾸는 요인: 구현 언어(2) × 모델(2). 고정: 과제, 프롬프트 템플릿, 채점 정본, 상한, 하드웨어.
- 해석의 한계: EXP-001과 같이 "언어 + 지정 스택" 조합의 비교다. 10개 언어 순위는 실행 시점이 다른 탐색적 비교다.
- 하드웨어·OS: EXP-001과 같은 머신(macOS Darwin 25.6.0, Apple Silicon, Docker Desktop 6 CPU·약 7.7 GiB).
- 툴체인(실험자가 2026-10-20에 설치, 에이전트는 run 안에서 설치하지 않음): OpenJDK 25.0.4.1(`/opt/homebrew/opt/openjdk@25`, `bench.env`에서 `JAVA_HOME`·PATH 지정), Gradle 9.8.2(Homebrew), sbt 런처 2.0.11(Homebrew), osv-scanner 2.7.0. Kotlin 컴파일러와 Scala 컴파일러는 따로 설치하지 않는다. 각각 Gradle Kotlin 플러그인과 sbt가 프로젝트에 지정된 버전을 의존성으로 받는다.
- 설정 스냅샷·실제 응답 모델·구독 사용·오염 방지: EXP-001과 같다.

## 반복·실행 순서·예산

- 반복 수: 조건×언어당 3회, 본 과제 12 run + 후속 과제 12 run.
- 실행 순서: 반복 블록(1, 2, 3) 순서, 블록 안의 4개 run은 seed로 섞은 순차 실행(`run-order.txt`). 동시 실행 없음.
- 상한: EXP-001과 같다(`MAX_SEC=2400`, `MAX_ITER=30`, `STALL_SEC=300`, 기동 대기 600초, docker build 900초, `FU_MAX_SEC=1200`, `FU_MAX_ITER=5`).
- 비용 상한: 단가는 EXP-001과 같다. `RUN_BUDGET_USD=15`, `EXP_BUDGET_USD=200`. run은 구독 과금이므로 API 정가 환산 추정치이며 청구액이 아니다.
- 사람 개입·실패·중단·제외·재실행 규칙: EXP-001과 같다.

## Ralph 정책·계측·분석 계획

- EXP-001과 같다. 런타임은 Fargate Spot 병렬 측정(스킬 `fargate/orchestrate.py --exp EXP-003`), S3 접두어 `exp003/`.
- 분석: 언어×모델 셀별 합격률(n=3)과 합격 run 지표의 중앙값·범위. 판정은 두 언어 비교로 하고, 10개 언어 비교는 보조로 쓴다.

## Phase 0 기록

- [ ] setup(툴체인·레지스트리·정본 해시) — `runs/toolchain.txt`, `runs/preflight-report.md`
- [ ] 제공자 스모크(두 모델 PASS, 구독 인증) — `runs/phase0.log`
- [ ] hello-world 빌드 시간(호스트 콜드 / docker no-cache) — `runs/phase0.md`
- [ ] Fargate 파일럿(`local_prep.sh`·Fargate 측정 경로 확인) — `runs/phase0.md`
- [ ] 실행 승인 — `runs/preflight-approved`
