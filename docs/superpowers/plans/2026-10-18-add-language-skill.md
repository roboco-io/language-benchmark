# 언어 레지스트리와 언어 추가 스킬 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 언어별 설정을 `languages/<key>.json` 레지스트리로 모으고, 새 언어 하나를 EXP-001과 같은 조건으로 실행해 대시보드에 붙이는 스킬 `langbench-add-language`를 만든다.

**Architecture:** 레지스트리 로더 `langreg.py`(Python, CLI 겸용)를 langbench 스킬 `scripts/`에 두고, 셸 스크립트·집계 스크립트·대시보드 데이터 생성기가 모두 이 로더를 거친다. 언어별 분기 코드는 "형식 종류"(deps kind, scanner format) 함수 표로 바꾼다. Fargate 측정 스크립트는 스킬 아래로 옮기고 실험 번호와 AWS 설정을 인자·설정 파일로 받는다.

**Tech Stack:** Python 3.14 표준 라이브러리(unittest, json, tomllib, shlex), bash, Node(대시보드 테스트 `.mjs`), boto3(Fargate).

**Spec:** `docs/superpowers/specs/2026-10-18-add-language-skill-design.md`

## Global Constraints

- EXP-001의 동작·측정값을 바꾸지 않는다. 기존 테스트(`scripts/test_*.py`, `scripts/test_*.mjs`, `.claude/skills/langbench/tests/test_*.py`)는 수정 없이 통과해야 한다. 예외는 언어 목록을 하드코딩한 테스트 줄(`tests/test_prompts.py:10,65`, `tests/test_orchestration.py:152`)뿐이며, 레지스트리에서 읽도록만 바꾼다.
- `experiments/001-five-language-realworld/` 아래 파일(Fargate 원본 스크립트 포함)은 수정하지 않는다. 새로 추가하는 `experiment.json`만 예외다.
- `analysis/metrics/runs.csv` 열과 `SCHEMA.md`의 지표 정의는 바꾸지 않는다.
- 렌더링한 `PROMPT-<lang>.md` 5개의 md5는 `assets/checksums.md5`의 현재 값과 같아야 한다.
- 새 AWS 자원을 만들지 않는다. 기존 자원(ECR `langbench`, S3 `langbench-exp001-779411790546`, ECS 클러스터 `langbench`, IAM `langbench-exec`·`langbench-task`, SG `sg-09b24a70b3cba5f6b`, 로그 그룹 `/langbench`)을 설정값으로 재사용한다. 실험 구분은 S3 접두어 `exp<NNN>/`와 태스크 태그 `experiment=EXP-NNN`이다.
- 4개 README의 `<!-- RESULTS:BEGIN/END -->` 사이는 직접 수정하지 않는다. Markdown 본문에 `~` 금지.
- 테스트 실행: Python은 `python3 -m unittest`(pytest 미설치), 대시보드는 `node scripts/test_*.mjs`.

## Review Focus

1. 레지스트리에 없는 언어 키가 run 이름이나 `runs.csv`에 나타나면 조용히 건너뛰지 않고 언어 키를 담은 오류로 멈춘다. → Task 1 `test_unknown_key_raises`, Task 4 `test_unknown_language_row_raises`
2. 새 언어 JSON에 필수 필드가 빠지거나 `deps.kind`·`scanner.format`이 알 수 없는 값이면 실행 전 `validate`에서 걸린다(측정 후 빈 칸으로 발견되지 않는다). → Task 1 `test_validate_rejects_unknown_kind`
3. 언어 키가 다른 언어 키의 부분 문자열이면(예: `c`와 `cpp`, `java`와 `javascript`) run 이름에서 언어를 잘못 고른다. → Task 1 `test_detect_exact_segment`
4. 한 언어×모델 셀에 두 실험의 행이 섞이면 대시보드 집계가 멈춘다. → Task 4 `test_mixed_experiments_raise`
5. 스캐너 인자에 공백이 들어간 경로(예: `$HOME`에 공백)가 있어도 셸 측 명령이 깨지지 않는다. → Task 2 `test_shell_quotes_paths`

---

## File Structure

