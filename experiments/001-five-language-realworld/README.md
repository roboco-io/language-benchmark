# EXP-001: 5개 언어 RealWorld 백엔드 Ralph 루프

> [실험 품질 규칙](../../docs/experiment-quality-rules.md)을 적용한다. 미측정·미적용 항목에는 이유를 쓴다. 이 문서의 조건·상한·판정 기준은 2026-10-18 실행 전에 고정했다.

## 가설

- 가설 코드·문장: [L-01](../../hypotheses/catalog.md). 같은 RealWorld 백엔드 과제에서 바이브 코딩 적합성(만드는 과정 지표와 결과물 지표의 종합 점수)은 구현 언어(TypeScript, Python, Go, Rust, C++) 사이에 차이가 있으며, 그 순위는 Sonnet 5.5와 Opus 5.5에서 같다.
- 질문 유형: 실무 조합 효율. 코딩 에이전트에게 백엔드를 맡길 때 어떤 구현 언어가 완주 가능성, 소요 시간·토큰, 결과물 성능·보안에서 유리한지 본다.
- 실무 선택 질문: "Claude Code(Sonnet 5.5 또는 Opus 5.5)로 CRUD 중심 REST 백엔드를 처음부터 만들 때 어떤 언어를 고르는가."
- 실험 단계: 탐색 파일럿. 조건×언어당 3회는 순위의 방향을 보는 규모이며 차이를 확정하지 않는다.
- 주 지표와 사전 판정 기준: 주 지표는 대시보드 종합 점수(24개 지표 균등 가중, [SCHEMA](../../analysis/metrics/SCHEMA.md) "기본 가중치")의 언어별 순위다. 판정은 다음과 같이 고정한다. (1) 한 언어가 두 모델 모두에서 종합 점수 1위이고 2위와의 차이가 run 간 범위(최솟값-최댓값)와 겹치지 않으면 "지지(탐색)"로 쓴다. (2) 두 모델의 1위가 다르거나 순위가 범위 안에서 겹치면 "부분 지지" 또는 "결론 보류"로 쓴다. (3) 합격률이 0인 언어는 결과물 지표가 빈 칸이므로 종합 점수 비교에서 그 사실을 따로 적는다. 종합 점수는 해석 보조이며 결론 문장은 개별 지표와 함께 쓴다.
- 보조 지표: 합격 여부, iteration 수, wall_min, token_proxy, cost_usd(API 정가 환산 추정), 후속 과제 합격·시간·토큰, rps·p50/p95/p99·error_rate, 메모리(유휴·최대·soak 증가), 빌드 시간·이미지 크기·콜드 스타트, 코드량, 직접 의존성 수, 취약점 수. 정의는 [SCHEMA](../../analysis/metrics/SCHEMA.md).

## 과제·완료 기준

- 과제 정의: [realworld-backend](../../tasks/realworld-backend/). 본 과제는 신규 구현, 후속 과제는 기능 추가(코멘트 수정 API `PUT /api/articles/:slug/comments/:id`).
- 초기 코드: 빈 git 리포(하네스 `driver.sh`가 run마다 새로 만든다). DB는 SQLite.
- 프롬프트 정본: `.claude/skills/langbench/assets/PROMPT-{typescript,python,go,rust,cpp,followup}.md`. 언어 프롬프트 5종은 같은 템플릿(`assets/prompt/template.md`)에 `STACK`·`WORKERS` 슬롯만 다르게 채운다(`assets/prompt/stacks.json`). 해시는 `assets/checksums.md5`(파일 자체 md5 `e5e9291660c15acb632655d3c99e1a55`).
- 채점 정본: Orange Hurl 8.0.1, `/opt/homebrew/bin/hurl` 절대 경로. 본 과제 13파일 154요청(`assets/harness-hurl/`), 후속 과제는 여기에 `assets/followup-hurl/comments_update.hurl` 1파일을 더한 14파일. 채점 정본은 에이전트 리포에 복사하지 않는다.
- 완료 기준: 에이전트의 `.ralph-done` 선언 + 호스트 `start.sh` 서버 채점 전 파일 통과 + `Dockerfile` 이미지 컨테이너 채점 전 파일 통과(게이트 `pass`). 자기 선언만으로는 완료가 아니며, 불일치 선언은 `rejected`로 삭제된다. 합격 run은 `measure.sh`·`measure_docker.sh` 각 2회 독립 재검증으로 확인한다.
- 평가 과제 분리: 숨긴 테스트는 쓰지 않는다. 자매 리포와 같이 채점 정본을 공개 사양(RealWorld spec)으로 두고 에이전트에게 테스트 파일을 주지 않는다. 후속 과제 Hurl도 에이전트에게 주지 않는다.

