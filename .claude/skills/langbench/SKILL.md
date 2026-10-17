---
name: langbench
description: Use when running or re-running the language-benchmark experiment (같은 RealWorld 백엔드를 TypeScript·Python·Go·Rust·C++로 구현하는 Ralph 루프, 후속 과제, 런타임 측정, runs.csv 집계), or when preparing its harness (EXP-NNN 추가, Phase 0, 하네스 생성).
---

# 다언어 Ralph 루프 벤치마크

## 개요

같은 RealWorld 백엔드 과제를 구현 언어 5종으로 만들게 하고, 과정 지표(iteration, 시간, 토큰)와 결과물 지표(런타임 성능, 코드량, 취약점)를 비교한다. 정본(PROMPT 6종, Hurl 14파일, k6 스크립트)과 공통 스크립트는 이 스킬의 `assets/`·`scripts/`에 있다. 실행 시 `$HOME/experiments/langbench-expNNN`(NNN은 3자리 실험 번호)으로 복사되고 해시 검증으로 고정된다. 실험 품질 규칙은 `docs/experiment-quality-rules.md`, 과제 정의는 `tasks/realworld-backend/README.md`, 지표 정의는 `analysis/metrics/SCHEMA.md`가 정본이다. 이 스킬은 그 절차를 실행 가능한 형태로 묶은 것이다.

자매 리포(`vibecoding-token-experiments`)의 `ralph-model-benchmark` 스킬에서 이식했으며 달라진 점은 다음과 같다. 하네스는 `claude-native` 하나만 지원한다. 실험 축은 자연어(EN/KO)가 아니라 구현 언어이며 `LANGS`는 `typescript python go rust cpp`다. 완주 게이트는 호스트 Hurl 채점과 Docker 이미지 Hurl 채점을 모두 통과해야 한다. 본 과제 뒤에 후속 과제와 런타임 측정 단계가 있다.

## 하네스와 조건

| 항목 | 값 |
|---|---|
| HARNESS | `claude-native` (구독 인증. 실행 시 `env -u ANTHROPIC_API_KEY -u ANTHROPIC_AUTH_TOKEN -u ANTHROPIC_BASE_URL`로 API 키를 제거한다) |
| 모델 조건 | `sonnet55:claude-sonnet-5-5`, `opus55:claude-opus-5-5` (조건명에 `-` 금지) |
| run 이름 | `<조건>-<언어>-<n>` (예: `opus55-rust-2`). 후속 과제 run은 `<원 run>-fu` |
| 실행 순서 | 반복 번호 단위로 언어×조건을 교차하는 순차 실행. 다른 실험·Docker 작업과 동시 실행 금지 |

## 절차