| 파일 | 책임 | 작업 |
|------|------|------|
| `.claude/skills/langbench/languages/{typescript,python,go,rust,cpp}.json` | 언어별 설정 정본 | 생성(Task 1) |
| `.claude/skills/langbench/scripts/langreg.py` | 레지스트리 로드·검증·CLI | 생성(Task 1) |
| `.claude/skills/langbench/tests/test_langreg.py` | 로더·CLI 테스트 | 생성(Task 1) |
| `.claude/skills/langbench/scripts/render_prompts.py` | stacks.json 대신 레지스트리 사용 | 수정(Task 1) |
| `.claude/skills/langbench/assets/prompt/stacks.json` | 레지스트리로 대체 | 삭제(Task 1) |
| `.claude/skills/langbench/scripts/scan_lib.sh` | scc·hints·언어 스캐너·trivy 공용 함수 | 생성(Task 2) |
| `.claude/skills/langbench/scripts/{runtime.sh,toolchain.sh,cleanup.sh,setup.sh}` | 레지스트리 사용 | 수정(Task 2) |
| `scripts/{metrics.py,runtime_parse.py,runs_row.py}` | 언어 목록·deps·vulns·loc를 레지스트리로 | 수정(Task 3) |
| `experiments/001-five-language-realworld/experiment.json` | 실험 메타데이터 | 생성(Task 4) |
| `scripts/build_dashboard_data.py` | stacks·experiments·언어별 exp 내보내기 | 수정(Task 4) |
| `dashboard/{index.html,decision.js,decision-ui.js}` | STACKS·폴더 매핑을 데이터로, 언어 수 문구, 실험 배지 | 수정(Task 5) |
| `.claude/skills/langbench/fargate/{orchestrate.py,local_prep.sh,runner.sh,probe.py,Dockerfile.tools,fargate.env.example}` | 일반화된 Fargate 측정 | 생성(Task 6, EXP-001 원본에서 복사 후 수정) |
| `.claude/skills/langbench-add-language/SKILL.md` | 언어 추가 절차 | 생성(Task 7) |
| `.claude/skills/langbench/SKILL.md`, `analysis/metrics/SCHEMA.md`, `tasks/realworld-backend/README.md`, `CLAUDE.md` | 레지스트리·새 스킬 안내 | 수정(Task 7) |

---

### Task 1: 언어 레지스트리와 로더

**Files:**
- Create: `.claude/skills/langbench/languages/{typescript,python,go,rust,cpp}.json`
- Create: `.claude/skills/langbench/scripts/langreg.py`
- Create: `.claude/skills/langbench/tests/test_langreg.py`
- Modify: `.claude/skills/langbench/scripts/render_prompts.py:2,14-20`
- Modify: `.claude/skills/langbench/tests/test_prompts.py:10,65`, `tests/test_orchestration.py:152`
- Delete: `.claude/skills/langbench/assets/prompt/stacks.json`

**Interfaces:**
- Produces:
  - JSON 스키마(필수 필드): `key: str`, `name: str`, `order: int`, `prompt: {STACK: str, WORKERS: str}`, `stack: {framework: str, db: str}`, `toolchain: [{name: str, bin?: str, bin_env?: str, args: [str], ver_lines?: int, probe?: "brew-or-pkgconfig"}]`, `scanner: {format: "npm"|"pip"|"go"|"cargo"|"none", name?: str, report?: str, required_key?: str, ok_rc?: str, argv?: [str], alt?: [{if_file: str, argv: [str]}]}`, `deps: {kind: "package-json"|"pyproject-or-requirements"|"go-mod"|"cargo-toml"|"cmake"}`, `loc: {scc_names: [str], test_pattern: str, exclude_dirs: [str]}`, `hints: [str]`, `cleanup: [str]`.
  - `langreg.lang_dir() -> Path`: `LB_LANG_DIR` 환경변수 → `<langreg.py 디렉터리>/languages` → `<langreg.py 디렉터리>/../languages` 순서로 처음 존재하는 곳.
  - `langreg.load_all() -> list[dict]`: `order` 오름차순.
  - `langreg.keys() -> list[str]`, `langreg.get(key) -> dict`(없으면 `KeyError("알 수 없는 언어: <key>")`).
  - `langreg.validate(entry: dict) -> list[str]`: 오류 메시지 목록(빈 목록이면 통과).
  - `langreg.detect(run: str) -> str`: run 이름을 `-`로 나눈 조각 중 등록된 키와 정확히 일치하는 것. 없거나 둘 이상이면 `ValueError`.
  - `DEPS_KINDS = {"package-json","pyproject-or-requirements","go-mod","cargo-toml","cmake"}`, `SCANNER_FORMATS = {"npm","pip","go","cargo","none"}`.
  - CLI: `langreg.py keys` (공백 구분 한 줄), `langreg.py validate [key...]` (오류 시 종료 코드 1), `langreg.py detect <run>`, `langreg.py get <key> <dotted.path>` (문자열은 그대로, 그 밖은 JSON).

- [ ] **Step 1: 기존 다섯 언어 JSON 작성**

