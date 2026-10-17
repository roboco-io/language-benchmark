# CLAUDE.md

이 리포는 코딩 에이전트(Claude Code + Sonnet 5.5 / Opus 5.5)가 같은 RealWorld 백엔드를 TypeScript, Python, Go, Rust, C++로 구현한 결과를 비교해 "바이브 코딩 백엔드에 어떤 언어가 적합한가"를 판단하는 실험 관리 리포다. 운영 방식은 자매 리포 [roboco-io/coding-agent-benchmark](https://github.com/roboco-io/coding-agent-benchmark)를 따른다. 설계 근거는 [설계 문서](docs/superpowers/specs/2026-10-17-language-benchmark-design.md), 가설 상태는 [hypotheses/catalog.md](hypotheses/catalog.md) 참조.

## 실험 설계·계측·판정 시 필수 규칙

실험 설계·실행·집계·분석·보고 또는 자매 리포 결과를 기준선으로 인용할 때 [실험 품질 규칙](docs/experiment-quality-rules.md)을 먼저 읽고 적용한다. Codex의 `AGENTS.md`도 같은 정본을 참조한다.

- 새 실험은 `templates/experiment-readme.md`와 `templates/report.md`를 사용한다.
- 과제 정의, 언어별 스택, 기동 규약(`start.sh` + `Dockerfile`), 워커·SQLite 규칙은 [과제 README](tasks/realworld-backend/README.md)가 정본이다. 언어 간 조건을 다르게 두지 않는다.
- 지표 정의와 `analysis/metrics/runs.csv` 열은 [지표 스키마](analysis/metrics/SCHEMA.md)가 정본이다. 미측정 값은 빈 칸으로 두고 0으로 채우지 않는다.
- 가설 코드의 `L` 축은 이 리포에서 **구현 언어**를 뜻한다(자매 리포의 L 축은 자연어 EN/KO였다).
- 실험 실행 절차(하네스 생성부터 `runs.csv` 행 생성까지)는 스킬 [langbench](.claude/skills/langbench/SKILL.md)를 따른다.
- 이 규칙은 실험 관리용이다. 실험 대상 에이전트에 노출할 지침·스킬·MCP는 설계에서 고정하고 실제 노출 상태를 기록한다.

## 실험 종료 시 필수 절차

1. report.md 헤더에 파싱 가능한 세 줄을 유지한다: `- 가설: [코드](...) — <문장>`, `- 클라이언트: <에이전트 클라이언트·버전>`, `- **판정: <판정>** — <핵심 요약 한 문장>`. 기록이 없으면 `미기록`.
2. `scripts/readme_i18n.json`에 영·일·중 번역(name·hypothesis·verdict·summary)을 추가하고 `python3 scripts/update_readme_results.py`를 실행해 4개 README의 결과 블록을 재생성한다.
3. run별 측정값을 `analysis/metrics/runs.csv`에 추가하고 `python3 scripts/build_dashboard_data.py`로 `dashboard/data.js`를 재생성한다(pre-commit 훅도 수행).
4. `hypotheses/catalog.md` 상태와 `ROADMAP.md` 체크박스를 갱신한다. README의 `### 종합 인사이트` 절은 손으로 4개 언어 모두 갱신한다.
5. main에 push하면 GitHub Actions(`.github/workflows/pages.yml`)가 `dashboard/`를 https://roboco.io/language-benchmark/ 로 배포한다. 워크플로 성공과 Pages 반영을 확인한다.

## 환경 주의

- 새로 클론한 환경에서는 `git config core.hooksPath hooks` 1회 실행(pre-commit 훅 활성화).
- **앱 코드 보관**: run별 앱 소스는 `experiments/NNN-slug/runs/apps/<run>/`에 커밋한다. 빌드 산출물·의존성 디렉터리·SQLite DB는 `.gitignore`로 제외한다.
- **API 키 커밋 금지**: pre-commit 훅이 `scripts/check_staged_keys.py`로 스테이징 내용을 검사한다. `--no-verify`로 우회하지 않고, 걸리면 `scripts/redact_keys.py`로 마스킹한다.
- **`hurl`은 Orange Hurl(https://hurl.dev)만 뜻한다.** PATH의 `hurl`이 npm `@hurl/cli` 등 다른 도구일 수 있으므로 채점은 Phase 0에서 확정한 절대 경로로만 실행하고 `hurl --version` 출력(`hurl 8.x (... libcurl ...)` 형식)을 기록한다.
- **포트 고정 금지**: 하네스가 빈 포트를 찾아 `PORT`로 주입하고, 채점은 실험 대상 프로세스가 실제 LISTEN한 포트로만 한다.
- **실행 전 승인**: 30 run 등 실험 실행 전에 사양·예상 사용량을 보고하고 `AskUserQuestion`으로 승인받는다. 구독 한도로 중단된 run은 서비스 장애 보류로 처리하고 재실행한다.
- **툴체인**: 언어 툴체인(Node, Python, Go, Rust, CMake·Drogon)은 실험자가 미리 설치하고 버전을 실험 README에 기록한다. 에이전트가 run 안에서 툴체인을 설치하지 않게 한다.
- 4개 README(README.ko.md 원문·README.md 영어·README.ja.md·README.zh-CN.md)의 `<!-- RESULTS:BEGIN/END -->` 사이는 직접 수정 금지.
