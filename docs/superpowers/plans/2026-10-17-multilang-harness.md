# 다언어 실험 하네스 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 같은 RealWorld 백엔드를 5개 언어 × 2개 모델로 구현시키고, 채점(호스트+Docker)·런타임 측정·후속 과제 결과를 `analysis/metrics/runs.csv` 행으로 만드는 실행 장치를 이 리포에 갖춘다.

**Architecture:** 자매 리포의 `ralph-model-benchmark` 스킬을 `claude-native` 하네스만 남겨 이 리포의 `.claude/skills/langbench/`로 이식한다. 기존 구조(정본 assets → `setup.sh`가 `~/experiments/langbench-expNNN/`로 복사·해시 검증 → `run-all.sh`가 `driver.sh`를 순차 실행)는 유지한다. 바뀌는 부분은 세 가지다. 첫째, 자연어 축 `LANGS`가 구현 언어 축이 된다. 둘째, 게이트가 `start.sh` 호스트 채점과 Docker 컨테이너 채점을 둘 다 요구한다. 셋째, 합격 run에 대해 런타임 측정 스크립트와 후속 과제 모드가 추가된다. 측정값을 CSV 행으로 바꾸는 순수 로직은 Python 모듈로 분리해 단위 테스트하고, Docker·k6·스캐너를 호출하는 부분은 얇은 셸 스크립트로 둔다.

**Tech Stack:** bash(macOS 기본 bash 3.2 호환), Python 3 표준 라이브러리 + `unittest`, Orange Hurl 8.0.1(`/opt/homebrew/bin/hurl`), Docker Desktop, k6 v1.1.0, scc, Trivy, 언어별 스캐너(`npm audit`, `pip-audit`, `govulncheck`, `cargo audit`).

**Spec:** [docs/superpowers/specs/2026-10-17-language-benchmark-design.md](../specs/2026-10-17-language-benchmark-design.md). 과제 정본은 [tasks/realworld-backend/README.md](../../../tasks/realworld-backend/README.md), 지표 정본은 [analysis/metrics/SCHEMA.md](../../../analysis/metrics/SCHEMA.md)다.

## Global Constraints

- 모델 ID: `claude-sonnet-5-5`, `claude-opus-5-5`. 조건명은 `sonnet55`, `opus55`이고 `scripts/metrics.py`의 `MODELS`와 같아야 한다.
- 언어 키: `typescript python go rust cpp`. `scripts/metrics.py`의 `LANGUAGES`와 같은 순서·철자다.
- run 이름: `<조건>-<언어>-<n>` (예: `opus55-rust-2`). 후속 과제 run은 `<원 run>-fu`.
- 하네스 디렉터리: `$HOME/experiments/langbench-exp<EXP>` (EXP는 3자리).
- Hurl은 `/opt/homebrew/bin/hurl` 절대 경로로만 실행한다. PATH의 `hurl`은 쓰지 않는다.
- 포트는 고정하지 않는다. 40000–49999에서 빈 포트를 골라 `PORT`로 주입하고, 호스트 채점은 리포 소속 프로세스가 실제 LISTEN한 포트로만 한다. 외부 프로세스는 종료하지 않는다.
- `claude-native`는 `env -u ANTHROPIC_API_KEY -u ANTHROPIC_AUTH_TOKEN -u ANTHROPIC_BASE_URL`로 실행한다(구독 인증 강제).
- 정본(PROMPT 6종, Hurl 14파일)은 `assets/checksums.md5`로 고정하고, 해시가 다르면 `setup.sh`가 중단한다.
- 런타임 측정용 컨테이너 제한은 모든 언어에서 `--cpus=2 --memory=1g`이다(설계 9절이 빌더에게 맡긴 값. 근거: Docker Desktop 할당이 6 CPU·7.7 GiB이므로 k6가 쓸 CPU 4개를 남긴다).
- 미측정 값은 빈 칸이다. 0으로 채우지 않는다.
- 실험 실행(30 run)은 이 계획의 범위가 아니다. 이 계획은 실행 장치와 Phase 0 직전 상태까지 만든다.

## Review Focus

1. **`start.sh`가 빌드에 수 분을 쓰는 언어(Rust·C++)**: 호스트 채점이 서버 LISTEN을 30초만 기다리면 빌드 중인 정상 앱을 실패로 판정한다. 기대 동작은 `START_SEC`(기본 600초)까지 기다리는 것이다. Task 3의 `test_measure_waits_for_slow_start`가 고정한다.
2. **Docker 빌드 실패·무한 대기**: 에이전트의 Dockerfile이 빌드되지 않거나 멈추면 게이트가 멈추지 않고 `0,0`을 내야 한다. Task 3의 `test_docker_build_failure_scores_zero`가 고정한다.
3. **uvicorn workers·Node cluster처럼 자식 프로세스가 포트를 잡는 앱**: 채점 후 자식이 남아 다음 iteration의 포트 탐지를 오염시키면 안 된다. Task 3의 `test_cleanup_kills_child_listeners`가 고정한다.
4. **부하 중 서버가 죽는 run**: k6가 연결 거부로 끝나도 측정 스크립트는 에러율 100%에 가까운 값을 기록하고 다음 단계로 진행해야 한다(측정 불가로 덮지 않는다). Task 5의 `test_k6_summary_all_failed`가 고정한다.
5. **같은 취약점이 Trivy(CVE)와 언어 스캐너(GHSA)에 다른 ID로 나오는 경우**: alias로 연결된 ID는 1건으로 센다. Task 6의 `test_alias_groups_count_once`가 고정한다.

---

## File Structure