값은 현재 코드에서 그대로 옮긴다. `prompt`는 `assets/prompt/stacks.json`, `stack`은 `dashboard/index.html:282`의 `STACKS`, `toolchain`은 `scripts/toolchain.sh:26-50`(node·npm은 typescript, python3·pip-audit은 python, go·govulncheck(`ver_lines: 2`, `bin_env: GOVULNCHECK_BIN`)은 go, cargo·rustc·cargo-audit(`bin_env: CARGO_AUDIT_BIN`)은 rust, cmake·drogon(`probe: brew-or-pkgconfig`)은 cpp), `scanner`는 `runtime.sh:261-275`(go는 `ok_rc: "0 3"`, `required_key: "stream:config"`; python은 `alt: [{if_file: "requirements.txt", argv: ["${PIP_AUDIT_BIN}","-f","json","-r","requirements.txt"]}]`, 기본 `argv: ["${PIP_AUDIT_BIN}","-f","json","."]`; cpp는 `format: "none"`), `loc.scc_names`는 `runtime_parse._PRIMARY`, `loc.test_pattern`은 현재 `--not-match` 정규식의 언어별 조각(`_test\.go`, `test_.*\.py|.*_test\.py`, `\.(test|spec)\.[jt]s`, `_test\.(cc|cpp)`, rust는 빈 문자열), `loc.exclude_dirs`는 typescript `node_modules`, python `.venv`, go `vendor`, rust `target`, cpp `build`, `cleanup`은 rust `target`, python `.venv`, cpp `build`. `order`는 typescript 1 … cpp 5.

- [ ] **Step 2: 실패하는 테스트 작성** (`tests/test_langreg.py`, unittest)

```python
def test_keys_order(self):
    self.assertEqual(langreg.keys(), ["typescript", "python", "go", "rust", "cpp"])
def test_all_entries_valid(self):
    for e in langreg.load_all(): self.assertEqual(langreg.validate(e), [], e["key"])
def test_unknown_key_raises(self):
    with self.assertRaisesRegex(KeyError, "zig"): langreg.get("zig")
def test_validate_rejects_unknown_kind(self):
    e = dict(langreg.get("go"), deps={"kind": "pom-xml"})
    self.assertTrue(any("deps.kind" in m for m in langreg.validate(e)))
def test_validate_requires_fields(self):
    self.assertTrue(langreg.validate({"key": "x"}))
def test_detect_exact_segment(self):
    # LB_LANG_DIR로 임시 디렉터리에 c.json·cpp.json을 두고 검사
    self.assertEqual(langreg.detect("sonnet55-cpp-3"), "cpp")
    self.assertEqual(langreg.detect("sonnet55-c-1"), "c")
def test_detect_unknown_raises(self):
    with self.assertRaises(ValueError): langreg.detect("sonnet55-zig-1")
def test_cli_keys_and_validate(self):
    # subprocess로 `langreg.py keys` 출력이 "typescript python go rust cpp", `validate` 종료 코드 0
def test_prompts_unchanged(self):
    # render_prompts.render() 결과의 md5가 assets/checksums.md5의 PROMPT-<lang>.md 값과 같다
```

- [ ] **Step 3: 실패 확인** — `cd .claude/skills/langbench && python3 -m unittest tests.test_langreg -v` → `ModuleNotFoundError: langreg`.

- [ ] **Step 4: `langreg.py` 구현, `render_prompts.py`를 `langreg.load_all()`의 `prompt` 값으로 바꾸고 `stacks.json` 삭제, 테스트 2곳의 언어 목록을 `langreg.keys()`로 교체**

- [ ] **Step 5: 통과 확인** — `cd .claude/skills/langbench && python3 -m unittest discover -s tests -v` 전부 PASS, `python3 scripts/render_prompts.py --check` 종료 코드 0.

- [ ] **Step 6: Commit** — `git commit -m "feat(langbench): add language registry and loader"`

### Task 2: 셸 스크립트를 레지스트리로

**Files:**
- Create: `.claude/skills/langbench/scripts/scan_lib.sh`
- Modify: `.claude/skills/langbench/scripts/runtime.sh:28-32,250-275`, `toolchain.sh:26-50`, `cleanup.sh:28-33`, `setup.sh:38-50`
- Modify: `.claude/skills/langbench/scripts/langreg.py`(CLI 추가)
- Test: `.claude/skills/langbench/tests/test_langreg.py`, 기존 `tests/test_runtime.py`·`tests/test_setup.py`