1. **사전 조사와 모델 사양**: 모델 ID를 추측하지 않는다. 입력 컨텍스트 한도, 최대 출력, 단가를 공식 문서에서 확인해 출처·확인일과 함께 `model-specs.json`으로 작성한다(형식은 `scripts/preflight.py` 주석, 예시는 `assets/model-specs.example.json`). 확인하지 못한 값은 추정하지 않고 사용자에게 알린다. 단가에는 두 가지 주의가 있다. 현재 값은 claude-api 스킬 기준(2026-10-06 캐시)이며 sonnet55는 입력 2, 출력 10, 캐시 읽기 0.2, 캐시 쓰기(5분) 2.5, opus55는 입력 4, 출력 20, 캐시 읽기 0.2, 캐시 쓰기(5분) 5.0 USD/MTok이다(R15). 캐시 쓰기는 5분 TTL 단가로 계산하므로 1시간 TTL로 쓴 토큰은 과소 계산된다. 그리고 run은 구독에 과금되므로 `cost_usd`는 API 정가 기준 대리지표이며 청구액이 아니다.
2. **설계 문서**: 다음 번호로 `experiments/NNN-<slug>/README.md`를 `templates/experiment-readme.md`로 작성한다. 질문, 조건, 반복 수, 상한, 판정 기준을 실행 전에 고정하고 `hypotheses/catalog.md`에 가설을 등록한다. 툴체인 버전은 5단계에서 만든 `toolchain.txt`를 옮겨 적는다.
3. **하네스 생성**: `scripts/bench.env.example`을 복사해 채운다(`MODEL_SPECS`·`RUN_BUDGET_USD`·`EXP_BUDGET_USD` 필수). 그다음 `bash .claude/skills/langbench/scripts/setup.sh <bench.env>`를 실행한다. setup은 다음을 한다.
   - `toolchain.sh`를 실행해 결과를 `$BASE/toolchain.txt`에 저장하고, 도구가 하나라도 없으면 중단한다.
   - 정본 PROMPT·Hurl·k6를 복사하고 `assets/checksums.md5`와 대조한다. 해시가 다르거나 `LANGS`의 PROMPT가 없으면 중단한다.
   - 스크립트를 동결 복사하고 `preflight.py`로 `preflight-report.md`를 만든다.

   `LB_SKIP_TOOLCHAIN=1`은 테스트 전용 우회다. 단위 테스트가 툴체인이 없는 환경에서 setup을 돌릴 때만 쓰며, 실험에서는 쓰지 않는다.
4. **실행 전 점검과 사용자 승인 (필수)**: `preflight-report.md`(입력 한도 적합성, 단가, run당·실험 전체 추정 비용, 최악 총액)를 사용자에게 보여 주고 `AskUserQuestion`으로 진행 여부를 묻는다. 사용자가 진행을 고른 경우에만 `bash $BASE/approve.sh "<답변 원문>"`을 실행한다. 승인 기록이 없거나 승인 뒤 `bench.env`·`model-specs.json`·보고서가 바뀌면 smoke.sh·run-all.sh·run_followups.sh가 실행을 거부한다.
5. **Phase 0**: `bash $BASE/smoke.sh`가 조건 전부 PASS여야 기동한다. smoke는 모델 ID, 구독 인증(`"apiKeySource":"none"`), `cost_guard.py` 실행 가능 여부를 확인한다. 이어서 다음을 `runs/phase0.md`에 기록한다.
   - `claude --version`, `/opt/homebrew/bin/hurl --version`(`hurl 8.x (... libcurl ...)` 형식), `toolchain.txt` 내용.
   - 에이전트에 노출된 지침·스킬·MCP(`claude-native`는 비격리).
   - 언어별 hello-world 빌드 시간으로 확정한 공통 `MAX_SEC`.
   - TS run으로 자매 리포 기준선(iteration 1 완주, 4-8분) 재현을 확인한 결과.

   `MAX_SEC`는 채점 시간(호스트 기동 대기, Docker 빌드)을 포함한 run 전체의 wall-clock 상한이며 모든 언어에 같은 값을 쓴다(R5). 빌드가 느린 언어는 같은 상한 안에서 iteration 여유가 줄어들며, 이는 metrics 타임스탬프에 그대로 드러난다.
6. **기동**: `nohup caffeinate -is bash $BASE/run-all.sh >/dev/null 2>&1 &`. 진행은 `orchestrator.log`, `driver.log`, `metrics-<run>.csv`, `cost-guard.csv`(30초 간격 추정 비용)로 확인하고 개입하지 않는다. 같은 오류가 반복되는 등 이상 징후가 보이면 원인을 조사하기 전에 먼저 멈추고 사용자에게 알린다. 구독 한도로 중단된 run은 서비스 장애 보류로 처리하고 재실행한다. driver는 중단된 run을 다시 시작하면 `metrics-<run>.csv`의 마지막 iteration 다음부터 이어서 실행한다.
7. **독립 재검증**: 완주(게이트 `pass`) run마다 `measure.sh`와 `measure_docker.sh`를 각각 2회 실행해 결과가 `13,154`로 일치하는지 확인하고 `recheck.csv`로 남긴다.
   - 호스트: `bash $BASE/measure.sh $BASE/app-<run> $BASE/harness-hurl`
   - Docker: `bash $BASE/measure_docker.sh $BASE/app-<run> <run>-recheck $BASE/harness-hurl`
