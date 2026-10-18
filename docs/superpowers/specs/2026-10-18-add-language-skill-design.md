# 새 언어 추가 스킬 설계

- 작성일: 2026-10-18
- 상태: 초안(리뷰어 검토 반영, 사용자 승인 전)
- 관련: [언어 벤치마크 설계](2026-10-17-language-benchmark-design.md), [실험 품질 규칙](../../experiment-quality-rules.md), [langbench 스킬](../../../.claude/skills/langbench/SKILL.md), [EXP-001 보고서](../../../experiments/001-five-language-realworld/report.md)

## 1. 의도

EXP-001은 RealWorld 백엔드를 TypeScript, Python, Go, Rust, C++ 다섯 언어로 구현해 비교했다. 사용자는 앞으로 Java, Kotlin, C#, Zig 같은 다른 언어도 같은 조건으로 측정해 기존 다섯 언어 옆에 붙여 보고 싶어 한다. 지금은 언어별 설정이 스킬 스크립트, 집계 스크립트, Fargate 측정 스크립트, 대시보드, 문서 등 약 15개 파일에 흩어져 있다. 그래서 언어 하나를 추가하려면 어디를 고쳐야 하는지 매번 찾아야 하고, 한 곳을 빠뜨리면 측정값이 빈 칸이 되거나 집계가 오류로 멈춘다.

이 작업의 목적은 두 가지다. 첫째, 언어별 설정을 한 곳(언어 레지스트리)에 모아 새 언어를 데이터 항목 추가만으로 등록할 수 있게 한다. 둘째, 새 언어 하나를 EXP-001과 같은 조건으로 실행하고 결과를 대시보드에 6번째 언어로 붙이는 절차를 스킬 `langbench-add-language`로 만든다.

## 2. 목표·비목표·실패 조건

**목표**

- 새 언어 등록에 필요한 언어별 정보를 `languages/<key>.json` 파일 하나에 적으면, 프롬프트 생성·툴체인 확인·런타임 측정·집계·대시보드가 그 파일을 읽어 동작한다.
- 스킬 하나로 "레지스트리 항목 작성 → Phase 0 → 6 run(2개 모델 × 3회) → 후속 과제 → Fargate 측정 → runs.csv → 보고서·README·대시보드 갱신"까지 진행한다.
- 기존 다섯 언어의 동작과 EXP-001 측정값은 리팩터링 전후로 바뀌지 않는다.

**비목표**

- 기존 다섯 언어를 다시 실행하지 않는다. 사용자는 실행 시점 차이를 "버전 기록만" 하는 방식으로 다루기로 결정했다(2026-10-18). 동시기 기준선 재측정은 이 설계 범위 밖이다.
- 과제(RealWorld)나 채점(Hurl 154요청), 프롬프트 템플릿 본문, 지표 정의는 바꾸지 않는다.
- 여러 언어를 한 번에 추가하는 일괄 실행은 만들지 않는다. 언어 하나씩 실험 하나로 진행한다.
- 이번 작업에서 실제 새 언어 실험(EXP-002)은 실행하지 않는다(사용자 결정, 2026-10-18). 이번 작업의 산출물은 레지스트리 리팩터링, Fargate 일반화, 대시보드 준비, 스킬이다. 검증은 EXP-001 회귀 테스트와 테스트 전용 가짜 언어 항목으로 한다. 첫 실제 실험은 나중에 이 스킬로 실행한다.

**실패 조건(기술적으로 동작해도 실패로 본다)**

- 리팩터링 후 EXP-001의 run 하나라도 `runtime_parse`·`runs_row` 결과가 이전과 달라진다.
- 새 언어 결과가 기존 결과와 같은 시점·같은 조건에서 측정된 것처럼 보인다. 대시보드와 보고서는 실행 시점과 Claude Code·모델 버전 차이를 반드시 드러내야 한다.
- 새 언어를 추가할 때 레지스트리 밖의 파일(스크립트 본문)을 고쳐야 하는데, 그 사실이 스킬 절차에 드러나지 않는다.

## 3. 현재 상태 (2026-10-18 코드 조사)

언어 목록과 언어별 동작이 정의된 위치는 다음과 같다. "데이터"는 목록이나 문자열만 바꾸면 되는 곳이고, "코드"는 언어마다 분기 로직이 있는 곳이다.