**Interfaces:**
- Consumes: Task 1의 `langreg.get`, `langreg.detect`, `langreg.load_all`.
- Produces:
  - `langreg.py shell <key> <src>`: `eval` 가능한 bash 대입문을 출력한다. 모든 값은 `shlex.quote`. 변수: `LR_SCAN_FORMAT`, `LR_SCAN_NAME`, `LR_SCAN_REPORT`, `LR_SCAN_KEY`, `LR_SCAN_OK_RC`, `LR_SCAN_ARGV`(bash 배열, `alt`의 `if_file`이 `<src>`에 있으면 그 argv, `${VAR}`는 환경변수로 치환), `LR_LOC_EXCLUDE`(쉼표 구분: `test,tests,__tests__,hurl` + 모든 언어 `exclude_dirs` 합집합), `LR_LOC_NOTMATCH`(모든 언어 `test_pattern`을 `|`로 묶은 `(...)$`), `LR_HINTS`(모든 언어 `hints` 합집합을 `|`로 묶은 문자열).
  - `langreg.py toolchain [key...]`: 줄마다 `name<TAB>probe<TAB>bin<TAB>bin_env<TAB>ver_lines<TAB>args...`.
  - `langreg.py cleanup-dirs`: 모든 언어 `cleanup` 합집합(공백 구분).
  - `scan_lib.sh`의 함수 `lb_static_scans SRC OUT LANG_KEY IMAGE`: runtime.sh 250-283행의 scc·hints·언어 스캐너·trivy 동작을 그대로 수행한다. `run_scan`·`scan_fail`은 호출 측(runtime.sh, local_prep.sh)이 정의한다.

- [ ] **Step 1: 실패하는 테스트 작성**

```python
def test_shell_go(self):
    out = run_cli("shell", "go", "/tmp/src")
    self.assertIn("LR_SCAN_OK_RC='0 3'", out); self.assertIn("LR_SCAN_KEY=stream:config", out)
def test_shell_python_alt(self):
    # requirements.txt가 있는 임시 src → LR_SCAN_ARGV에 -r requirements.txt
def test_shell_union_matches_legacy(self):
    # LR_LOC_EXCLUDE 집합 == {"test","tests","__tests__","hurl","node_modules","target","build",".venv","vendor"}
    # LR_HINTS 집합 == runtime.sh 기존 패턴 10개 집합
def test_shell_quotes_paths(self):
    # PIP_AUDIT_BIN="/tmp/a b/pip-audit" 환경에서 bash -c 'eval "$(...)"; printf %s "${LR_SCAN_ARGV[0]}"' 결과가 "/tmp/a b/pip-audit"
def test_toolchain_lines(self):
    # toolchain 출력에 go·govulncheck(ver_lines 2)·drogon(probe brew-or-pkgconfig) 줄이 있다
```

- [ ] **Step 2: 실패 확인** — `python3 -m unittest tests.test_langreg -v` → 새 테스트 FAIL.

- [ ] **Step 3: CLI 구현 후 `scan_lib.sh` 작성, runtime.sh의 언어 감지를 `langreg.py detect`, 스캔 구간을 `lb_static_scans` 호출로 교체. toolchain.sh는 공통 도구(docker·k6·scc·trivy·hurl)는 그대로 두고 언어 도구는 `langreg.py toolchain $LANGS`(LANGS 미설정이면 전체) 줄을 읽어 기존 `check`를 호출, `probe: brew-or-pkgconfig`는 기존 Drogon 블록을 이름 인자로 일반화. cleanup.sh의 `for d in target .venv build`를 `langreg.py cleanup-dirs`로. setup.sh는 `languages/`를 `$BASE/languages`로 복사하고 `langreg.py validate $LANGS`를 PROMPT 확인 앞에 실행.**

- [ ] **Step 4: 통과 확인** — `cd .claude/skills/langbench && python3 -m unittest discover -s tests -v` 전부 PASS(기존 `test_runtime.py`·`test_setup.py` 포함).

- [ ] **Step 5: Commit** — `git commit -m "refactor(langbench): drive scans, toolchain and cleanup from the registry"`

### Task 3: 집계 스크립트를 레지스트리로

**Files:**
- Modify: `scripts/metrics.py:3-4`, `scripts/runtime_parse.py:33-107,143-177`, `scripts/runs_row.py:25`
- Test: `scripts/test_registry_regression.py`(생성), 기존 `scripts/test_runtime_parse.py`, `scripts/test_runs_row.py`

**Interfaces:**
- Consumes: Task 1의 `langreg`. 이 디렉터리에서는 `sys.path`에 `<repo>/.claude/skills/langbench/scripts`를 추가해 import한다(`metrics.py` 상단 한 곳).
- Produces:
  - `metrics.LANGUAGES = langreg.keys()`, `metrics.LANGUAGE_NAMES = {e["key"]: e["name"]}`(이름과 타입 그대로).
  - `runtime_parse.DEPS_BY_KIND: dict[str, Callable[[Path], int | None]]`; `deps(repo, language)` 시그니처 유지, `langreg.get(language)["deps"]["kind"]`로 분기. 알 수 없는 언어는 기존처럼 `ValueError`.
  - `runtime_parse.loc(scc_json, language)` 시그니처 유지, 이름 집합은 `loc.scc_names`.
  - `runs_row.SCANNERS = {k: (None if fmt == "none" else fmt)}`.

- [ ] **Step 1: 실패하는 회귀 테스트 작성** (`scripts/test_registry_regression.py`)