8. **usage 집계**: `python3 scripts/aggregate_tokens.py --json $BASE/sessions-<run>/`(message.id 중복 제거). 결과는 토큰 대리지표이며 청구 비용이 아니다.
9. **후속 과제**: 8단계까지 끝나 `done-all`이 생긴 뒤 `nohup caffeinate -is bash $BASE/run_followups.sh >/dev/null 2>&1 &`를 실행한다. 스크립트는 metrics 마지막 게이트가 `pass`인 run마다 `FOLLOWUP=1 driver.sh`로 코멘트 수정 API 과제를 순차 실행한다. 앱은 `app-<run>-fu`로 복사되어 이어서 작업하며 채점은 14파일(본 과제 13 + 후속 1)이다. 세션은 `sessions-<run>-fu`에 분리된다.
10. **런타임 측정**: 게이트를 통과한 run마다 `bash $BASE/runtime.sh $BASE/app-<run> <run>`을 순차로 실행한다. 다른 Docker 부하(다른 컨테이너 빌드, 후속 과제 채점, 병렬 측정)가 없는 상태에서 한 번에 한 run씩만 돌린다. 부하가 겹치면 rps·지연·메모리가 오염된다. 측정 동작은 다음과 같다.
    - 1부: 빌드, 콜드 스타트, 유휴 메모리, 시드(`seed.py`), 워밍업 후 k6 부하 3회, soak. 컨테이너는 모든 언어에 `--cpus=2 --memory=1g`이다.
    - 빌드는 작업 트리가 아니라 앱 git HEAD의 임시 clean clone에서 한다(R8). 미추적 파일(`node_modules`, `target`, DB)에 기대는 Dockerfile은 빌드에 실패하며, 이는 재현성 결함으로 본다.
    - 2부(정적 분석): `scc`, `hints.txt`, 이미지 `lb/<run>:rt`의 trivy 스캔, 언어별 취약점 스캐너. 1부의 Docker 단계가 실패해도 2부는 실행한다(R13). trivy는 이미지가 없으면 보고서를 만들지 않는다. 스캐너 하나가 실패하면 `error.txt`에 `scan:<이름>: <사유>`를 덧붙이고 나머지를 계속한다.
    - 산출물은 `$BASE/runtime-<run>/`에 쓴다. 메모리 파일(`idle_mem.txt`, `peak_mem.txt`, `soak_mem_start.txt`, `soak_mem_end.txt`)은 MiB 숫자 하나, `cold_start_ms.txt`는 정수다(R11). `load-1.json`·`load-2.json`·`load-3.json`은 k6 요약이다. `error.txt`가 있으면 종료 코드가 1이다.
    - 스캐너 경로: `PIP_AUDIT_BIN`(기본 `$HOME/.local/share/langbench-tools/venv/bin/pip-audit`), `GOVULNCHECK_BIN`(기본 `$HOME/go/bin/govulncheck`), cargo-audit는 `$HOME/.cargo/bin`. `toolchain.sh`도 같은 경로를 쓴다(R10).
11. **runs.csv 행 생성**: 원 run마다 `python3 scripts/runs_row.py $BASE EXP-NNN <run> --append analysis/metrics/runs.csv`. 미측정 값은 빈 칸이다. 빈 칸 규칙은 다음과 같다.
    - `wall_min`과 `followup_min`은 `driver.log`에서 그 run의 모든 재개 구간을 합산한다(R16).
    - `rps`·지연·`error_rate`는 `load-1/2/3.json` 3개가 모두 있어야 계산한다. 하나라도 없으면 빈 칸이다(R16).
    - 취약점 수는 trivy와 해당 언어 스캐너 보고서가 모두 있어야 한다. 하나라도 없으면 빈 칸이다. C++은 trivy만 필요하다.