```
.claude/skills/langbench/
  SKILL.md                       # 절차서(자매 리포 SKILL.md를 이 리포 규칙으로 축약)
  assets/
    prompt/template.md           # 언어 공통 본문. {{STACK}} {{WORKERS}} 두 자리만 다름
    prompt/stacks.json           # 언어별 STACK·WORKERS 문장
    PROMPT-<lang>.md (5종)        # render_prompts.py 산출물(커밋)
    PROMPT-followup.md           # 후속 과제 프롬프트(언어 공통)
    harness-hurl/*.hurl (13)     # 자매 리포 정본 그대로 복사
    followup-hurl/comments_update.hurl
    checksums.md5
    model-specs.example.json     # sonnet55/opus55 형식 예시
    k6/load.js                   # 혼합 부하 시나리오(측정·soak 공용)
  scripts/
    bench.env.example
    setup.sh  gate.sh  approve.sh  preflight.py  cost_guard.py  watchdog.sh  cleanup.sh  smoke.sh  run-all.sh   # 이식
    driver.sh                    # 이식 + 언어 축 + 이중 게이트 + 후속 모드
    measure.sh                   # 호스트(start.sh) 채점
    measure_docker.sh            # Docker 빌드·컨테이너 채점
    render_prompts.py            # template + stacks → PROMPT-<lang>.md
    runtime.sh                   # 합격 run 런타임 측정 → runtime-<run>.json
    seed.py                      # API 시드 → seed.json
    run_followups.sh             # 합격 run마다 후속 과제 실행
  tests/
    fakes/                       # 가짜 claude, 가짜 앱(fixtures)
    test_prompts.py  test_measure.py  test_driver.py  test_seed.py
scripts/
  runtime_parse.py               # k6 요약·docker stats·scc·의존성·취약점 파싱(순수 함수)
  runs_row.py                    # 하네스 산출물 → runs.csv 행 추가
  test_runtime_parse.py  test_runs_row.py
analysis/metrics/SCHEMA.md       # 기본 가중치·측정 정의 보강
```

이식 스크립트 중 `usage_codex.py usage_pi.py usage_agy.py usage_probe.py pi_env.sh agy_env.sh key_env.sh`와 `reference-profile.json`, `PROMPT-ko.md`는 가져오지 않는다. `claude-native`만 쓰므로 필요 없다. 단, `preflight.py`가 `reference-profile.json`을 읽으므로 그 파일은 복사한다(입력 한도 판정 기준 데이터).

---

### Task 1: 하네스 골격 이식 (claude-native 전용)

**Files:**
- Create: `.claude/skills/langbench/scripts/{setup.sh,gate.sh,approve.sh,preflight.py,cost_guard.py,watchdog.sh,cleanup.sh,smoke.sh,run-all.sh,bench.env.example}`
- Create: `.claude/skills/langbench/assets/{harness-hurl/*.hurl,reference-profile.json,model-specs.example.json}`
- Create: `.claude/skills/langbench/tests/test_setup.py`, `.claude/skills/langbench/tests/fakes/claude`

**Interfaces:**
- Produces: `setup.sh <bench.env>` → `$HOME/experiments/langbench-exp$EXP/`에 `bench.env model-specs.json PROMPT-*.md harness-hurl/ followup-hurl/ k6/` 및 스크립트 사본을 만든다. `bench.env` 변수: `EXP HARNESS=claude-native CONDITIONS="sonnet55:claude-sonnet-5-5 opus55:claude-opus-5-5" LANGS="typescript python go rust cpp" N=3 MAX_ITER=30 MAX_SEC=1200 STALL_SEC=300 START_SEC=600 DOCKER_BUILD_SEC=900 FU_MAX_ITER=5 FU_MAX_SEC=1200 MODEL_SPECS RUN_BUDGET_USD EXP_BUDGET_USD PRICE_IN PRICE_OUT PRICE_CACHE_READ PRICE_TIERS`.
- Produces: `run-all.sh`의 run 순서 = `for n in 1..N; for l in LANGS; for c in CONDITIONS` (자매 리포와 같은 교차).

- [ ] **Step 1: 자매 리포 원본을 복사한다**

```bash
SRC=/Users/dohyunjung/Workspace/roboco-io/research/vibecoding-token-experiments/.claude/skills/ralph-model-benchmark
DST=.claude/skills/langbench
mkdir -p $DST/scripts $DST/assets $DST/tests/fakes
cp $SRC/scripts/{setup.sh,gate.sh,approve.sh,preflight.py,cost_guard.py,watchdog.sh,cleanup.sh,smoke.sh,run-all.sh,driver.sh,measure.sh,bench.env.example} $DST/scripts/
cp -r $SRC/assets/harness-hurl $SRC/assets/reference-profile.json $DST/assets/
```

- [ ] **Step 2: 실패 테스트를 쓴다** — `tests/test_setup.py`

임시 `HOME`(tempfile)과 임시 bench.env로 `setup.sh`를 실행하는 테스트 세 개:
- `test_setup_copies_and_verifies`: 종료 코드 0, `$HOME/experiments/langbench-exp901/harness-hurl/`에 `.hurl` 13개, `bench.env` 사본 존재.
- `test_setup_rejects_tampered_hurl`: assets 사본의 `auth.hurl` 한 줄을 바꾼 스킬 복사본으로 실행하면 종료 코드 ≠ 0, stderr에 `정본 해시 불일치`.
- `test_setup_rejects_non_native_harness`: `HARNESS=codex`이면 종료 코드 ≠ 0.

테스트는 `preflight.py`까지 실행되므로 `model-specs.example.json`을 `MODEL_SPECS`로 넘긴다.

- [ ] **Step 3: 실패 확인** — Run: `python3 -m unittest discover -s .claude/skills/langbench/tests -p 'test_setup.py' -v` / Expected: FAIL(`HARNESS=codex`가 통과하거나 경로가 `ralph-exp`).

- [ ] **Step 4: 스크립트를 claude-native 전용으로 고친다**

`setup.sh`: `BASE="$HOME/experiments/langbench-exp$EXP"`, `case "$HARNESS"`는 `claude-native)`만 남기고 나머지는 `unknown HARNESS` 오류. 스크립트는 고정 목록 대신 `scripts/*`를 전부 복사하고(이후 Task가 추가하는 스크립트도 자동 포함), assets는 `PROMPT-*.md`, `harness-hurl/`, `followup-hurl/`(있으면), `k6/`(있으면)를 복사한다. 해시 검증은 `checksums.md5`에 적힌 파일 목록 기준이다: `(cd assets && md5 -r $(cut -d' ' -f2 checksums.md5) | diff - checksums.md5)`. 추가로 `LANGS`의 각 언어에 `PROMPT-<lang>.md`가 있고 그 파일이 `checksums.md5`에 있어야 하며, 아니면 중단한다. `driver.sh` `cost_path`·`watch_paths`·`invoke_agent`와 `run-all.sh` `archive_sessions`, `smoke.sh`의 조건 분기에서 `claude-native` 이외 분기를 지운다. `cleanup.sh`는 지우는 대상에 각 app의 `target/ .venv/ build/`와 `docker image ls 'lb/*'` 이미지를 더하고, 코드·`.git`·세션·로그는 계속 보존한다. `bench.env.example`은 Interfaces의 변수 목록과 주석만 남긴다(pi·agy·codex·claude-direct 변수 삭제). `model-specs.example.json`은 두 모델 키로 `"billing": "subscription"`과 API 공개 단가 자리(`input output cache_read cache_write source checked_at`)를 두되 값은 `null`로 두고 `_comment`에 "Phase 0에서 공식 가격 문서로 채운다"고 적는다(가격을 추측해 넣지 않는다).