```python
def test_deps_match_runs_csv(self):
    # analysis/metrics/runs.csv의 EXP-001 각 행에 대해
    # deps(Path("experiments/001-five-language-realworld/runs/apps")/run, language) == int(row["deps"])
def test_scanners_unchanged(self):
    self.assertEqual(runs_row.SCANNERS, {"typescript": "npm", "python": "pip", "go": "go", "rust": "cargo", "cpp": None})
def test_languages_unchanged(self):
    self.assertEqual(metrics.LANGUAGES, ["typescript", "python", "go", "rust", "cpp"])
def test_fake_language_flows(self):
    # LB_LANG_DIR 임시 디렉터리에 기존 5개 + zig.json(deps kind go-mod, scanner none)
    # → importlib.reload 후 runs_row.parse_run("sonnet55-zig-1") == ("sonnet55", "zig", "1")
```

- [ ] **Step 2: 실패 확인** — `cd scripts && python3 -m unittest test_registry_regression -v` → `test_fake_language_flows` FAIL(현재 하드코딩).

- [ ] **Step 3: 구현** — 기존 언어별 함수 본문은 그대로 두고 kind 이름으로 표에 연결한다.

- [ ] **Step 4: 통과 확인** — `cd scripts && python3 -m unittest discover -p 'test_*.py' -v` 전부 PASS.

- [ ] **Step 5: Commit** — `git commit -m "refactor(metrics): read languages, deps and scanners from the registry"`

### Task 4: 실험 메타데이터와 대시보드 데이터

**Files:**
- Create: `experiments/001-five-language-realworld/experiment.json`
- Modify: `scripts/build_dashboard_data.py:49-70`
- Test: `scripts/test_build_dashboard_data.py`(테스트 추가)

**Interfaces:**
- Consumes: Task 3의 `metrics.LANGUAGES`, `langreg.get(k)["stack"]`.
- Produces:
  - `experiment.json`: `{"exp": "EXP-001", "folder": "001-five-language-realworld", "date": "2026-10-18", "client": "Claude Code 2.1.302", "models": {"sonnet55": "claude-sonnet-5-5", "opus55": "claude-opus-5-5"}}`(client는 보고서 헤더, 모델 ID는 `runs/orchestrator.log` 첫 줄에서 확인한 값).
  - `build(rows, experiments=None) -> dict`에 키 추가: `"experiments": {exp: {...experiment.json}}`, `"languages"` 각 항목에 `"stack": {"framework","db"}`와 `"exp": str | None`(그 언어 행의 실험, 행이 없으면 None).
  - `read_experiments(root: Path) -> dict[str, dict]`: `experiments/*/experiment.json`을 읽는다.
  - 한 언어에 두 실험의 행이 있으면 `ValueError("<lang>: 실험이 섞임 EXP-001, EXP-002")`. 등록되지 않은 언어 행은 기존처럼 오류.

- [ ] **Step 1: 실패하는 테스트 작성**

```python
def test_languages_carry_stack_and_exp(self):
    data = build([row("go", "sonnet55", 1)], {"EXP-001": {"exp": "EXP-001"}})
    go = next(l for l in data["languages"] if l["key"] == "go")
    self.assertEqual(go["exp"], "EXP-001"); self.assertEqual(go["stack"]["framework"], "Gin")
def test_mixed_experiments_raise(self):
    r2 = dict(row("go", "opus55", 1), exp="EXP-002")
    with self.assertRaisesRegex(ValueError, "go"): build([row("go", "sonnet55", 1), r2])
def test_unknown_language_row_raises(self):
    with self.assertRaises(ValueError): build([row("zig", "sonnet55", 1)])
def test_experiments_exported(self):
    self.assertIn("EXP-001", build([], read_experiments(ROOT))["experiments"])
```

- [ ] **Step 2: 실패 확인** — `cd scripts && python3 -m unittest test_build_dashboard_data -v` → 새 테스트 FAIL.

- [ ] **Step 3: 구현 후 `python3 scripts/build_dashboard_data.py`로 `dashboard/data.js` 재생성**

- [ ] **Step 4: 통과 확인** — 위 명령 PASS, `node scripts/test_decision.mjs`·`node scripts/test_score.mjs` PASS(셀 값 불변).

- [ ] **Step 5: Commit** — `git commit -m "feat(dashboard-data): export stacks, experiments and per-language experiment"`

### Task 5: 대시보드를 데이터 기반으로

**Files:**
- Modify: `dashboard/index.html`(`STACKS` 상수 제거, 메타·리드 문구), `dashboard/decision.js:21-32`, `dashboard/decision-ui.js`(언어 수 문구, 배지), `dashboard/decision.css`
- Test: `scripts/test_decision.mjs`