## 조건 (Conditions)

| 조건 | 모델·리비전·추론 설정 | 실행 도구·버전 | 작업 전략 | 제공자·연결 계층 | 원자료 |
|------|--------------------|--------------|----------|----------------|--------|
| sonnet55 | `claude-sonnet-5-5`, Claude Code 기본 effort | Claude Code 2.1.302 `claude -p --dangerously-skip-permissions` | Ralph 루프(iteration마다 새 세션) | Anthropic 구독(claude-native, API 키 제거) | `runs/` |
| opus55 | `claude-opus-5-5`, Claude Code 기본 effort | 같음 | 같음 | 같음 | `runs/` |

언어 축: `typescript python go rust cpp`. 스택은 TypeScript(Fastify+Prisma), Python(FastAPI+SQLAlchemy, uvicorn), Go(Gin+GORM, modernc SQLite), Rust(Axum+SQLx, tokio), C++20(Drogon+Drogon ORM, CMake)이며 모두 SQLite다. 워커는 코어 수만큼 쓰도록 같은 문장으로 지시한다.

## 환경·비교 범위

- 바꾸는 요인: 구현 언어(5) × 모델(2). 고정: 과제, 프롬프트 템플릿, 채점 정본, 상한, 하드웨어, Claude Code 버전.
- 해석의 한계: 언어와 함께 프레임워크·ORM·빌드 도구가 바뀐다. 결과는 "언어 + 지정 스택" 조합의 비교다. 각 언어의 대표 스택 하나만 쓰므로 스택 선택의 영향과 언어의 영향을 분리하지 않는다.
- 하드웨어·OS: macOS Darwin 25.6.0(Apple Silicon), Docker Desktop 6 CPU·약 7.7 GiB. 런타임 측정 컨테이너는 모든 언어에 `--cpus=2 --memory=1g`.
- 툴체인: Phase 0 `toolchain.txt`(→ `runs/phase0.md`). Node v24.14.0, Python 3.14.4, Go 1.27.2, rustc 1.99.1, CMake·Drogon 1.9.14(호스트), k6 v1.1.0, Trivy, scc, pip-audit 2.10.1, govulncheck, cargo-audit 0.22.2. 에이전트는 run 안에서 툴체인을 설치하지 않는다(프롬프트 지시).
- 설정 스냅샷: `claude-native`는 격리하지 않는다. 에이전트 세션에는 실험자 글로벌 지침(`~/.claude/CLAUDE.md`), 사용자 스킬, MCP 서버(browsermcp, context7, exa)가 노출된다. 리포 CLAUDE.md는 앱 디렉터리(`~/experiments/langbench-exp001/app-*`)가 이 리포 밖이라 노출되지 않는다. 같은 노출이 모든 조건에 동일하다.
- 실제 응답 모델: 세션 jsonl의 `message.model`로 run마다 확인한다. 다른 모델 응답이 섞이면 이탈로 기록한다.
- 구독 추가 사용: 구독 인증만 쓴다(smoke에서 `apiKeySource: none` 확인). 한도 도달 시 하네스가 exit 75로 배치를 멈추고 해당 run을 처음부터 다시 실행한다(서비스 장애 보류).
- 오염 방지: 매 게이트 전 채점 자산·하네스 스크립트 해시를 스킬 리포 기준으로 대조한다(`tampered`). 집계 전 `scripts/contamination_check.py`로 다른 run 앱·채점 정본 참조를 검사한다.
- 기준선: 자매 리포 EXP-026(Opus 5.5)·EXP-031(Sonnet 5.5) TS run(전부 iteration 1 완주, 세션 4-8분). 앱 코드가 보존되지 않아 결과물 지표는 비교하지 않고, Phase 0에서 새 하네스의 TS 재현 확인에만 쓴다.

## 반복·실행 순서·예산