| 영역 | 위치 | 성격 |
|------|------|------|
| 프롬프트 슬롯 | `.claude/skills/langbench/assets/prompt/stacks.json`, `checksums.md5` | 데이터 |
| 언어 목록 | `scripts/metrics.py`(`LANGUAGES`, `LANGUAGE_NAMES`), `bench.env.example`(`LANGS`), `runtime.sh:29`, 테스트 2곳 | 데이터 |
| 툴체인 확인 | `scripts/toolchain.sh:26-50`(도구별 `check` 줄, Drogon 전용 확인) | 코드 |
| 취약점 스캐너 실행 | `runtime.sh:261-275`의 언어별 `case`, `fargate/local_prep.sh`에 같은 코드 복사본 | 코드(중복) |
| 코드량·힌트 | `runtime.sh:251-258`의 scc 제외 디렉터리·테스트 파일 정규식·빌드 모드/워커 grep 패턴, `runtime_parse.py:33-39`의 scc 언어 이름 | 데이터 |
| 의존성 수 | `runtime_parse.py:82-107` 언어별 매니페스트 파서 | 코드 |
| 취약점 ID 추출 | `runtime_parse.py:143-177` 형식별 추출기, `runs_row.py:25` `SCANNERS` | 코드 |
| 정리 | `cleanup.sh:17,31` 빌드 디렉터리 | 데이터 |
| 문서·UI 문구 | `SCHEMA.md`, 과제 README 스택 표, `dashboard/index.html` `STACKS`·"다섯 언어" 문구, `decision-ui.js` "다섯 언어" 문구 | 데이터 |
| 실험 고정값 | `experiments/001-*/fargate/orchestrate.py`의 `langbench-exp001` 버킷·태그, `dashboard/decision.js:22` 폴더 매핑 | 실험별 |

실험 실행 상태는 대부분 실험 번호별로 분리돼 있다(`setup.sh`의 `BASE=$HOME/experiments/langbench-exp$EXP`). 예외는 Fargate 측정 스크립트로, EXP-001 폴더 안에 있고 EXP-001 버킷 이름이 고정돼 있다.

## 4. 설계

### 4.1 언어 레지스트리

언어마다 `.claude/skills/langbench/languages/<key>.json` 파일 하나를 둔다. 기존 다섯 언어도 같은 형식으로 옮긴다. 파일에 들어가는 항목은 다음과 같다.

| 항목 | 내용 | 현재 위치 |
|------|------|------|
| `key`, `name` | 식별자(`java`)와 표시 이름(`Java`) | `metrics.py` |
| `order` | 대시보드·run 순서에 쓰는 정렬 값 | `metrics.py` 목록 순서 |
| `prompt.STACK`, `prompt.WORKERS` | 프롬프트 템플릿 슬롯 문장 | `stacks.json` |
| `stack` | 웹 프레임워크·DB 접근 표시값(대시보드·과제 README 표) | `index.html` `STACKS` |
| `toolchain` | 버전 확인 명령 목록(이름·명령·필수 여부) | `toolchain.sh` |
| `scanner` | 명령, 정상 종료 코드, 리포트 파일 이름, 리포트 필수 JSON 키, 리포트 형식(`npm`·`pip`·`go`·`cargo`·`none` 등) | `runtime.sh` `case`, `runs_row.py` |
| `deps` | 의존성 계수 규칙: 매니페스트 파일과 형식 종류(`package-json`·`pyproject-or-requirements`·`go-mod`·`cargo-toml`·`cmake`) | `runtime_parse.deps` |
| `loc` | scc 언어 이름 목록, 테스트 파일 정규식, 제외 디렉터리 | `runtime.sh`, `runtime_parse._PRIMARY` |
| `hints` | 빌드 모드·워커 설정을 찾는 grep 패턴 | `runtime.sh` |
| `cleanup` | 지울 빌드·의존성 디렉터리 | `cleanup.sh` |

스크립트는 이 파일을 읽는 공용 로더(Python 모듈 `langreg.py` 하나, 셸에서는 `langreg.py get <key> <field>` 호출)를 쓴다. 의존성 계수와 취약점 ID 추출은 "형식 종류"별 함수로 바꾼다. 새 언어가 기존 형식 중 하나를 쓰면 데이터만 추가하면 된다. 새 형식(예: Maven `pom.xml`, Gradle, NuGet)이 필요하면 `runtime_parse.py`에 형식 함수 하나와 테스트 픽스처를 추가한다. 스킬 절차는 이 경우를 명시적으로 안내한다.

