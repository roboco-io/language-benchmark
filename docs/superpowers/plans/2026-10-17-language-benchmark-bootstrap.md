# language-benchmark 부트스트랩 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `roboco-io/language-benchmark` 리포를 만들고, 기존 리포의 운영 골격을 언어 비교용으로 옮기고, 빈 상태의 "언어 × 지표" 대시보드를 `roboco.io/language-benchmark/`에 배포한다.

**Architecture:** 측정 결과는 run 단위 CSV(`analysis/metrics/runs.csv`) 하나에 모인다. `scripts/build_dashboard_data.py`가 이를 언어 × 모델별로 집계해 `dashboard/data.js`(`window.BENCH`)를 생성하고, 정적 `dashboard/index.html`(vanilla JS + SVG)이 이를 읽어 표·종합 점수·상세 차트를 그린다. pre-commit 훅이 README 결과 블록과 data.js를 재생성하고, GitHub Actions가 `dashboard/`를 Pages로 배포한다. 다언어 Ralph 하네스 이식은 이 계획의 범위가 아니다(별도 계획, 실험 승인 후).

**Tech Stack:** Python 3 표준 라이브러리(unittest, csv, json), vanilla JS/SVG, GitHub Actions(`actions/deploy-pages@v4`), gh CLI.

**Spec:** `docs/superpowers/specs/2026-10-17-language-benchmark-design.md`

**경로 약칭:** `SRC` = `/Users/dohyunjung/Workspace/roboco-io/research/vibecoding-token-experiments` (기존 리포), `DST` = `/Users/dohyunjung/Workspace/roboco-io/research/language-benchmark` (신규 리포).

## Global Constraints

- 언어 5종 순서 고정: `typescript, python, go, rust, cpp` (표시명 TypeScript, Python, Go, Rust, C++).
- 모델 2종: `sonnet55` = `claude-sonnet-5-5`, `opus55` = `claude-opus-5-5`.
- 스택 표(TS Fastify+Prisma, Python FastAPI+SQLAlchemy, Go Gin+GORM, Rust Axum+SQLx, C++ Drogon+Drogon ORM, DB 전부 SQLite)는 스펙 3절에서 그대로 옮긴다.
- 원문 언어는 한국어. README는 `README.ko.md`(정본), `README.md`(영어), `README.ja.md`, `README.zh-CN.md` 4종.
- Markdown 본문에 `~` 금지(범위는 `-`), 삭제선 금지.
- 가설 코드 축 `L`은 이 리포에서 "구현 언어"를 뜻한다고 README와 catalog에 정의한다.
- 앱 소스는 `experiments/NNN-slug/runs/apps/<run>/`에 커밋하고 `node_modules/ target/ .venv/ build/ dist/ __pycache__/ *.db *.sqlite*`는 `.gitignore`로 제외한다.
- 대시보드는 외부 CDN 스크립트를 쓰지 않는다(Google Fonts만 허용). 런타임 측정은 macOS Docker Desktop VM 위 측정이므로 상대 비교로만 해석한다는 문구를 대시보드에 표시한다.
- 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

- 데이터 0건(현재 상태): 스크립트와 대시보드가 에러 없이 5개 언어 빈 골격과 "아직 실험 없음" 안내를 보여야 한다 → Task 2·3 테스트.
- 한 (언어, 모델)의 run이 전부 실패: 결과물 지표는 "측정 0/3", 건당 비용은 유한값이 아니라 `null`(표시 "—")이어야 한다 → Task 2 테스트.
- CSV 빈 칸(미측정): 0으로 바꾸지 말고 `null`로 남겨야 한다(기존 품질 규칙 "누락은 미측정") → Task 2 테스트.
- 한 지표에 값이 1개 언어뿐이거나 모두 같을 때: min-max 정규화의 0 나누기 없이 모두 1.0 처리 → Task 3 테스트(JS 함수를 node로 실행).
- 가중치 슬라이더를 전부 0으로: 종합 점수는 NaN이 아니라 "—" → Task 3 테스트.

---

### Task 1: 로컬 리포 골격과 운영 문서