- 반복 수: 조건×언어당 3회, 총 30 run. 탐색 파일럿 규모다.
- 실행 순서: 반복 번호 블록(1, 2, 3) 순서, 블록 안의 10개 run은 seed `20261018-<반복>`으로 섞은 순차 실행(`run-order.txt`). 동시 실행 없음.
- run 초기화: run마다 새 빈 git 리포, 새 세션. 이전 run 산출물은 같은 `$BASE`의 다른 디렉터리에 있고, 참조 여부는 오염 검사로 확인한다.
- 상한: run당 `MAX_SEC=2400`(40분, 채점 시간 포함, 재개 시 구간 합산), `MAX_ITER=30`, 정체 `STALL_SEC=300`, 기동 대기 600초, docker build 900초. 후속 과제는 `FU_MAX_SEC=1200`, `FU_MAX_ITER=5`. `MAX_SEC`는 Phase 0 hello-world 빌드 측정(호스트 콜드/도커 no-cache 초: ts 13/45, py 6/13, go 22/46, rust 36/87, cpp 7/66)에서 Rust·C++의 반복당 채점 시간을 감안해 모든 언어 공통으로 정했다.
- 비용 상한: 공개 단가(claude-api 스킬 모델 표, 2026-10-06 캐시, 2026-10-18 확인) sonnet55 입력 $2·출력 $10·캐시 읽기 $0.2·캐시 쓰기(5분) $2.5, opus55 입력 $4·출력 $20·캐시 읽기 $0.2·캐시 쓰기(5분) $5.0 /MTok. `RUN_BUDGET_USD=15`, `EXP_BUDGET_USD=300`, 최악 총액 $300(실험 상한이 먼저 강제). run은 구독 과금이므로 이 금액은 API 정가 환산 추정치이며 청구액이 아니다. 사용자 승인 2026-10-18 09:4x KST("진행").
- 사람 개입: 없음. 하네스 정지·재시작만 허용하며 `interventions.csv`에 기록한다.
- 실패·중단·제외·재실행: 구독 한도 → 보류 후 처음부터 재실행. 하네스 결함으로 run이 왜곡되면 원인 수정 후 해당 run을 재실행하고 `runs/deviations.md`에 기록한다. 에이전트 산출물의 실패(기동 실패, 포트 고정, Dockerfile 오류)는 결과로 남기고 재실행하지 않는다. 오염 검사에서 다른 run 참조가 나오면 내용과 판단을 기록한다.

## Ralph 정책

- iteration 세션: 매 iteration 새 `claude -p` 세션. 코드·DB·git 기록은 유지, 대화 메모리는 유지하지 않는다.
- 공개 피드백: 다음 iteration에는 같은 PROMPT만 준다. 채점 결과는 에이전트에게 주지 않는다.
- 최종 채점: 에이전트 선언 시점의 호스트·Docker 게이트, 이후 독립 재검증.
- 지표: 합격 여부, 합격 iteration, wall_min, token_proxy. 후속 과제는 별도 열.

## 계측·분석 계획

- usage 원자료: run별 Claude Code 세션 jsonl(`sessions-<run>/`, 서브에이전트 포함).
- 집계기: `scripts/aggregate_tokens.py --json --recursive`(message.id 중복 제거), `scripts/runs_row.py`.
- 토큰 정의: `token_proxy` = (입력 + 출력 + 캐시 쓰기) / 1000. 캐시 읽기는 별도 원자료로 남긴다.
- 비용 정의: API 정가 환산 추정(`cost_usd`). 캐시 쓰기는 5분 TTL 단가로 계산하므로 1시간 TTL 토큰은 과소 계산된다.
- 시간 정의: driver.log run 시작부터 pass 행 종료까지, 재개 구간 합산(분).
- 런타임: 합격 run마다 `runtime.sh`를 다른 Docker 부하 없이 순차 실행. 작업 트리 export로 빌드, 시드 후 k6(VUs 32) 30초×3 중앙값, 10분 soak.
- 분석: 언어×모델 셀별 합격률(n=3)과 합격 run 지표의 중앙값·범위. 전체 결과와 합격 조건부 결과를 구분한다. 결과물 지표는 합격 run만 측정한다.

## Phase 0 기록

- [x] 제공자 스모크(두 모델 PASS, 구독 인증) — `runs/smoke.log`
- [x] 프롬프트·채점 정본 해시 확인(setup) — `runs/preflight-report.md`
- [x] 채점기 경로·버전(`/opt/homebrew/bin/hurl` 8.0.1) — `runs/phase0.md`
- [x] TS 기준선 재현 run(`sonnet55-typescript-0`): iteration 1 pass, 약 4.1분 — `runs/phase0.md`
- [ ] 구독 한도 메시지 실제 문구 — 한도를 만나면 기록, 만나지 않으면 "미관측"
- 증거: `runs/phase0.md`