`runtime.sh`와 `fargate/local_prep.sh`에 중복된 스캐너·코드량 측정 코드는 공용 셸 함수 파일 하나로 합치고, 두 스크립트가 그 파일을 불러 쓴다.

### 4.2 실험 단위와 실행 시점 기록

새 언어는 새 실험 번호(EXP-NNN, 예: `002-add-java`)로 실행한다. 실험마다 `experiments/NNN-*/experiment.json`에 실험 번호, 폴더 이름, 실행일, Claude Code 버전, 모델 ID를 적는다. `runs.csv` 열과 지표 정의는 바꾸지 않고, `build_dashboard_data.py`가 이 파일을 읽어 언어별 실행 정보를 만든다(빌더 질문 반영, 내 판단). EXP-001용 파일도 보고서 헤더와 설계 README 값으로 만든다. 구성은 새 언어 × Sonnet 5.5·Opus 5.5 × 3회 = 6 run이고, 후속 과제 6 run과 런타임 측정 6건이 따른다. 프롬프트 템플릿, 채점 정본, 시간·iteration 상한, 비용 상한 방식, Fargate 측정 사양(앱 2 vCPU·1 GiB, 부하 4 vCPU, Graviton4 확인)은 EXP-001과 같게 고정한다. 템플릿과 채점 정본은 EXP-001의 해시와 같은지 Phase 0에서 확인한다.

실험 품질 규칙 2절은 과거 결과와 비교할 때 시점·설정·CLI 차이를 명시하고, 강한 주장이 필요하면 동시기 기준선을 다시 측정하라고 정한다. 사용자는 기준선 재측정 대신 버전 기록을 택했다. 그래서 새 실험의 설계 README와 보고서에는 EXP-001 대비 Claude Code 버전, 모델 ID, 툴체인 버전, 실행일 차이를 표로 적는다. 보고서의 판정은 "EXP-001과 다른 시점의 탐색적 비교"로 범위를 한정한다.

### 4.3 대시보드 통합

대시보드는 지금도 `runs.csv`의 모든 행을 언어×모델로 집계한다. 새 언어 행이 추가되면 6번째 언어로 표시된다. 바꿀 점은 다음과 같다.

- 언어 목록·표시 이름·스택 표를 레지스트리에서 `data.js`로 내보내고, `index.html`의 `STACKS` 하드코딩과 "다섯 언어" 문구를 언어 수에 따라 바뀌는 문구로 바꾼다.
- 언어마다 그 값이 나온 실험 번호와 실행일을 `data.js`에 넣고, 다른 시점에 실행된 언어에는 표와 증거 패널에 표시(예: "EXP-002, 2026-11-xx 실행")를 붙인다.
- 한 언어×모델 셀에 두 실험의 행이 섞이면 `build_dashboard_data.py`가 오류로 멈춘다. 같은 언어를 다시 실행하는 경우는 이 설계 범위 밖이다.
- `decision.js`의 실험→폴더 매핑을 하드코딩하지 않고 `data.js`에 실험별 폴더를 넣는다.
- 기본 화면은 6개 언어를 한 순위와 표에 함께 표시하고, 다른 시점에 실행한 언어에는 배지(예: "EXP-002 · 날짜")와 안내문을 붙인다(사용자 결정, 2026-10-18). 실험별 필터는 만들지 않는다.
- 종합 점수는 비교 대상 언어 안에서 정규화하므로, 6번째 언어가 들어오면 기존 다섯 언어의 표시 점수와 순위가 바뀔 수 있다. EXP-001 판정은 EXP-001 보고서가 기준이라는 기존 안내를 유지하고, 새 언어가 포함된 순위는 실행 시점이 섞인 탐색적 순위라는 안내를 추가한다.

### 4.4 Fargate 측정 일반화