**Files:**
- Create: `DST/{CLAUDE.md, AGENTS.md, .gitignore, ROADMAP.md, README.ko.md, README.md, README.ja.md, README.zh-CN.md}`
- Create: `DST/docs/experiment-quality-rules.md`, `DST/templates/{experiment-readme.md, report.md}`, `DST/hypotheses/catalog.md`, `DST/tasks/realworld-backend/README.md`
- Create: `DST/docs/superpowers/specs/2026-10-17-language-benchmark-design.md`, `DST/docs/superpowers/plans/2026-10-17-language-benchmark-bootstrap.md` (scratchpad에서 복사)

- [ ] **Step 1:** `mkdir DST && git -C DST init -b main`.
- [ ] **Step 2:** `SRC`의 `docs/experiment-quality-rules.md`, `templates/*`를 복사하고, 모델·하네스 비교 문구를 "언어 비교, 모델·하네스 고정"으로 바꾼다. 품질 규칙 본문(채점 정본 격리, 비용 상한, Phase 0, dedup, "3/3은 관측값")은 유지한다. report 템플릿 헤더 3줄 형식(`- 가설:`, `- 클라이언트:`, `- **판정: X** —`)은 바이트 단위로 유지한다(스크립트가 파싱).
- [ ] **Step 3:** `tasks/realworld-backend/README.md`를 `SRC`에서 가져와 "기술 스택" 절을 스펙 3절의 5개 언어 표로 바꾸고, 기동 규약(`start.sh` + `Dockerfile`, 둘 다 Hurl 154요청 통과가 합격 조건)과 워커·SQLite 규칙(코어 수 워커, Node cluster, WAL + `busy_timeout` 5초)을 넣는다.
- [ ] **Step 4:** `CLAUDE.md`·`AGENTS.md`를 `SRC` 기준으로 쓰되 TS 전용 규칙(`npm run dev` 등)을 언어 공통 규칙으로 바꾸고, 실험 종료 절차(report → `scripts/readme_i18n.json` → 스크립트 → catalog·ROADMAP → `analysis/metrics/runs.csv` 갱신)를 적는다.
- [ ] **Step 5:** `hypotheses/catalog.md`에 L 축 정의와 초기 가설 `L-01: 바이브 코딩 백엔드 적합성(종합 점수)은 언어 간 차이가 있으며 그 순위는 Sonnet 5.5와 Opus 5.5에서 같다`를 상태 "미실험"으로 넣는다. `ROADMAP.md`에 EXP-001(5 언어 × 2 모델 × 3회) 항목을 미체크로 넣는다.
- [ ] **Step 6:** 4개 README를 쓴다. 각 README 첫 문장은 "코딩 에이전트(Claude Code)가 같은 RealWorld 백엔드를 언어별로 구현한 결과를 비교한다"는 뜻을 담고, 언어 런타임 순수 벤치마크가 아님을 명시한다. 대시보드 링크 `https://roboco.io/language-benchmark/`, 자매 리포 링크, `<!-- RESULTS:BEGIN -->`/`<!-- RESULTS:END -->` 마커를 넣는다.
- [ ] **Step 7:** 검증: `grep -rn '~' DST --include=*.md | grep -v '~/'` 결과 0줄, `grep -c 'RESULTS:BEGIN' DST/README*.md`가 파일당 1.
- [ ] **Step 8:** 커밋 `docs: bootstrap language-benchmark operating docs`.

### Task 2: 지표 스키마와 집계 스크립트

**Files:**
- Create: `DST/analysis/metrics/runs.csv` (헤더만), `DST/analysis/metrics/SCHEMA.md`
- Create: `DST/scripts/metrics.py`, `DST/scripts/build_dashboard_data.py`, `DST/scripts/test_build_dashboard_data.py`
- Copy as-is from SRC: `scripts/{update_readme_results.py, readme_i18n.json(빈 객체로), check_staged_keys.py, redact_keys.py, aggregate_tokens.py, test_aggregate_tokens.py}`, `hooks/pre-commit`

**Interfaces:**
- Produces: `scripts/metrics.py`의 `METRICS: list[dict]` — 각 항목 `{"key": str, "group": "process"|"product", "better": "lower"|"higher", "unit": str, "ko": str, "en": str}`; `LANGUAGES`, `MODELS` 상수.
- Produces: `build(rows: list[dict]) -> dict` in `build_dashboard_data.py`, 반환 `{"generated": ISO8601, "languages": [...], "models": [...], "metrics": METRICS, "cells": {lang: {model_or_"all": {"n": int, "passed": int, metric_key: float|None, metric_key+"_n": int}}}, "runs": rows}`; CLI `python3 scripts/build_dashboard_data.py`가 `dashboard/data.js`에 `window.BENCH = <json>;` 기록.