12. **보관(마스킹 선행)**: 리포에 넣기 전에 `python3 scripts/redact_keys.py <복사본 디렉터리>`로 비밀값을 마스킹한다. `bench.env`, 스크립트, `metrics-*.csv`, `runtime-*/`, `toolchain.txt`, `orchestrator.log`, `driver.log`, `phase0.md`, `recheck.csv`, `preflight-report.md`·`preflight-approved`, 로그(`gzip`)를 `experiments/NNN-*/runs/`로 복사한다. run별 앱 소스는 `experiments/NNN-*/runs/apps/<run>/`에 커밋한다(빌드 산출물·의존성·DB 제외).
13. **보고·동기화**: `templates/report.md`로 `report.md`를 쓰고 repo `CLAUDE.md`의 "실험 종료 시 필수 절차" 1-5(헤더 세 줄, i18n·README 재생성, `runs.csv`·대시보드 재생성, catalog·ROADMAP 갱신, push 후 Pages 확인)를 그대로 수행한다.
14. **로컬 정리**: 보관·재검증·보고가 끝나면 `bash $BASE/cleanup.sh $BASE`. lockfile이 있는 `node_modules`, 각 앱의 `target/`·`.venv/`·`build/`, Docker 이미지 `lb/*`를 지운다. 코드·`.git`·세션·로그·metrics는 보존하고 삭제 목록은 `cleanup.log`에 남는다.

## metrics CSV

열은 `iter,종료시각,exit,host_succ,host_reqs,docker_succ,docker_reqs,완료 선언,게이트`다. 게이트 `pass`만 완주다. 에이전트가 `.ralph-done`을 선언해도 호스트와 Docker 채점이 모두 기대 파일 수(본 과제 13, 후속 14)를 통과하지 못하면 `rejected`로 삭제된다. Docker 채점은 호스트 채점이 기대 파일 수를 통과한 iteration에서만 실행하므로, 그렇지 않은 행의 Docker 열은 비어 있다. `timeout,<시각>` 행은 `MAX_SEC` 도달, `budget,<시각>` 행은 비용 상한 도달이다. iteration exit `124`는 정체 감시(`STALL_SEC`), `125`는 run 상한, `126`은 비용 상한이 종료한 것이다.

## 흔한 실수