`experiments/001-*/fargate/`의 측정 스크립트를 스킬 아래(`.claude/skills/langbench/fargate/`)로 옮기고, 실험 번호를 인자로 받아 S3 접두어·태그를 정한다. AWS 계정·서브넷·보안 그룹·버킷은 설정 파일(`bench.env` 또는 별도 `fargate.env`)에서 읽는다. 새 AWS 자원은 만들지 않는다. 2026-10-18에 다음 실험용으로 남겨 둔 자원(us-east-1의 ECR `langbench`, S3 `langbench-exp001-<계정>`, ECS 클러스터 `langbench`, IAM 역할 `langbench-exec`·`langbench-task`, 보안 그룹, 로그 그룹 `/langbench`)을 재사용하고, 실험은 S3 접두어 `exp<NNN>/`와 태스크 태그 `experiment=EXP-NNN`으로 구분한다(내 판단). 버킷 이름에 exp001이 남는 점은 설정값으로만 다루고 이름을 바꾸지 않는다. EXP-001 폴더의 스크립트는 기록 보존을 위해 그대로 두고, 새 실험부터 일반화된 스크립트를 쓴다. 스킬은 사용 전에 `tools` 이미지를 다시 빌드해 ECR에 올리는 단계를 포함한다(2026-10-18에 ECR 이미지를 삭제했기 때문이다).

### 4.5 스킬 `langbench-add-language`

새 스킬은 기존 langbench 스킬의 스크립트를 그대로 쓰고, 언어 추가에 필요한 단계만 순서대로 안내한다.

1. **언어 조사:** 사용자가 정한 언어의 대표 웹 프레임워크·SQLite 접근 라이브러리·취약점 스캐너·의존성 매니페스트를 조사해 스택 후보를 제시하고 `AskUserQuestion`으로 확정한다.
2. **레지스트리 항목 작성:** `languages/<key>.json`을 쓰고 스키마 검사(`langreg.py validate`)를 통과시킨다. 새 매니페스트·리포트 형식이 필요하면 `runtime_parse.py`에 형식 함수와 테스트를 추가한다.
3. **프롬프트:** `render_prompts.py`로 `PROMPT-<key>.md`를 만들고 체크섬을 등록한다. 템플릿 해시가 EXP-001과 같은지 확인한다.
4. **실험 설계:** `templates/experiment-readme.md`로 설계 README를 쓰고 EXP-001 대비 버전 차이 표를 넣는다. 가설은 `hypotheses/catalog.md`에 등록한다.
5. **툴체인과 Phase 0:** 실험자가 툴체인을 설치하고(에이전트가 run 안에서 설치하지 않음), `toolchain.sh`·`smoke.sh`·hello-world 빌드로 확인한다.
6. **승인:** 사양·예상 사용량·비용 상한을 보고하고 `AskUserQuestion`으로 실행 승인을 받는다(CLAUDE.md 실행 전 승인 규칙).
7. **실행:** 기존 `run-all.sh`(`LANGS=<key>`)로 6 run, `run_followups.sh`로 후속 6 run을 돌린다.
8. **측정:** 일반화된 Fargate 스크립트로 6 run을 병렬 측정한다.
9. **집계·보고:** `runs_row.py`로 `runs.csv`에 행을 추가하고 보고서를 쓴 뒤 CLAUDE.md의 실험 종료 절차(README 4종, 카탈로그, ROADMAP, 대시보드, Pages 배포 확인)를 수행한다.

## 5. 제약

- 기존 langbench 스킬과 EXP-001 기록(`experiments/001-*`)의 동작·결과를 바꾸지 않는다. 리팩터링 검증은 EXP-001 원자료로 `runtime_parse`·`runs_row`를 다시 돌려 기존 `runs.csv` 행과 같은지 비교하는 회귀 테스트로 한다.
- 툴체인은 실험자가 미리 설치한다. 스킬은 설치 명령을 제안할 수 있지만 run 안에서 설치하지 않는다.
- 실행 전 승인, 구독 한도 보류·재실행, API 키 커밋 금지, Orange Hurl 절대 경로, 포트 고정 금지 규칙은 CLAUDE.md를 그대로 따른다.
- 4개 README의 `RESULTS` 블록은 스크립트로만 갱신한다.

## 6. 빌더에게 맡기는 부분

- `langreg.py`의 내부 구조와 JSON 스키마 표기 방식.
- 셸 공용 함수 파일의 이름과 위치.
- 대시보드에서 "다른 시점 실행" 표시의 구체적인 시각 형태(배지, 각주 등). 단, 표와 증거 패널 모두에서 보여야 한다.
- 리팩터링 커밋을 나누는 단위.