이 Task의 `checksums.md5`에는 Hurl 13개만 넣는다. PROMPT·후속 Hurl 해시는 Task 2·7이 추가한다. 실제 PROMPT가 아직 없으므로 `test_setup.py`는 스킬 디렉터리를 임시 위치로 복사하고, 그 사본에 테스트용 `PROMPT-typescript.md`와 그 md5 줄을 넣은 뒤 `LANGS="typescript"`로 실행한다. 같은 사본 방식이 `test_setup_rejects_tampered_hurl`에도 쓰인다.

- [ ] **Step 5: 통과 확인** — 같은 명령 / Expected: 3 tests OK.

- [ ] **Step 6: Commit** — `git add .claude/skills/langbench && git commit -m "feat(harness): port ralph harness as claude-native langbench skill"`

---

### Task 2: 언어별 프롬프트 5종

**Files:**
- Create: `.claude/skills/langbench/assets/prompt/template.md`, `assets/prompt/stacks.json`, `scripts/render_prompts.py`, `assets/PROMPT-{typescript,python,go,rust,cpp}.md`
- Modify: `assets/checksums.md5`
- Test: `.claude/skills/langbench/tests/test_prompts.py`

**Interfaces:**
- Produces: `render_prompts.py [--check]` — 인자 없으면 5개 PROMPT를 쓰고, `--check`면 디스크의 파일과 렌더 결과가 다를 때 종료 코드 1.
- Produces: `PROMPT-<lang>.md` 5개. driver가 `PROMPT-$LANG_.md`로 고른다.

`template.md`는 자매 리포 `PROMPT-en.md`(md5 `2c28ea6b…`)에서 다음만 바꾼 영어 본문이다. Tech stack 줄은 `- Tech stack: {{STACK}}`. 완료 기준은 다음 네 항목으로 바꾼다. (1) 공식 Hurl 13파일·154요청 통과(원문 그대로). (2) 리포 루트 `start.sh` 한 번 실행으로 의존성 설치·빌드·서버 기동, 포트는 환경변수 `PORT`, 서버는 포그라운드로 계속 실행. (3) 리포 루트 `Dockerfile`이 release(최적화) 빌드 이미지를 만들고, 컨테이너도 `PORT`로 포트를 받으며, 빈 DB로 기동해도 스스로 스키마를 만든다. (4) 서버는 사용 가능한 CPU 코어 수만큼 워커/스레드를 쓴다: `{{WORKERS}}`; SQLite는 WAL 모드와 `busy_timeout` 5000ms를 쓴다. 작업 지시에 한 줄 추가: "Language toolchains are preinstalled on the host; do not install or upgrade compilers or language runtimes." 마지막 줄은 `Do not modify PROMPT.md.`(ralph.sh는 이 하네스에 없으므로 뺀다).

`stacks.json`(설계 3절 표를 그대로 옮김):

| 키 | STACK | WORKERS |
|---|---|---|
| typescript | `TypeScript (Node.js) + Fastify + Prisma + SQLite` | `Node cluster with one worker process per core` |
| python | `Python + FastAPI + SQLAlchemy + SQLite, served by uvicorn` | `uvicorn --workers set to the core count` |
| go | `Go + Gin + GORM + SQLite (pure-Go modernc.org/sqlite driver)` | `GOMAXPROCS equal to the core count` |
| rust | `Rust + Axum + SQLx + SQLite on tokio` | `tokio multi-thread runtime with one worker thread per core` |
| cpp | `C++20 + Drogon + Drogon ORM + SQLite, built with CMake` | `Drogon app().setThreadNum() set to the core count` |

- [ ] **Step 1: 실패 테스트를 쓴다** — `tests/test_prompts.py`
  - `test_render_matches_committed`: `render_prompts.py --check` 종료 코드 0.
  - `test_variants_differ_only_in_slots`: 5개 파일 각각에서 STACK·WORKERS 문장을 `{{STACK}}`·`{{WORKERS}}`로 되돌리면 `template.md`와 바이트 단위로 같다.
  - `test_prompt_requires_contract`: 각 PROMPT에 `start.sh`, `Dockerfile`, `PORT`, `WAL`, `busy_timeout`, `5000`, `.ralph-done` 문자열이 있다.
  - `test_checksums_cover_prompts`: `checksums.md5`에 `PROMPT-<lang>.md` 5줄과 `PROMPT-followup.md` 줄이 있고 실제 md5와 같다(후속 프롬프트는 Task 7에서 생기므로 이 테스트는 Task 7 Step 4에서 통과한다. 이 Task에서는 5개만 검사하는 `test_checksums_cover_language_prompts`로 쓴다).
- [ ] **Step 2: 실패 확인** — Run: `python3 -m unittest discover -s .claude/skills/langbench/tests -p 'test_prompts.py' -v` / Expected: FAIL(`render_prompts.py` 없음).
- [ ] **Step 3: `template.md`, `stacks.json`, `render_prompts.py`를 쓰고 렌더한다.** 렌더는 `str.replace` 두 번이다. 렌더 후 `md5 -r PROMPT-*.md`로 `checksums.md5`의 placeholder 줄을 교체한다.
- [ ] **Step 4: 통과 확인** — 같은 명령 + Task 1 테스트 / Expected: 모두 OK.
- [ ] **Step 5: Commit** — `git commit -m "feat(harness): language prompt variants with slot-only diffs"`

---

### Task 3: 이중 게이트 — 호스트(start.sh)와 Docker 채점

**Files:**
- Modify: `.claude/skills/langbench/scripts/measure.sh`
- Create: `.claude/skills/langbench/scripts/measure_docker.sh`
- Modify: `.claude/skills/langbench/scripts/driver.sh`
- Create: `tests/fakes/app-ok/{start.sh,Dockerfile,server.py}`, `tests/fakes/app-slow/start.sh`, `tests/fakes/app-forks/start.sh`, `tests/fakes/app-badbuild/Dockerfile`, `tests/fakes/claude`
- Test: `.claude/skills/langbench/tests/test_measure.py`, `tests/test_driver.py`