`runs.csv` 열(순서 고정): `exp,run,language,model,rep,pass,wall_min,iterations,interventions,token_proxy,cost_usd,rps,p50_ms,p95_ms,p99_ms,error_rate,idle_rss_mb,peak_rss_mb,soak_error_rate,soak_mem_growth_mb,loc,deps,build_sec,image_mb,cold_start_ms,vulns,followup_pass,followup_min,followup_token_proxy`. `pass`·`followup_pass`는 0/1.

집계 규칙(셀 = 언어 × 모델, 그리고 두 모델 합친 `all`):
- `pass_rate` = passed/n. `cost_per_pass` = 전체 run `cost_usd` 합 / passed, passed가 0이면 `None`.
- `wall_min`, `iterations`, `interventions`, `token_proxy`: 합격 run의 중앙값.
- 결과물 지표(rps부터 vulns까지)와 후속 과제 지표: 합격 run 중 값이 있는 것의 중앙값, `<key>_n`에 표본 수. 빈 칸은 `None`(0 아님). `followup_pass`는 비율.
- `better`: `rps, pass_rate, followup_pass`만 higher, 나머지 lower.

- [ ] **Step 1: 실패 테스트 작성** (`unittest`)
  - `test_empty_rows`: `build([])["cells"]`에 5개 언어 × (`sonnet55`, `opus55`, `all`)가 모두 있고 `n == 0`, 모든 지표 `None`.
  - `test_all_failed_cell`: rust/opus55 3 run 전부 `pass=0`, `cost_usd=1.0` → `pass_rate == 0.0`, `cost_per_pass is None`, `rps is None`, `rps_n == 0`.
  - `test_median_and_missing`: go/sonnet55 합격 3 run의 `rps` = `100, 300, ""` → `rps == 200.0`, `rps_n == 2`.
  - `test_all_combines_models`: go 두 모델 각 1 run 합격 `wall_min` 4, 8 → `cells["go"]["all"]["wall_min"] == 6.0`.
  - `test_cost_includes_failures`: 2 run 중 1 합격, cost 1.0·3.0 → `cost_per_pass == 4.0`.
- [ ] **Step 2:** `python3 -m unittest scripts/test_build_dashboard_data.py` → FAIL(import error).
- [ ] **Step 3:** `metrics.py`와 `build()`·CLI 구현(`statistics.median`).
- [ ] **Step 4:** 테스트 PASS, `python3 -m unittest scripts/test_aggregate_tokens.py` PASS.
- [ ] **Step 5:** `update_readme_results.py`를 실험 0건으로 실행해 4개 README의 RESULTS 블록에 "아직 완료된 실험이 없다"(언어별 번역) 한 줄이 들어가는지 확인. 0건에서 실패하면 그 경우만 처리하도록 고친다.
- [ ] **Step 6:** `hooks/pre-commit`의 details.js 재생성 줄을 `runs.csv` 또는 `scripts/metrics.py` 변경 시 `build_dashboard_data.py` 실행으로 바꾼다. `git -C DST config core.hooksPath hooks`.
- [ ] **Step 7:** 커밋 `feat: metrics schema and dashboard data builder`.

### Task 3: 대시보드

**Files:**
- Create: `DST/dashboard/index.html`, `DST/dashboard/score.js`, `DST/dashboard/data.js`(Task 2 CLI 산출물), `DST/scripts/test_score.mjs`
- Copy/adapt: `SRC/scripts/render_og.sh`, `SRC/scripts/og/og-card.html` (index.html의 `M` 배열 대신 `data.js`의 `window.BENCH`를 읽도록), `.claude/launch.json`(포트 8792)

**Interfaces:**
- Consumes: `window.BENCH`(Task 2).
- Produces: `score.js`의 `normalize(values: (number|null)[], better: "lower"|"higher") -> (number|null)[]`(0-1, 값이 1개 이하이거나 전부 같으면 값 있는 칸 1.0), `composite(cellsByLang, metrics, weights: {key: number}) -> {lang: number|null}`(값 있는 지표만 가중평균, 가중치 합 0이면 `null`). 브라우저에서는 `window.Score`, node에서는 `export`.