**Interfaces:**
- Consumes: Task 4의 `BENCH.languages[i].stack`, `.exp`, `BENCH.experiments`.
- Produces:
  - `Decision.evidenceLinks(run, experiments)`: 폴더를 `experiments[run.exp].folder`에서 읽는다. 없으면 `null`(기존 동작).
  - `Decision.baselineExp(languages, experiments) -> string | null`: 가장 이른 `date`의 실험.
  - 배지 규칙: 언어의 `exp`가 `baselineExp`와 다르면 표의 언어 이름 옆과 증거 패널에 `"<exp> · <date>"` 배지, 개요 표 아래에 4개 언어 안내문 "다른 시점에 실행한 언어가 포함된 순위는 탐색적이다. Claude Code·모델 버전은 실험 README를 참조한다."를 표시한다.
  - "다섯 언어/five languages/5言語/五种语言" 문구는 `BENCH.languages.length`로 만든 문구로 바꾼다(`index.html` 메타 설명·리드, `decision-ui.js` reasonNote).

- [ ] **Step 1: 실패하는 테스트 작성** (`scripts/test_decision.mjs`에 추가)

```js
const exps={'EXP-001':{folder:'001-five-language-realworld',date:'2026-10-18'},'EXP-002':{folder:'002-add-zig',date:'2026-11-01'}};
assert.ok(evidenceLinks({...rows[0]}, exps).source.includes('001-five-language-realworld'));
assert.ok(evidenceLinks({...rows[0], exp:'EXP-002'}, exps).source.includes('002-add-zig'));
assert.equal(evidenceLinks({...rows[0], exp:'EXP-999'}, exps), null);
assert.equal(baselineExp([{exp:'EXP-002'},{exp:'EXP-001'}], exps), 'EXP-001');
```

- [ ] **Step 2: 실패 확인** — `node scripts/test_decision.mjs` → FAIL.

- [ ] **Step 3: 구현** — 기존 `test_decision.mjs`의 증거 경로 존재 검사(41-44행)는 `bench.experiments`를 넘기도록 호출만 바꾼다.

- [ ] **Step 4: 통과 확인** — `node scripts/test_decision.mjs`, `node scripts/test_score.mjs` PASS. 헤드리스 Chrome에서 `dashboard/index.html`을 열어 스택 표 5행, 배지 0개(EXP-001만 있음), 390px 폭 가로 넘침 없음 확인. 가짜 데이터 확인: `LB_LANG_DIR`에 zig.json을 두고 EXP-002 행 1개를 넣은 임시 CSV로 `build_dashboard_data.py`를 돌린 `data.js`를 임시 사본에 넣어 배지가 Zig에만 붙는지 확인(커밋하지 않음).

- [ ] **Step 5: Commit** — `git commit -m "feat(dashboard): data-driven stacks, language count and experiment badges"`

### Task 6: Fargate 측정 일반화

**Files:**
- Create: `.claude/skills/langbench/fargate/`에 EXP-001 원본(`experiments/001-five-language-realworld/fargate/`)의 `orchestrate.py`, `local_prep.sh`, `runner.sh`, `probe.py`, `Dockerfile.tools` 복사본과 `fargate.env.example`
- Modify: 복사본 `orchestrate.py:25-36,74,85,94`, `local_prep.sh`(스캔 구간)
- Test: `.claude/skills/langbench/tests/test_fargate_config.py`

**Interfaces:**
- Consumes: Task 2의 `scan_lib.sh`의 `lb_static_scans`, `langreg.py detect`.
- Produces:
  - `fargate.env` 키: `AWS_REGION`, `AWS_ACCOUNT`, `ECS_CLUSTER`, `ECR_REPO`, `S3_BUCKET`, `EXEC_ROLE`, `TASK_ROLE`, `SECURITY_GROUP`, `SUBNETS`(쉼표 구분). 예시 파일에는 현재 값을 그대로 넣는다.
  - `orchestrate.load_config(path: str) -> dict`, `orchestrate.s3_prefix(exp: str) -> str`(`"EXP-002"` → `"exp002/"`), `orchestrate.task_tags(exp: str) -> list[dict]`(`[{"key":"project","value":"langbench"},{"key":"experiment","value":"EXP-002"}]`).
  - CLI: `orchestrate.py <harness BASE> --exp EXP-NNN --config fargate.env [--runs ...] [--parallel 20] [--attempts 3]`. `--exp` 없으면 종료 코드 2.
  - `local_prep.sh`는 `scan_lib.sh`를 source하고 언어 감지를 `langreg.py detect`로 한다.

- [ ] **Step 1: 실패하는 테스트 작성**