**Interfaces:**
- Produces: `measure.sh <repo> <hurl-dir>...` → stdout `succ_files,exec_reqs`. `start.sh` 없으면 `0,0`. 서버 로그는 `$BASE/server-iter.log`, Hurl 로그는 `$BASE/hurl-last.log`.
- Produces: `measure_docker.sh <repo> <tag> <hurl-dir>...` → stdout `succ_files,exec_reqs`. `Dockerfile` 없거나 빌드 실패·`DOCKER_BUILD_SEC` 초과면 `0,0`. 빌드 로그 `$BASE/docker-build-last.log`, 컨테이너 이름 `lb-<tag>`, 실행 옵션 `--cpus=2 --memory=1g -e PORT=$P -p 127.0.0.1:$P:$P`. 기동 대기는 `GET /api/tags`가 200을 줄 때까지 최대 60초.
- Produces: metrics CSV 열 `iter,종료시각,exit,host_succ,host_reqs,docker_succ,docker_reqs,완료 선언,게이트`. Docker 채점은 호스트가 기대 파일 수를 모두 통과한 iteration에서만 실행하고, 아니면 `docker_succ,docker_reqs`를 빈 칸으로 둔다. 게이트 `pass` = 완료 선언 + 호스트 전부 통과 + Docker 전부 통과.
- 기대 파일 수 `EXPECT_FILES`는 driver가 채점 디렉터리의 `.hurl` 개수로 계산한다(본 과제 13, 후속 14).

가짜 앱 `server.py`는 표준 라이브러리 HTTP 서버로 `/api/tags`에 200을 준다. 테스트는 실제 Hurl 정본 대신 `tests/fakes/hurl-mini/ping.hurl`(GET `{{host}}/api/tags` → 200) 1개 파일로 채점한다. 가짜 `claude`는 인자를 로그에 남기고, 환경변수 `FAKE_CLAUDE_COPY`가 가리키는 앱 디렉터리를 cwd로 복사한 뒤 `.ralph-done`을 만든다.

- [ ] **Step 1: 실패 테스트를 쓴다** — `tests/test_measure.py` (Docker가 없으면 Docker 테스트는 `skipUnless`)
  - `test_host_pass`: `app-ok` → `1,1`.
  - `test_missing_start_sh_scores_zero`: 빈 디렉터리 → `0,0`.
  - `test_measure_waits_for_slow_start`: `app-slow`(LISTEN 전 40초 sleep), `START_SEC=90` → `1,1`.
  - `test_cleanup_kills_child_listeners`: `app-forks`(자식 프로세스가 LISTEN) 채점 후 `lsof -iTCP -sTCP:LISTEN`에 리포 소속 PID가 없다.
  - `test_docker_pass`: `app-ok` → `measure_docker.sh` `1,1`, 종료 후 `docker ps -a --filter name=lb-`에 남은 컨테이너가 없다.
  - `test_docker_build_failure_scores_zero`: `app-badbuild`(존재하지 않는 베이스 이미지) → `0,0`, 60초 안에 끝난다.
  - `tests/test_driver.py::test_gate_requires_both`: 가짜 claude + `app-ok`에서 Dockerfile만 지운 사본 → metrics 마지막 행 게이트 `rejected`, docker 열 `0,0`; 원본 `app-ok` → `pass`.
- [ ] **Step 2: 실패 확인** — Run: `python3 -m unittest discover -s .claude/skills/langbench/tests -p 'test_measure.py' -v` / Expected: FAIL.
- [ ] **Step 3: `measure.sh` 수정.** `package.json` 검사를 `start.sh` 검사로, `npm run dev`를 `bash start.sh`로, LISTEN 대기 30초를 `START_SEC`(기본 600)로 바꾼다. `cleanup`의 `pkill -f "$REPO/node_modules"`를 `pkill -9 -f "$REPO/"`로 일반화하고, 리포 소속 판정(`belongs_to_repo`)은 그대로 둔다. Hurl 대상은 인자로 받은 디렉터리들의 `*.hurl`.
- [ ] **Step 4: `measure_docker.sh` 작성.** 순서: 같은 이름 컨테이너 제거 → `docker build -t lb/<tag> <repo>`를 `DOCKER_BUILD_SEC` 감시 하에 실행(백그라운드+폴링, 초과 시 kill) → 빈 포트 → `docker run -d --name lb-<tag> …` → 기동 대기 → Hurl → `docker rm -f`. 이미지는 남긴다(런타임 측정에서 재빌드하므로 정리는 `cleanup.sh`가 `lb/*` 이미지를 지운다).
- [ ] **Step 5: `driver.sh` 수정.** iteration 뒤 `RESULT=$(measure.sh "$REPO" $HURL_DIRS)`; 호스트 성공 파일 수가 `EXPECT_FILES`면 `DRES=$(measure_docker.sh "$REPO" "$RUN-i$i" $HURL_DIRS)`, 아니면 `DRES=","`. 게이트 판정과 metrics 행을 Interfaces대로 바꾼다. `[ -f "$PROMPT" ]` 오류 메시지는 `unknown language`.
- [ ] **Step 6: 통과 확인** — `python3 -m unittest discover -s .claude/skills/langbench/tests -v` / Expected: 모두 OK(Docker 미기동 시 skip 표시).
- [ ] **Step 7: Commit** — `git commit -m "feat(harness): host start.sh and docker dual gate"`

---

### Task 4: 기본 가중치 고정과 측정 정의 보강

**Files:**
- Modify: `analysis/metrics/SCHEMA.md`
- Modify: `dashboard/index.html` (기본 가중치를 `METRICS`와 같은 위치에서 읽게 할 필요가 있을 때만)
- Test: `scripts/test_score.mjs`

설계 5절은 기본 가중치를 실험 전에 문서에 고정하라고 요구한다. 설계자 판단으로 **모든 지표 가중치 1(균등)** 을 기본으로 고정한다. 근거는 실험 전에 특정 지표를 더 중시할 근거가 없고, 대시보드가 이미 균등 가중을 기본으로 구현하고 있어 문서와 화면이 일치하기 때문이다. SCHEMA.md에 다음을 추가한다. (1) "기본 가중치" 절: 24개 지표 모두 1, EXP-001 실행 후 변경 금지, 변경하려면 새 실험 번호로 사전 등록. (2) "측정 정의" 절: 컨테이너 제한 `--cpus=2 --memory=1g`, `idle_rss_mb`·`peak_rss_mb`는 `docker stats`의 cgroup 메모리 사용량(MemUsage)이며 엄밀한 프로세스 RSS가 아니라는 한계, `loc`는 scc의 주 언어 코드 줄(Task 6 정의), `deps`·`vulns` 집계 규칙(Task 6), k6 시나리오 요약(Task 5), `wall_min`의 시작·끝 시각 정의(Task 8).