화면 구성(스펙 5절):
1. 상단: 제목, 한 줄 설명(언어 런타임 순수 벤치마크가 아님), 언어 전환(ko/en/ja/zh, 기존 대시보드처럼 사전 객체), 모델 토글(Sonnet 5.5 / Opus 5.5 / 두 모델).
2. 비교표: 행 5개 언어 + 종합 점수 열, 열은 METRICS(과정/결과물 그룹 헤더). 칸 색은 열 내 정규화 값 3단계(상위/중간/하위)와 범례. 값 없으면 "—", 결과물 지표는 `n/3` 표기.
3. 가중치 패널: 지표별 0-3 슬라이더, 기본값 전부 1, "가중치가 바뀌면 순위가 바뀐다"는 안내.
4. 상세: 지표 선택 → run 단위 점 그래프(SVG), 데이터 없으면 안내 문구.
5. 하단: 측정 환경 한계 문구, 스택 표, 리포·자매 리포 링크.
6. 다크 모드 대응(`prefers-color-scheme`), 폭 360px에서 가로 스크롤 없이 표는 자체 가로 스크롤.

- [ ] **Step 1: 실패 테스트** `scripts/test_score.mjs`(node `assert`):
  - `normalize([1,2,3],"lower")` → `[1,0.5,0]`; `normalize([5,null],"higher")` → `[1,null]`; `normalize([2,2],"lower")` → `[1,1]`.
  - `composite`에 가중치 전부 0 → 모든 언어 `null`.
  - 한 언어가 모든 지표 `null` → 그 언어 `null`, 나머지는 숫자.
- [ ] **Step 2:** `node scripts/test_score.mjs` → FAIL.
- [ ] **Step 3:** `score.js` 구현 후 테스트 PASS.
- [ ] **Step 4:** `index.html` 구현. `python3 -m http.server 8792 -d DST/dashboard` 후 headless Chrome으로 1280px·375px 스크린샷을 scratchpad에 저장해 확인: 5개 언어 행, "아직 실험 없음" 안내, 콘솔 에러 0.
- [ ] **Step 5:** `./scripts/render_og.sh`로 `dashboard/og.png` 생성 확인(`.gitignore` 대상).
- [ ] **Step 6:** 커밋 `feat: language x metric dashboard (empty state)`.

### Task 4: GitHub 리포 생성과 Pages 배포

**Files:**
- Create: `DST/.github/workflows/pages.yml`(SRC 복사, paths에 `scripts/build_dashboard_data.py` 추가 없음, 그대로), `DST/.github/ISSUE_TEMPLATE/{benchmark-request.yml, result-dispute.yml, config.yml}`(리포명·대시보드 URL·"언어 추가 요청" 문구로 수정)

- [ ] **Step 1:** 이슈 템플릿과 워크플로를 추가하고 커밋 `ci: pages deploy and issue templates`.
- [ ] **Step 2:** `gh repo create roboco-io/language-benchmark --public --description "Which language suits vibe-coded backends? Claude Code (Sonnet 5.5 / Opus 5.5) implements the RealWorld backend in Rust, C++, Python, Go and TypeScript." --homepage https://roboco.io/language-benchmark/ --source DST --remote origin --push`.
- [ ] **Step 3:** `gh api -X POST repos/roboco-io/language-benchmark/pages -f build_type=workflow` (이미 있으면 `PUT`).
- [ ] **Step 4:** `gh workflow run pages.yml -R roboco-io/language-benchmark` 후 `gh run watch`로 성공 확인.
- [ ] **Step 5:** 검증: `curl -sI https://roboco.io/language-benchmark/ | head -1`이 200, `curl -s https://roboco.io/language-benchmark/data.js | head -c 40`이 `window.BENCH`로 시작. `gh api repos/roboco-io/language-benchmark/pages --jq .html_url`이 `http://roboco.io/language-benchmark/`.
- [ ] **Step 6:** 자매 리포 README에 링크를 추가할지는 사용자에게 묻는다(외부 리포 변경이므로 이 계획에서 자동 수행하지 않음).