```python
def test_s3_prefix(self): self.assertEqual(orchestrate.s3_prefix("EXP-002"), "exp002/")
def test_task_tags(self): self.assertIn({"key": "experiment", "value": "EXP-002"}, orchestrate.task_tags("EXP-002"))
def test_load_config_example(self):
    cfg = orchestrate.load_config(".../fargate/fargate.env.example")
    self.assertEqual(cfg["S3_BUCKET"], "langbench-exp001-779411790546"); self.assertEqual(len(cfg["SUBNETS"]), 3)
def test_exp_required(self):
    # subprocess `orchestrate.py /tmp/x` 종료 코드 2
def test_local_prep_has_no_language_case(self):
    # local_prep.sh 본문에 "typescript)" 같은 언어 case 분기가 없다
```

- [ ] **Step 2: 실패 확인** — `cd .claude/skills/langbench && python3 -m unittest tests.test_fargate_config -v` → FAIL.

- [ ] **Step 3: 구현** — boto3 import는 함수 안으로 옮겨 테스트가 boto3 없이도 import되게 한다.

- [ ] **Step 4: 통과 확인** — 같은 명령 PASS, `diff -r experiments/001-five-language-realworld/fargate <원본 커밋>`로 원본 불변 확인(`git diff --stat experiments/001-five-language-realworld` 출력 없음).

- [ ] **Step 5: Commit** — `git commit -m "feat(langbench): generalize Fargate runtime measurement per experiment"`

### Task 7: 스킬 `langbench-add-language`와 문서

**Files:**
- Create: `.claude/skills/langbench-add-language/SKILL.md`
- Modify: `.claude/skills/langbench/SKILL.md`(언어 목록·"블록당 10 run"·스캐너 경로 문구를 레지스트리 기준으로, Fargate 경로 안내), `analysis/metrics/SCHEMA.md`(언어 허용값·scc 이름·deps 규칙·스캐너를 "레지스트리 참조"로), `tasks/realworld-backend/README.md`(스택 표 아래에 "정본은 레지스트리" 한 줄), `CLAUDE.md`(스킬 목록에 새 스킬 한 줄)

**Interfaces:**
- Consumes: Task 1의 CLI(`validate`, `keys`), Task 6의 `orchestrate.py --exp --config`, 설계 문서 4.5절 9단계.

- [ ] **Step 1: SKILL.md 작성** — frontmatter `name: langbench-add-language`, `description`: "새 구현 언어(예: Java, Kotlin, C#, Zig)를 language-benchmark에 추가해 EXP-001과 같은 조건으로 6 run을 실행하고 대시보드에 붙일 때 사용". 본문은 설계 4.5절의 9단계를 순서대로 쓰고, 각 단계에 실행할 명령과 완료 기준을 적는다. 반드시 포함할 내용: 새 deps·리포트 형식이 필요하면 `runtime_parse.DEPS_BY_KIND`·`_EXTRACT`와 `langreg.DEPS_KINDS`·`SCANNER_FORMATS`에 추가하고 픽스처 테스트를 쓴다는 안내, 실행 전 `AskUserQuestion` 승인, `experiment.json` 작성, EXP-001 대비 버전 차이 표, 프롬프트 템플릿·채점 정본 해시가 EXP-001과 같은지 확인, Fargate 사용 전 `tools` 이미지 재빌드·push.

- [ ] **Step 2: 문서 4개 수정** (위 Files 목록 그대로)

- [ ] **Step 3: 가짜 언어 리허설로 절차 검증** — 임시 `LB_LANG_DIR`에 기존 5개 + `zig.json`을 두고 다음을 실행해 모두 성공하는지 확인한다(산출물은 커밋하지 않는다): `langreg.py validate zig`, `render_prompts.py`(임시 출력), `langreg.py shell zig /tmp/src`, `cd scripts && python3 -m unittest discover -p 'test_*.py'`, `node scripts/test_decision.mjs`. 실패하면 해당 Task로 돌아가 고친다.

- [ ] **Step 4: 전체 테스트** — `cd .claude/skills/langbench && python3 -m unittest discover -s tests`, `cd scripts && python3 -m unittest discover -p 'test_*.py'`, `node scripts/test_score.mjs`, `node scripts/test_decision.mjs` 모두 PASS. `grep -c '~'`로 수정한 Markdown에 물결표 없음 확인.

- [ ] **Step 5: Commit** — `git commit -m "feat(skills): add langbench-add-language skill and registry docs"`

### Task 8: Java·PHP·C# 레지스트리 항목 (EXP-002 준비)

사용자 결정(2026-10-18): Java·PHP·C#을 실험 하나(EXP-002)로 실행한다. 스택은 Java Spring Boot + Spring Data JPA(Maven), PHP Laravel + Eloquent(Octane, FrankenPHP 워커), C# ASP.NET Core + EF Core이며 모두 SQLite다. 이 작업은 실험을 실행하지 않고 등록만 한다.