- [ ] **Step 1: 실패 테스트를 쓴다** — `scripts/test_score.mjs`에 `index.html`의 초기 가중치 객체가 `METRICS` 전 키에 대해 1인지 확인하는 단언 추가(정규식으로 초기화 코드를 찾지 말고, `score.js`에 `defaultWeights(metrics)`를 export하게 바꾸고 그 반환값을 검사).
- [ ] **Step 2: 실패 확인** — `node scripts/test_score.mjs` / Expected: FAIL(`defaultWeights` 없음).
- [ ] **Step 3: `score.js`에 `defaultWeights(metrics) -> {[key]: 1}` 추가, `index.html`의 초기화·리셋 버튼이 이를 쓰게 바꾼다. SCHEMA.md 두 절 작성.**
- [ ] **Step 4: 통과 확인** — `node scripts/test_score.mjs && cd scripts && python3 -m unittest test_build_dashboard_data test_aggregate_tokens` / Expected: 모두 통과.
- [ ] **Step 5: Commit** — `git commit -m "docs(metrics): fix default equal weights and measurement definitions"`

(SCHEMA.md의 측정 정의 문장은 Task 5–8의 값이 확정된 뒤 최종 확인한다. 값이 바뀌면 해당 Task 커밋에서 같이 고친다.)

---

### Task 5: 시드와 부하 측정 (runtime.sh 1부)

**Files:**
- Create: `.claude/skills/langbench/scripts/seed.py`, `assets/k6/load.js`, `scripts/runtime.sh`
- Create: `scripts/runtime_parse.py`
- Test: `.claude/skills/langbench/tests/test_seed.py`, `scripts/test_runtime_parse.py`

**Interfaces:**
- Produces: `seed.py <base_url> <out.json>` — 고정 시드로 사용자 50명, 사용자당 아티클 10개(총 500), 태그 풀 20개 중 아티클당 3개, 앞 100개 아티클에 코멘트 5개씩, 사용자마다 다음 5명 팔로우, 사용자마다 아티클 5개 즐겨찾기를 HTTP API로 넣는다. `out.json` = `{"users":[{"username","token"}], "slugs":[...], "tags":[...]}`. 이메일·사용자명은 `lb{i}`처럼 결정적. 실패한 요청이 하나라도 있으면 종료 코드 1.
- Produces: `load.js` — `SEED`(seed.json 경로), `BASE`, `VUS`, `DURATION` 환경변수. 요청 비율(설계 9절이 맡긴 값): 목록 `GET /api/articles?limit=20&offset=<0-480>` 35%, 단건 `GET /api/articles/:slug` 20%, `GET /api/tags` 10%, 피드 `GET /api/articles/feed`(인증) 10%, 코멘트 목록 15%, 쓰기 10%(아티클 작성 4%, 코멘트 작성 3%, 즐겨찾기 3%). `summaryTrendStats: ["p(50)","p(95)","p(99)"]`, 응답 코드 2xx만 성공으로 본다.
- Produces: `runtime_parse.k6_summary(path) -> {"rps": float, "p50_ms": float, "p95_ms": float, "p99_ms": float, "error_rate": float}` (`error_rate`는 %). `runtime_parse.mem_mb(text: str) -> float` (`docker stats` MemUsage 앞쪽 값 `"123.4MiB / 1GiB"` → 123.4, `GiB`·`KiB` 환산).
- Produces: `runtime.sh <repo> <run>` 1부 — `$BASE/runtime-<run>/`에 `build.json`(build_sec, image_mb), `cold_start_ms`, `idle_mem.txt`, `load-{1,2,3}.json`(k6 `--summary-export`), `peak_mem.txt`, `soak.json`, `soak_mem_{start,end}.txt`를 쓴다.

`runtime.sh` 측정 순서와 값(빌더 결정):
1. `docker build --no-cache -t lb/<run>:rt` 소요 시간 → `build_sec`, `docker image inspect -f '{{.Size}}'` → `image_mb`(1e6 바이트 단위).
2. 빈 포트로 `docker run -d --cpus=2 --memory=1g` 직후부터 `GET /api/tags` 200까지 50ms 간격 폴링 → `cold_start_ms`.
3. 10초 대기 후 `docker stats --no-stream` → `idle_mem.txt`.
4. `seed.py` 실행.
5. 워밍업 k6 `VUS=32 DURATION=15s` 1회(결과 버림) → 측정 k6 `VUS=32 DURATION=30s` 3회. 측정 중 1초 간격 `docker stats` 샘플을 모아 최대값 → `peak_mem.txt`.
6. soak: `VUS=32 DURATION=${SOAK_SEC:-600}s`. 시작 30초 뒤와 종료 직전 메모리를 기록.
7. 컨테이너 제거. 어느 단계에서 실패하면 이후 단계를 건너뛰고 실패 단계를 `runtime-<run>/error.txt`에 쓴다(뒤 단계 값은 빈 칸이 된다).

- [ ] **Step 1: 실패 테스트를 쓴다**
  - `scripts/test_runtime_parse.py::test_k6_summary`: 픽스처 JSON(`metrics.http_reqs.rate=812.5`, `http_req_duration.p(50)=12.1`, `p(95)=40.0`, `p(99)=88.8`, `http_req_failed.value=0.002`) → `{"rps":812.5,"p50_ms":12.1,"p95_ms":40.0,"p99_ms":88.8,"error_rate":0.2}`.
  - `test_k6_summary_all_failed`: `http_req_failed.value=1.0`, `http_reqs.rate=50` → `error_rate == 100.0`, 예외 없음.
  - `test_mem_mb_units`: `"512KiB / 1GiB"` → 0.5, `"1.5GiB / 2GiB"` → 1536.0, `"87.2MiB / 1GiB"` → 87.2.
  - `tests/test_seed.py::test_seed_against_fake`: 테스트 안에서 띄운 최소 가짜 RealWorld 서버(요청을 세고 정해진 응답을 주는 `http.server`)에 `seed.py` 실행 → 사용자 생성 50, 아티클 생성 500, 코멘트 500, 팔로우 250, 즐겨찾기 250 호출, `out.json`의 `slugs` 길이 500.