| 실수 | 결과 | 대응 |
|---|---|---|
| 입력 한도·과금 체계를 확인하지 않고 기동 | 작은 컨텍스트 모델이 한도에 닿아 400 반복·구간 최고 단가 과금(자매 리포 EXP-042) | `model-specs.json` 작성, preflight 보고서, `AskUserQuestion` 승인, `approve.sh`. 승인이 없으면 smoke·run-all이 거부한다 |
| 비용 상한을 사후 집계로만 둠 | 제공자 usage 누락으로 2 run에 약 $92 과금(자매 리포 EXP-042) | `RUN_BUDGET_USD`·`EXP_BUDGET_USD` 필수. 하네스가 30초마다 추정해 초과하면 종료(exit 126)하고 `budget-stop`을 만든다 |
| `cost-guard.csv`가 0만 기록하는데 그대로 둠 | 빈 `PRICE_*`로 `cost_guard.py`가 예외를 내 비용 상한이 무력화(자매 리포 EXP-045) | driver는 추정 실패 시 run을 멈추고(fail-closed, `cost-guard.err`) smoke가 조건마다 `cost_guard.py`를 실행해 점검한다. 구독 실행의 단가 0과 예외를 구분한다 |
| PATH의 `hurl` 사용 | npm shim이 실 Hurl을 가려 0건 통과 오검(자매 리포 EXP-020) | 채점은 `/opt/homebrew/bin/hurl` 절대 경로로만 한다. `hurl --version`을 기록한다 |
| 포트를 고정(3000 등)해 채점·서버 기동 | 외부 프로세스가 점유한 포트로 채점 요청이 가 거짓 음성 | driver·measure가 40000-49999 빈 포트를 골라 `PORT`로 주입하고, 호스트 채점은 리포 소속 프로세스가 실제 LISTEN한 포트로만 한다. 외부 프로세스는 종료하지 않는다. `PORT`를 무시하고 고정 바인딩해 실패한 앱은 산출물 결함이다 |
| `claude-native`를 `ANTHROPIC_API_KEY`가 export된 셸에서 실행 | API 키가 구독 로그인보다 우선해 API 과금·5분 캐시 TTL로 실행(자매 리포 EXP-037) | driver·smoke가 `env -u`로 실행하고 smoke가 `"apiKeySource":"none"`을 확인한다. `phase0.md`에 인증 출처를 기록한다 |
| 에이전트가 장기 실행 서버를 포그라운드로 실행 | 도구 호출이 반환되지 않아 iteration 정체(자매 리포 EXP-039) | `STALL_SEC` 정체 감시와 iteration 중 run 상한이 종료한다. 정체 종료는 이탈로 기록한다 |
| 느린 `start.sh` | 호스트 기동 대기(`START_SEC`)가 `MAX_SEC`를 잠식해 iteration 여유가 줄어든다 | `START_SEC` 초과 시 `0,0`으로 채점한다. 기동 시간은 언어 간 비교 대상이므로 상한을 언어별로 다르게 두지 않는다 |
| Docker 빌드 정지 | `measure_docker.sh`가 반환되지 않아 driver가 대기하고 `MAX_SEC`를 소모한다 | `DOCKER_BUILD_SEC` 초과 시 빌드를 중단하고 `0,0`으로 채점한다. `docker-build-last.log`로 원인을 확인한다 |
| 호스트 통과 후 Docker 실패(host-pass/docker-fail) | 게이트 기각이 반복된다. 대개 `0.0.0.0` 바인딩 누락, `.dockerignore` 부재, Dockerfile이 미추적 파일에 의존하는 문제다 | PROMPT에 힌트를 더하지 않는다(R6). Dockerfile 정확성은 측정 대상 산출물이며 지시는 언어 간 동일하다. 로그는 `hurl-docker-last.log`이고 호스트 로그 `hurl-last.log`와 따로 쓴다 |
| 자식 리스너 잔존 | 채점 뒤 앱의 자식 프로세스가 포트를 계속 잡아 다음 채점·측정을 교란한다 | measure는 리포 소속 프로세스 트리를 정리한다. iteration 종료 후 metrics 행이 수 분째 안 생기면 `hurl-last.log`로 채점 완료를 확인하고 잔존 서버 트리를 종료한 뒤 `runs/deviations.md`에 기록한다. 리포 소속이 아닌 프로세스는 종료하지 않는다 |
| 런타임 측정을 다른 Docker 부하와 겹쳐 실행 | rps·지연·메모리 오염 | 10단계 규칙대로 순차로만 실행하고, 측정 중 다른 컨테이너 작업을 하지 않는다 |
| 프롬프트·Hurl 정본 수정 | 비교 불가 | `assets/` 정본만 사용한다. 해시가 다르면 setup이 중단된다 |
| 키가 에이전트 세션 로그·아카이브에 남음 | API 키 커밋 | 보관 전 `redact_keys.py`로 마스킹한다. pre-commit 훅을 `--no-verify`로 우회하지 않는다 |
| 에이전트 run 안에서 툴체인 설치 | 언어 간 환경 차이 | 툴체인은 실험자가 미리 설치하고 `toolchain.sh`로 점검한다. 도구가 없으면 setup이 중단된다 |
| "3/3 완주 = 재현성 확정" 서술 | 관측 범위 초과 | 표본 수와 조건을 함께 적는다. 언어 우위는 확정하지 않는다 |