**Files:**
- Create: `.claude/skills/langbench/languages/{java,php,csharp}.json`
- Modify: `.claude/skills/langbench/scripts/langreg.py`(`DEPS_KINDS`에 `maven-pom`·`composer-json`·`csproj`, `SCANNER_FORMATS`에 `osv`·`composer`·`dotnet`), `scripts/runtime_parse.py`(deps·vuln 추출 함수), `.claude/skills/langbench/assets/checksums.md5`, `PROMPT-{java,php,csharp}.md`(render_prompts로 생성)
- Test: `scripts/test_runtime_parse.py`(픽스처 추가), `.claude/skills/langbench/tests/test_langreg.py`

**Interfaces:**
- Consumes: Task 1-3의 레지스트리·`DEPS_BY_KIND`·`_EXTRACT`.
- Produces:
  - `order`: java 6, php 7, csharp 8. 표시 이름 `Java`, `PHP`, `C#`.
  - deps 규칙: `maven-pom` = `pom.xml`의 `<dependencies>` 바로 아래 `<dependency>` 중 `<scope>`가 `test`·`provided`가 아닌 것의 수(`<dependencyManagement>` 안은 제외). `composer-json` = `composer.json`의 `require` 키 중 `php`와 `ext-*`를 뺀 수. `csproj` = 저장소 안 모든 `*.csproj`의 `<PackageReference>` 중 `PrivateAssets="all"`이 아닌 것의 Include 이름 고유 수(테스트 프로젝트 `*Test*.csproj` 제외).
  - 스캐너: java `{format: "osv", name: "osv", report: "osv.json", required_key: "results", ok_rc: "0 1", argv: ["osv-scanner","scan","source","--format","json","-L","pom.xml"]}`, php `{format: "composer", name: "composer", report: "composer-audit.json", required_key: "advisories", ok_rc: "0 1", argv: ["composer","audit","--format=json","--locked"]}`, csharp `{format: "dotnet", name: "dotnet", report: "dotnet.json", required_key: "projects", argv: ["dotnet","list","package","--vulnerable","--include-transitive","--format","json"]}`. php 리포트 이름은 매니페스트 `composer.json`과 겹치지 않게 `composer-audit.json`이다.
  - 추출기: `_ids_osv`(results[].packages[].vulnerabilities[] → [id]+aliases), `_ids_composer`(advisories{pkg: [..]} → [advisoryId 또는 cve]), `_ids_dotnet`(projects[].frameworks[].{topLevelPackages,transitivePackages}[].vulnerabilities[] → advisoryurl의 GHSA 꼬리).
  - toolchain: java `java`(bin `/opt/homebrew/opt/openjdk@25/bin/java`, args `["-version"]`), `mvn`, `osv-scanner`; php `php`, `composer`; csharp `dotnet`(args `["--version"]`).
  - loc: java scc `["Java"]`, test `Test\.java|Tests\.java`, exclude `target`, `.mvn`; php scc `["PHP"]`, test `Test\.php`, exclude `vendor`, `storage`, `bootstrap/cache`; csharp scc `["C#"]`, test `Tests?\.cs`, exclude `bin`, `obj`. cleanup: java `target`, php `vendor`, csharp `bin`, `obj`.
  - prompt WORKERS: java "Spring Boot's embedded Tomcat with its thread pool sized to the core count", php "Laravel Octane on FrankenPHP with one worker per core", csharp "Kestrel with the default .NET thread pool (scales with the core count)". STACK: "Java 25 + Spring Boot + Spring Data JPA (Hibernate) + SQLite, built with Maven", "PHP 8.5 + Laravel + Eloquent + SQLite, served by Laravel Octane on FrankenPHP", "C# (.NET 10) + ASP.NET Core + Entity Framework Core + SQLite".

- [ ] **Step 1: 실패하는 테스트** — 세 deps 규칙과 세 추출기마다 작은 픽스처로 기대값을 단언한다(예: test 스코프 1개·일반 3개인 pom.xml → 3, `php`·`ext-pdo` 포함 require 5개 → 3, 두 csproj에 같은 패키지 중복 → 고유 수). `langreg.keys()`가 8개이고 `validate` 전부 통과.
- [ ] **Step 2: 실패 확인** — `cd scripts && python3 -m unittest test_runtime_parse -v`.
- [ ] **Step 3: 구현** — JSON 3개, kind·format 함수, `render_prompts.py`로 PROMPT 3개 생성 후 `checksums.md5`에 md5 추가(기존 5줄 불변).
- [ ] **Step 4: 통과 확인** — 두 테스트 스위트 전체 PASS, `render_prompts.py --check` 종료 코드 0, 기존 5개 PROMPT md5 불변.
- [ ] **Step 5: Commit** — `git commit -m "feat(langbench): register Java, PHP and C# for EXP-002"`