- [ ] **Step 2: 실패 확인** — `cd scripts && python3 -m unittest test_runtime_parse -v` 및 `python3 -m unittest discover -s ../.claude/skills/langbench/tests -p 'test_seed.py'` / Expected: FAIL.
- [ ] **Step 3: `runtime_parse.k6_summary`, `mem_mb`, `seed.py`, `load.js`, `runtime.sh` 1부 작성.**
- [ ] **Step 4: 통과 확인** — 같은 명령 / Expected: OK. 추가로 `k6 inspect .claude/skills/langbench/assets/k6/load.js` 종료 코드 0.
- [ ] **Step 5: Commit** — `git commit -m "feat(runtime): api seed, k6 mixed load and memory sampling"`

---

### Task 6: 코드량·의존성·취약점 (runtime.sh 2부)

**Files:**
- Modify: `scripts/runtime_parse.py`, `.claude/skills/langbench/scripts/runtime.sh`
- Test: `scripts/test_runtime_parse.py`, 픽스처 `scripts/fixtures/runtime/*`

**Interfaces:**
- Produces: `runtime_parse.loc(scc_json: list, language: str) -> int` — scc `--format json` 결과에서 주 언어만 합산한다. 주 언어: typescript → `TypeScript`,`JavaScript`; python → `Python`; go → `Go`; rust → `Rust`; cpp → `C++`,`C++ Header`,`C Header`. scc 호출 시 `--exclude-dir test,tests,__tests__,hurl,node_modules,target,build,.venv,vendor`와 `--not-match '(_test\.go|test_.*\.py|.*_test\.py|\.(test|spec)\.[jt]s|_test\.(cc|cpp))$'`로 테스트를 뺀다.
- Produces: `runtime_parse.deps(repo: Path, language: str) -> int | None` — 직접 런타임 의존성 수. typescript: `package.json` `dependencies` 키 수(devDependencies 제외). python: `pyproject.toml` `[project] dependencies` 항목 수, 없으면 `requirements.txt`의 주석·빈 줄·`-r` 제외 줄 수. go: `go.mod` `require`에서 `// indirect` 아닌 모듈 수. rust: `Cargo.toml` `[dependencies]` 키 수. cpp: `CMakeLists.txt`의 `find_package(X`·`FetchContent_Declare(X`·`pkg_check_modules(… X` 고유 이름 수에서 `Threads` 제외. 매니페스트가 없으면 `None`.
- Produces: `runtime_parse.vulns(reports: dict[str, dict]) -> int` — 키 `trivy npm pip go cargo`의 JSON 보고서에서 (id, aliases)를 뽑아 alias로 연결된 ID 묶음 수를 센다. 추출 위치: trivy `Results[].Vulnerabilities[].VulnerabilityID`; npm `vulnerabilities.*.via[]`가 dict면 `url` 끝의 GHSA ID; pip-audit `dependencies[].vulns[].id` + `aliases`; govulncheck JSON 스트림의 `osv.id` + `osv.aliases`(호출 경로가 있는 `finding`의 osv만); cargo audit `vulnerabilities.list[].advisory.id` + `aliases`.
- Produces: `runtime.sh` 2부 — `runtime-<run>/scc.json`, `trivy.json`, `<lang 스캐너>.json`, `hints.txt`. `hints.txt`는 `Dockerfile`·`start.sh`·소스에서 `--release|CMAKE_BUILD_TYPE|--workers|cluster|GOMAXPROCS|worker_threads|setThreadNum|journal_mode|busy_timeout`를 `grep -rnE`한 결과다. 빌드 모드·워커 설정의 최종 판정은 실험자가 이 파일을 보고 report에 적는다(설계 4.2의 "자동 추출 vs 수동 확인"을 반자동으로 정한 것).

- [ ] **Step 1: 실패 테스트를 쓴다** (`scripts/test_runtime_parse.py`)
  - `test_loc_primary_language_only`: scc 픽스처(`Rust 1200`, `TOML 30`, `Dockerfile 20`, `Shell 10`) + `rust` → 1200; `C++ 800`, `C++ Header 150`, `CMake 40` + `cpp` → 950.
  - `test_deps_per_language`: 픽스처 디렉터리 5개 — package.json(deps 4, devDeps 6) → 4; pyproject(deps 5) → 5; go.mod(direct 3, indirect 9) → 3; Cargo.toml(deps 7, dev-deps 2) → 7; CMakeLists(`find_package(Drogon`, `find_package(Threads`, `find_package(SQLite3`) → 2.
  - `test_deps_missing_manifest`: 빈 디렉터리 → `None`.
  - `test_alias_groups_count_once`: trivy에 `CVE-2025-1`, cargo에 `RUSTSEC-2025-9`(aliases `CVE-2025-1`), pip에 `PYSEC-1`(aliases 없음) → 2.
  - `test_vulns_empty_reports_zero`: 모든 보고서가 빈 결과 → 0 (측정한 0).
- [ ] **Step 2: 실패 확인** — `cd scripts && python3 -m unittest test_runtime_parse -v` / Expected: 새 테스트 FAIL.
- [ ] **Step 3: 세 함수와 `runtime.sh` 2부 작성.** `runtime.sh`는 스캐너 하나가 실패하면 그 보고서를 만들지 않고 `error.txt`에 기록한다. Task 8은 보고서가 하나라도 빠지면 `vulns`를 빈 칸으로 둔다(부분 집계로 과소 보고하지 않는다).
- [ ] **Step 4: 통과 확인** — 같은 명령 / Expected: OK.
- [ ] **Step 5: Commit** — `git commit -m "feat(runtime): loc, direct deps and deduped vulnerability counts"`

---

### Task 7: 후속 과제 — 코멘트 수정 API

**Files:**
- Create: `.claude/skills/langbench/assets/followup-hurl/comments_update.hurl`, `assets/PROMPT-followup.md`, `scripts/run_followups.sh`
- Modify: `scripts/driver.sh`, `scripts/run-all.sh`(아카이브 함수 재사용), `assets/checksums.md5`
- Test: `tests/test_driver.py`, `tests/test_prompts.py`

**Interfaces:**
- Produces: `FOLLOWUP=1 bash driver.sh <run>` — `REPO=$BASE/app-<run>-fu`(최초 실행 시 `app-<run>`을 `.git` 포함 복사하고 `.ralph-done` 삭제), `MET=metrics-<run>-fu.csv`, `PROMPT=PROMPT-followup.md`, `HURL_DIRS="harness-hurl followup-hurl"`(기대 14파일), `MAX_ITER=$FU_MAX_ITER`, `MAX_SEC=$FU_MAX_SEC`. 모델은 원 run의 조건에서 가져온다.
- Produces: `run_followups.sh` — `done-all` 이후 실행. metrics 마지막 게이트가 `pass`인 run마다 `FOLLOWUP=1 driver.sh`를 순차 실행하고 세션을 `sessions-<run>-fu`로 보관한다. 실행 승인(`require_approval`)을 똑같이 요구한다.

`comments_update.hurl` 시나리오(기존 정본의 오류 형식 `{"errors":{"<필드>":["<메시지>"]}}`을 따름): 사용자 A·B 등록 → A가 아티클·코멘트 작성 → A가 `PUT /api/articles/{{slug}}/comments/{{comment_id}}` `{"comment":{"body":"Edited body"}}` → 200, `$.comment.id == {{comment_id}}`, `$.comment.body == "Edited body"`, `$.comment.author.username == A`, `updatedAt` ISO 형식 → `GET …/comments`에서 body가 바뀜 → B가 수정 → 403, `$.errors.comment[0] == "forbidden"` → 토큰 없이 → 401, `$.errors.token[0] == "is missing"` → A가 빈 body → 422, `$.errors.body[0] == "can't be blank"` → 없는 코멘트 ID `999999999` → 404, `$.errors.comment[0] == "not found"` → 없는 slug → 404, `$.errors.article[0] == "not found"`. 사용자명은 `cupd_a_{{uid}}`처럼 `uid`로 유일하게 한다.

`PROMPT-followup.md`(영어, 언어 공통): 현재 리포에 RealWorld 백엔드가 있다는 사실, 추가할 엔드포인트의 요청·응답·권한·오류 규칙(위 Hurl과 같은 내용을 문장으로), 기존 Hurl 154요청과 `start.sh`·`Dockerfile` 동작을 깨지 말 것, 새 Hurl 테스트를 직접 작성해 검증할 것, 완료 후 `.ralph-done`, `Do not modify PROMPT.md.` 테스트 파일 자체(`comments_update.hurl`)는 에이전트에게 주지 않는다(채점 정본 격리).

- [ ] **Step 1: 실패 테스트를 쓴다**
  - `tests/test_prompts.py::test_checksums_cover_prompts`(Task 2에서 예고한 6개 버전).
  - `tests/test_driver.py::test_followup_copies_and_gates_on_both_dirs`: `app-<run>`에 가짜 앱, `harness-hurl`=ping.hurl 1개, `followup-hurl`=ping2.hurl 1개 → `FOLLOWUP=1 driver.sh` 후 `app-<run>-fu/.git` 존재, `app-<run>`은 변경 없음(파일 해시 동일), metrics 행의 `host_succ`가 2.
  - `tests/test_driver.py::test_followup_respects_iter_cap`: 가짜 claude가 `.ralph-done`을 만들지 않으면 metrics 행 수가 `FU_MAX_ITER=2`와 같다.
  - `tests/test_measure.py::test_followup_hurl_is_valid`: `/opt/homebrew/bin/hurl --test`를 띄우지 않고 파싱만 확인하기 위해 `hurl --test --variable host=http://127.0.0.1:1 --variable uid=x --connect-timeout 1 comments_update.hurl`의 stderr에 `Parsing error`가 없다.
- [ ] **Step 2: 실패 확인** — `python3 -m unittest discover -s .claude/skills/langbench/tests -v` / Expected: 새 테스트 FAIL.
- [ ] **Step 3: Hurl·프롬프트·`run_followups.sh` 작성, driver에 `FOLLOWUP` 분기 추가, `checksums.md5`에 `PROMPT-followup.md`와 `followup-hurl/comments_update.hurl` 해시 추가(Task 1 placeholder 줄 제거).**
- [ ] **Step 4: 통과 확인** — 같은 명령 / Expected: 모두 OK.
- [ ] **Step 5: Commit** — `git commit -m "feat(followup): comment update task, hurl and follow-up driver mode"`

---

### Task 8: runs.csv 행 생성

**Files:**
- Create: `scripts/runs_row.py`, `scripts/test_runs_row.py`, 픽스처 `scripts/fixtures/harness/`

**Interfaces:**
- Consumes: `metrics-<run>.csv`(Task 3 열), `metrics-<run>-fu.csv`, `driver.log`의 `=== RUN <run> (…) start: <시각>` 줄, `runtime-<run>/`(Task 5·6 파일), `sessions-<run>/`·`sessions-<run>-fu/`(aggregate_tokens 입력), `model-specs.json`, `interventions.csv`(`run,count` — 실험자가 쓴다, 없으면 빈 칸).
- Consumes: `runtime_parse.k6_summary`, `mem_mb`, `loc`, `deps`, `vulns`; `aggregate_tokens.aggregate(Path) -> dict`.
- Produces: `runs_row.py <BASE> <EXP-NNN> <run> [--append analysis/metrics/runs.csv]` — `metrics.CSV_COLUMNS` 순서의 행 1개를 stdout(또는 append). 같은 `run` 행이 이미 있으면 append를 거부하고 종료 코드 1.

열 계산 규칙:
- `pass`: metrics 마지막 숫자 행의 게이트가 `pass`면 1, 아니면 0.
- `wall_min`: driver.log의 run 시작 시각부터 `pass` 행 종료 시각까지(분, 소수 1자리). 불합격이면 빈 칸.
- `iterations`: `pass` 행의 iter. 불합격이면 빈 칸.
- `token_proxy`: `aggregate()`의 `input + output + cache_creation` 합 / 1000. 세션이 없으면 빈 칸.
- `cost_usd`: `input×in + output×out + cache_creation×cache_write + cache_read×cache_read` / 1e6, 단가는 `model-specs.json[모델].pricing`. 단가 중 하나라도 `null`이면 빈 칸(추측 금지).
- `rps p50_ms p95_ms p99_ms error_rate`: `load-{1,2,3}.json` 각각 `k6_summary` 후 지표별 중앙값. 3개가 다 없으면 빈 칸.
- `idle_rss_mb peak_rss_mb soak_mem_growth_mb`(= end − start), `soak_error_rate`, `build_sec image_mb cold_start_ms loc deps vulns`: 해당 파일에서. 파일이 없으면 빈 칸.
- 불합격 run은 결과물 열(rps부터 vulns까지)을 모두 빈 칸으로 둔다(SCHEMA 집계 규칙).
- `followup_pass`: `metrics-<run>-fu.csv`가 없으면 빈 칸, 있으면 마지막 게이트 `pass`→1 아니면 0. `followup_min`·`followup_token_proxy`: 같은 방식(불합격이면 시간 빈 칸, 토큰은 기록).

- [ ] **Step 1: 실패 테스트를 쓴다** (`scripts/test_runs_row.py`, 픽스처는 합격 run 1개·불합격 run 1개·후속 실패 run 1개)
  - `test_pass_row_values`: 합격 픽스처 → `pass=1`, `iterations=2`, `wall_min=12.5`(driver.log 10:00:00, pass 행 10:12:30), `rps`=load 3개(800, 812.5, 790)의 중앙값 800, 열 순서가 `CSV_COLUMNS`와 같다.
  - `test_failed_run_blanks_product_metrics`: 불합격 → `pass=0`, `rps`·`vulns`·`wall_min` 빈 칸, `token_proxy`는 값 있음.
  - `test_cost_blank_when_price_missing`: `cache_write: null` → `cost_usd` 빈 칸.
  - `test_followup_failure_recorded`: 후속 metrics 마지막 게이트 `rejected` → `followup_pass=0`, `followup_min` 빈 칸.
  - `test_append_rejects_duplicate_run`: 같은 run을 두 번 append → 두 번째 종료 코드 1, 파일 행 수 불변.
  - `test_output_roundtrips_dashboard`: 생성 행을 `build_dashboard_data.build()`에 넣으면 예외 없이 해당 셀 `n == 1`.
- [ ] **Step 2: 실패 확인** — `cd scripts && python3 -m unittest test_runs_row -v` / Expected: FAIL.
- [ ] **Step 3: `runs_row.py` 작성.**
- [ ] **Step 4: 통과 확인** — `cd scripts && python3 -m unittest test_runs_row test_runtime_parse test_build_dashboard_data test_aggregate_tokens -v` / Expected: 모두 OK.
- [ ] **Step 5: Commit** — `git commit -m "feat(metrics): build runs.csv rows from harness outputs"`

---

### Task 9: SKILL.md 절차서와 툴체인 점검

**Files:**
- Create: `.claude/skills/langbench/SKILL.md`, `.claude/skills/langbench/scripts/toolchain.sh`
- Modify: `CLAUDE.md`(스킬 경로 한 줄), `ROADMAP.md`(하네스 항목 체크)

**Interfaces:**
- Produces: `toolchain.sh` — 필요한 도구의 절대 경로와 버전을 한 줄씩 출력하고, 하나라도 없으면 종료 코드 1. 대상: `node npm python3 go cargo rustc cmake docker k6 scc trivy pip-audit govulncheck cargo-audit /opt/homebrew/bin/hurl`, 그리고 Drogon(`brew list --versions drogon` 또는 `pkg-config --modversion drogon`). `setup.sh`가 이 출력을 `$BASE/toolchain.txt`로 저장하고 실패 시 중단한다.

SKILL.md는 자매 리포 SKILL.md 절차 1–11을 이 리포 기준으로 옮긴다. 바뀌는 점: 하네스는 `claude-native` 하나, `LANGS`는 구현 언어 5종, 7단계 독립 재검증은 `measure.sh`와 `measure_docker.sh`를 각각 2회, 8단계 뒤에 "후속 과제(`run_followups.sh`) → 런타임 측정(`runtime.sh` 합격 run마다) → `runs_row.py --append`" 단계를 넣는다. 런타임 측정은 다른 Docker 부하가 없는 상태에서 순차로만 실행한다는 규칙을 명시한다. "흔한 실수" 표는 `claude-native`와 무관한 행(pi·agy·codex·DashScope)을 빼고, Review Focus 1–3에 해당하는 행(느린 start.sh, Docker 빌드 정지, 자식 리스너 잔존)을 추가한다.

- [ ] **Step 1: 실패 테스트를 쓴다** — `tests/test_setup.py::test_setup_fails_without_toolchain`: `PATH`에서 `cmake`를 숨긴(빈 디렉터리 PATH + 필수 도구 심볼릭 링크) 환경에서 `setup.sh` 종료 코드 ≠ 0, stderr에 `cmake`.
- [ ] **Step 2: 실패 확인** — Expected: FAIL.
- [ ] **Step 3: `toolchain.sh`, setup 연동, SKILL.md, CLAUDE.md·ROADMAP.md 갱신.**
- [ ] **Step 4: 실제 환경 점검** — Run: `bash .claude/skills/langbench/scripts/toolchain.sh` / Expected: 2026-10-17 기준 `cmake`, Drogon, `scc`, `trivy`, `pip-audit`, `govulncheck`, `cargo-audit`가 없어 종료 코드 1. 이 출력은 Phase 0 직전 설치 요청의 근거로 사용자에게 보고한다(설치 자체는 이 계획 범위 밖이며 사용자 승인 후 진행).
- [ ] **Step 5: 전체 테스트** — `python3 -m unittest discover -s .claude/skills/langbench/tests -v && cd scripts && python3 -m unittest discover -p 'test_*.py' && node test_score.mjs` / Expected: 모두 통과.
- [ ] **Step 6: Commit** — `git commit -m "docs(harness): langbench skill procedure and toolchain check"`

---

## 이 계획 이후 (범위 밖, 순서만 기록)

1. 툴체인 설치(`brew install cmake drogon scc trivy` 등)와 버전 기록 — 사용자 승인 후.
2. `model-specs.json`에 두 모델의 API 공개 단가를 공식 가격 문서로 채운다(추측 금지).
3. Phase 0: 정본 해시 검증, 언어별 hello-world 빌드 시간으로 공통 `MAX_SEC` 확정, TS run으로 자매 리포 기준선(EXP-026/031: iteration 1 완주, 4-8분) 재현 확인.
4. `experiments/001-…/README.md` 작성 → 사양·예상 사용량 보고 → `AskUserQuestion` 승인 → 30 run.
