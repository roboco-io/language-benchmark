---
name: langbench-add-language
description: 새 구현 언어(예: Java, Kotlin, C#, PHP, Zig)를 language-benchmark에 추가해 EXP-001과 같은 조건으로 언어당 6 run을 실행하고 대시보드에 붙일 때 사용. 한 실험에 언어 하나 또는 여럿을 함께 추가한다.
---

# 새 구현 언어 추가

## 개요

EXP-001은 TypeScript, Python, Go, Rust, C++ 다섯 언어를 같은 RealWorld 백엔드 과제로 비교했다. 이 스킬은 그 옆에 언어를 더 붙이는 절차다. 새 언어를 모아 새 실험 번호(EXP-NNN) 하나로 실행하고, 결과를 같은 `runs.csv`와 대시보드에 올린다. 언어를 하나만 추가해도 되고 여러 개(예: EXP-002의 Java, PHP, C#)를 함께 추가해도 된다. 아래 절차는 언어 키 목록 `<keys>`를 기준으로 쓴다.

실행 자체는 기존 스킬 [langbench](../langbench/SKILL.md)의 스크립트를 그대로 쓴다. 이 스킬은 언어 추가에만 필요한 단계를 순서대로 안내한다. 언어별 설정은 `.claude/skills/langbench/languages/<key>.json` 한 파일(언어 레지스트리)에 모여 있으므로, 새 언어가 기존 형식을 쓰면 데이터 파일 추가만으로 등록된다. 새 의존성 매니페스트 형식이나 새 취약점 리포트 형식이 필요하면 스크립트 본문을 고쳐야 한다(2단계).

## 고정 규칙

- 구성은 새 언어마다 Sonnet 5.5·Opus 5.5 두 모델 × 3회 = 언어당 6 run이다. 후속 과제 6 run과 런타임 측정 6건이 따른다. 언어 N개를 함께 추가하면 본 과제는 6N run이다.
- 프롬프트 템플릿, 채점 정본(Hurl 154요청), 시간·iteration 상한, 비용 상한 방식, Fargate 측정 사양(앱 2 vCPU·1 GiB, 부하 4 vCPU, Graviton4 확인)은 EXP-001과 같게 둔다. 언어 간 조건을 다르게 두지 않는다.
- 기존 다섯 언어는 다시 실행하지 않는다. 실행 시점이 다른 데서 오는 차이는 버전을 기록하는 것으로 다룬다(사용자 결정, 2026-10-18). 이 비교는 시점이 다른 탐색적 비교이며, 판정 문장도 그 범위로 한정한다.
- 한 언어는 하나의 실험에만 나타날 수 있다. 같은 언어를 다시 돌리면 `build_dashboard_data.py`가 오류로 멈춘다. 재실행은 이 스킬의 범위 밖이다.
- 툴체인은 실험자가 run 전에 설치한다. 에이전트가 run 안에서 설치하게 두지 않는다.
- 4개 README의 `<!-- RESULTS:BEGIN/END -->` 사이는 직접 고치지 않는다. 마크다운 본문에는 물결표 문자를 쓰지 않고 범위는 `-`로 적는다.

## 절차

**EXP-002(Java, PHP, C#)의 현재 상태**: 2026-10-18 기준으로 `java`·`php`·`csharp`의 1-3단계가 이미 끝났다. 레지스트리 항목(`languages/java.json`·`php.json`·`csharp.json`), 새 형식(`maven-pom`·`composer-json`·`csproj`, `osv`·`composer`·`dotnet`)의 계수·추출 함수와 테스트, 렌더링한 `PROMPT-java.md`·`PROMPT-php.md`·`PROMPT-csharp.md`, `checksums.md5`의 해당 줄이 모두 커밋되어 있다. 따라서 EXP-002는 4단계부터 시작한다. 3단계의 `md5 -r … >> checksums.md5`를 다시 실행하면 같은 줄이 중복으로 붙으므로 실행하지 않는다.

**경로 규칙**: 아래 명령은 모두 리포 루트에서 실행한다. 디렉터리를 옮겨야 하는 명령은 괄호 서브셸 `( cd … && … )`로 감싸서, 한 셸에서 이어서 붙여 넣어도 다음 명령의 현재 디렉터리가 바뀌지 않게 한다. 하네스 디렉터리(`$BASE`)는 `setup.sh`가 `$HOME/experiments/langbench-exp<NNN>`(`<NNN>`은 `bench.env`의 `EXP` 값, 3자리)으로 만든다. 5단계 이후 명령을 실행하는 셸에서는 먼저 `BASE="$HOME/experiments/langbench-exp<NNN>"`를 정의한다.

1. **언어 조사와 스택 확정**: 언어마다 대표 웹 프레임워크, SQLite 접근 라이브러리, 취약점 스캐너, 의존성 매니페스트 형식을 조사한다. 선정 기준은 과제 README와 같다(해당 언어에서 가장 널리 쓰이고 LLM 학습 데이터가 풍부한 조합, DB는 SQLite). 근거와 확인일을 붙여 스택 후보를 사용자에게 보여 주고 `AskUserQuestion`으로 확정한다. 확정한 스택이 기존 레지스트리 형식으로 표현되는지도 이때 가린다. 완료 기준은 언어별 프레임워크·DB 라이브러리·스캐너·매니페스트가 사용자 승인으로 정해진 상태다.

2. **레지스트리 항목 작성**: 기존 파일(예: `.claude/skills/langbench/languages/go.json`)을 본떠 `languages/<key>.json`을 쓴다. 항목은 `key`, `name`, `order`(다음 정렬 값), `prompt`(`STACK`, `WORKERS`), `stack`(`framework`, `db`), `toolchain`, `scanner`, `deps`, `loc`, `hints`, `cleanup`이다. `scanner` 항목의 필드는 다음 뜻이다. 스캐너는 `runtime.sh`와 Fargate `local_prep.sh`가 공용 함수 `scan_lib.sh`의 `lb_static_scans`로 실행한다. 실행 위치는 앱 저장소에서 git이 추적하는 파일만 내보낸 임시 디렉터리다. 그러므로 빌드 산출물(`target/`, `bin/`, `vendor/`, `node_modules/` 등)은 없다고 가정한다.

   | 필드 | 뜻 |
   |---|---|
   | `format` | 리포트 형식 이름. `langreg.SCANNER_FORMATS` 중 하나다. `none`이면(cpp) 언어 스캐너 없이 Trivy 이미지 스캔만 쓴다 |
   | `name` | 실패 기록(`error.txt`)에 쓰는 스캐너 이름 |
   | `report` | 표준 출력을 저장할 보고서 파일 이름(예: `osv.json`). `runs_row.py`의 `SCAN_REPORTS`가 이 값을 레지스트리에서 읽으므로 `runs_row.py`는 고치지 않는다 |
   | `argv` | 실행할 명령과 인자. `${VAR}`는 환경변수 값으로 바뀐다(없으면 빈 문자열). 표준 출력이 보고서이므로 진행 메시지나 빌드 로그는 표준 오류로 보내야 한다. 명령 두 개가 필요하면(java처럼 Maven으로 SBOM을 만든 뒤 osv-scanner로 스캔) `["bash", "-c", "<명령1> 1>&2 && <명령2>"]`로 쓴다 |
   | `alt` | 선택. `{"if_file": <파일>, "argv": [...]}` 목록이다. 내보낸 디렉터리에 `if_file`이 있는 첫 항목의 `argv`가 기본 `argv`를 대신한다(python의 `requirements.txt`) |
   | `required_key` | 보고서로 인정할 조건. 값이 있으면 표준 출력이 JSON 객체 하나이고 그 최상위에 이 키가 있어야 한다. `stream:<키>`이면 JSON 스트림 중 어느 객체에든 그 키가 있어야 한다(go의 `stream:config`). 비우면 유효한 JSON이기만 하면 된다 |
   | `ok_rc` | 보고서로 인정할 종료 코드 목록(공백 구분, 예: `"0 1"`). 비우면 종료 코드를 보지 않는다 |

   새 스캐너를 넣기 전에 그 스캐너의 종료 코드와 빈 보고서 모양을 실제로 확인한다. 취약점이 없을 때도 `required_key`가 있는 JSON을 내야 하며(예: composer는 `"advisories": []`, osv-scanner는 `"results": []`), 취약점이 있을 때의 종료 코드(예: osv-scanner·composer는 1, govulncheck는 3)를 `ok_rc`에 넣는다. 조건을 못 맞춘 실행은 보고서가 없는 것으로 처리되어 그 run의 `vulns`가 빈 칸이 된다. 반대로 스캐너가 의존성을 해석하지 못하고 빈 결과를 정상 종료 코드로 내면 `vulns`가 조용히 적게 세어진다. 그러므로 알려진 취약 버전을 고정한 작은 프로젝트로 실제 취약점이 잡히는지 확인한다.

   그다음 검사한다.

   ```
   python3 .claude/skills/langbench/scripts/langreg.py validate <keys>
   python3 .claude/skills/langbench/scripts/langreg.py keys
   ```

   완료 기준은 `validate`가 오류 없이 끝나고 `keys`에 새 키가 순서대로 보이는 것이다.

   **테스트의 고정 목록 갱신**: 몇몇 테스트는 등록된 언어 전체를 목록으로 고정해 둔다. 새 키를 넣으면 다음 테스트의 기대값에 새 키와 그 값을 더한다. `.claude/skills/langbench/tests/test_langreg.py`의 `test_keys_order`·`test_cli_keys_and_validate`(키 순서), `test_shell_union_matches_legacy`(모든 언어의 `loc.exclude_dirs`와 `hints` 합집합), `test_cleanup_dirs`(`cleanup` 합집합)와 `scripts/test_registry_regression.py`의 `test_scanners_unchanged`(언어별 스캐너 형식)·`test_languages_unchanged`(키 순서와 표시 이름)다.

   **스크립트를 고쳐야 하는 경우**: 허용된 형식 종류는 `langreg.DEPS_KINDS`(`package-json`, `pyproject-or-requirements`, `go-mod`, `cargo-toml`, `cmake`, `maven-pom`, `composer-json`, `csproj`)와 `langreg.SCANNER_FORMATS`(`npm`, `pip`, `go`, `cargo`, `osv`, `composer`, `dotnet`, `none`)다. 새 언어의 매니페스트(예: Gradle)나 스캐너 리포트가 이 목록에 없으면 다음 네 곳을 함께 고친다.
   - `deps` 형식: `langreg.DEPS_KINDS`에 이름을 더하고, 같은 이름의 계수 함수를 `scripts/runtime_parse.py`의 `DEPS_BY_KIND`에 더한다.
   - 스캐너 형식: `langreg.SCANNER_FORMATS`에 이름을 더하고, 리포트에서 ID와 alias를 뽑는 함수를 `runtime_parse.py`의 `_EXTRACT`에 더한다.
   - `scripts/test_runtime_parse.py`에 대표 매니페스트·리포트 픽스처로 새 함수의 테스트를 쓴다. `langreg`의 상수 집합과 `runtime_parse`의 `DEPS_BY_KIND`·`_EXTRACT` 키는 서로 거울이어야 한다. `scripts/test_runtime_parse.py`의 `test_registry_kinds_and_formats_match_parsers`가 이를 검사하므로, 한쪽에만 이름을 더하면 이 테스트가 실패한다.
   - `analysis/metrics/SCHEMA.md`의 deps·vulns 규칙은 레지스트리를 참조한다. 새 형식의 계산 방식만 그 절에 한 줄 더한다.

   테스트는 다음으로 실행한다(둘 다 통과해야 한다).

   ```
   ( cd scripts && python3 -m unittest discover -p 'test_*.py' )
   ( cd .claude/skills/langbench && python3 -m unittest discover -s tests )
   ```

   스킬 테스트는 5분 정도 걸린다.

   새 형식이 필요 없으면 이 하위 단계는 건너뛴다.

3. **프롬프트**: 렌더링하고 체크섬을 등록한다.

   ```
   python3 .claude/skills/langbench/scripts/render_prompts.py
   ( cd .claude/skills/langbench/assets && md5 -r PROMPT-<key>.md >> checksums.md5 )
   ```

   새 언어마다 `md5 -r` 줄을 한 번만 실행한다(EXP-002의 세 언어는 이미 등록되어 있다). `assets/prompt/template.md`와 채점 정본(`harness-hurl/`, `followup-hurl/`, `k6/`)은 고치지 않는다. 확인은 세 가지다. 첫째, `python3 .claude/skills/langbench/scripts/render_prompts.py --check`는 디스크의 `PROMPT-*.md`가 레지스트리와 템플릿으로 다시 렌더링한 결과와 같은지 본다(다르면 종료 코드 1). 둘째, 해시 대조는 `( cd .claude/skills/langbench/assets && md5 -r $(cut -d' ' -f2 checksums.md5) | diff - checksums.md5 )`이며 출력이 없어야 한다. `setup.sh`도 같은 대조를 한다. 셋째, `git diff .claude/skills/langbench/assets/`에서 기존 다섯 언어의 PROMPT 파일과 `checksums.md5` 기존 줄, 템플릿, 정본이 바뀌지 않았는지 본다. 완료 기준은 기존 줄이 하나도 바뀌지 않고 새 줄만 늘어난 상태다.

4. **실험 설계**: 다음 번호로 `experiments/NNN-<slug>/README.md`를 `templates/experiment-readme.md`로 쓰고 `hypotheses/catalog.md`에 가설을 등록한다(가설 코드의 `L` 축은 구현 언어다). 설계 README에는 EXP-001 대비 버전 차이 표를 반드시 넣는다. 비교 항목은 실행일, Claude Code 버전, Sonnet·Opus 모델 ID, 언어별 툴체인 버전, 하네스 스크립트·정본 해시 일치 여부다. 같은 표를 보고서에도 옮긴다. 판정 문장은 "EXP-001과 다른 시점의 탐색적 비교"로 한정한다. 이어서 `experiments/NNN-<slug>/experiment.json`을 쓴다. 대시보드 빌더(`scripts/build_dashboard_data.py`)가 읽는 파일이며 EXP-001 파일과 같은 형식이다.

   ```json
   {
     "exp": "EXP-NNN",
     "folder": "NNN-<slug>",
     "date": "YYYY-MM-DD",
     "client": "Claude Code <버전>",
     "models": {"sonnet55": "claude-sonnet-5-5", "opus55": "claude-opus-5-5"}
   }
   ```

   `date`와 `client`는 실제로 실행하는 날과 `claude --version` 값으로 채운다. 완료 기준은 설계 README, 카탈로그 항목, `experiment.json`이 모두 있는 상태다.

5. **툴체인, 하네스, Phase 0**: 다음 순서로 진행한다.

   (가) **툴체인 설치**: 실험자가 새 언어의 툴체인을 설치한다. 설치 명령은 제안할 수 있지만 run 안에서 실행하지 않는다. 프레임워크가 실행 중에 바이너리를 내려받는 경우도 확인한다. 예: Laravel Octane은 FrankenPHP 1.13.0 이상을 요구하고, PATH의 바이너리가 그보다 낮으면 기존 파일을 `.backup`으로 바꾼 뒤 최신 바이너리를 앱 디렉터리에 내려받는다. EXP-002는 공식 릴리스 단일 바이너리 1.13.1을 `$HOME/.local/share/langbench-tools/bin`에 두고 `bench.env`의 PATH에 넣었다. hello-world로 실제 기동해 다운로드가 일어나지 않는지 확인한다.

   (나) **`bench.env` 작성**: `scripts/bench.env.example`을 리포 밖으로 복사해 채운다. 예를 들어 `cp .claude/skills/langbench/scripts/bench.env.example "$HOME/experiments/langbench-exp<NNN>.env"`로 만든다. `EXP`는 새 번호(3자리), `LANGS`는 새 언어 키만(예: `"java php csharp"`), `N=3`, `CONDITIONS`는 EXP-001과 같은 두 모델이다. `MODEL_SPECS`는 모델 사양 JSON이며, 비용 상한 `RUN_BUDGET_USD`·`EXP_BUDGET_USD`도 필수다.

   (다) **PATH 고정**: 설치한 도구가 PATH에서 첫 번째가 아니면 하네스가 잘못된 버전을 쓴다. 예를 들어 OpenJDK 25는 `/opt/homebrew/opt/openjdk@25`에 설치만 되고 링크되지 않아 있고, PATH의 `java`는 asdf의 더 낮은 버전(21)일 수 있다. 이런 경우 (나)에서 만든 `bench.env` 파일 끝에 다음 두 줄을 덧붙이고, 그 사실을 실험 README에 기록한다.

   ```
   export JAVA_HOME=/opt/homebrew/opt/openjdk@25
   export PATH="$JAVA_HOME/bin:$PATH"
   ```

   `bench.env`의 `export`는 `setup.sh`(그 안의 `toolchain.sh` 포함), `run-all.sh`, `driver.sh`가 읽는다. 레지스트리의 java 툴체인 점검은 PATH의 `java`를 실행하므로, 이 두 줄이 빠지면 `toolchain.txt`에 의도와 다른 버전(예: 21)이 찍힌다. 그 값으로 누락을 발견한다.

   (라) **하네스 생성**: setup을 실행한다.

   ```
   bash .claude/skills/langbench/scripts/setup.sh "$HOME/experiments/langbench-exp<NNN>.env"
   BASE="$HOME/experiments/langbench-exp<NNN>"
   ```

   setup은 `bench.env`를 읽은 뒤 `LANGS`를 `toolchain.sh`에 넘겨 새 언어 도구가 없으면 중단하고, 레지스트리 검증과 PROMPT·체크섬 대조를 하며, 스크립트와 `languages/`를 `$BASE`에 복사하고 `bench.env`를 `$BASE/bench.env`로 복사한다. `$BASE/toolchain.txt`에서 새 도구 버전(java는 25)을 확인한다.

   (마) **직접 실행하는 스크립트의 셸 준비**: 하네스 밖에서 직접 돌리는 `runtime.sh`와 `orchestrate.py`(그 안의 `local_prep.sh` 포함)는 `bench.env`를 읽지 않는다. 그런데 java의 정적 분석은 이 단계에서 `mvn`을 실행한다. 그러므로 이 스크립트를 실행할 셸에서는 먼저 `source "$BASE/bench.env"`를 하고 `command -v java`와 `java -version`으로 의도한 버전인지 확인한다.

   (바) **Phase 0**: 하네스의 `smoke.sh`는 `preflight-approved`가 없으면 실행을 거부한다. 그러므로 6단계 승인을 이 시점에 먼저 받는다. 승인 보고에는 Phase 0(스모크, hello-world, Fargate 파일럿)과 본 실행을 함께 넣고, "Phase 0에서 문제가 나오면 본 run 전에 멈추고 보고한다"는 조건을 명시한다(EXP-002에서 이 순서로 진행). 그다음 `bash "$BASE/smoke.sh"`가 전부 PASS여야 한다. 언어별 hello-world 빌드 시간을 확인해 `runs/phase0.md`에 기록한다. 공통 `MAX_SEC`는 EXP-001과 같은 값을 쓴다. 빌드가 느린 언어는 같은 상한 안에서 iteration 여유가 줄고 이는 측정 대상이다.

   (사) **Fargate 파일럿(Phase 0 필수)**: Fargate 측정의 로컬 단계(`local_prep.sh`: 작업 트리 내보내기, Docker 빌드, ECR push, 레지스트리 기반 정적 분석)는 레지스트리 전환 뒤 처음부터 끝까지 실제로 돌려 본 적이 없다. 그러므로 본 실행 승인 전에 run 하나로 파일럿을 한다. 파일럿에는 Hurl 정본을 통과하는 RealWorld 앱이 필요한데 이 시점에는 새 언어의 앱이 없으므로, EXP-001에서 측정한 앱 하나를 git 저장소로 복사해 쓴다. 먼저 8단계의 첫 두 하위 단계(`tools` 이미지 빌드·push, `$BASE/fargate.env` 작성)를 마친다. 파일럿은 AWS 비용이 들므로 실행 전에 사용자에게 알린다.

   ```
   cp -R experiments/001-five-language-realworld/runs/apps/opus55-go-1 "$BASE/app-pilot-go"
   ( cd "$BASE/app-pilot-go" && git init -q && git add -A && git commit -qm pilot )
   source "$BASE/bench.env"
   AWS_PROFILE=roboco python3 .claude/skills/langbench/fargate/orchestrate.py "$BASE" --exp EXP-<NNN> --config "$BASE/fargate.env" --runs pilot-go --tag pilot --soak-sec 60
   ```

   run 이름 `pilot-go`의 조각 `go`로 언어가 정해진다(`langreg.py detect`). `--tag pilot`은 S3 접두어를 본 측정과 분리하고, `--soak-sec 60`은 soak 구간을 줄인다. 확인할 것은 `$BASE/runtime-fg-pilot-go/`에 `build.json`, `scc.json`, `go.json`, `trivy.json`, 부하 요약 3개가 있고 `error.txt`가 없으며, `attempts.csv`와 probe 기록에서 Graviton4가 확인되는 것이다. 결과를 `runs/phase0.md`에 기록한 뒤 `mkdir -p "$BASE/pilot" && mv "$BASE/app-pilot-go" "$BASE/runtime-fg-pilot-go" "$BASE/pilot/"`로 옮긴다. 파일럿 앱이 `$BASE`에 남아 있으면 오염 검사가 그 앱 참조를 다른 앱 참조로 잡을 수 있다.

   이 파일럿은 새 언어의 스캐너를 실행하지 않는다. 새 언어의 스캐너(java의 Maven SBOM + osv-scanner, php의 composer audit, csharp의 dotnet list package)는 8단계에서 언어마다 첫 run 하나를 먼저 측정해 보고서 파일이 생겼는지 확인한 뒤 나머지를 측정한다.

   완료 기준은 setup 성공, `toolchain.txt`의 새 도구 버전 확인, `smoke.sh` 전체 PASS, hello-world 빌드 시간과 Fargate 파일럿 결과가 `runs/phase0.md`에 기록된 상태다.

6. **승인(실행 전 필수)**: 사양과 예상 사용량을 보고한다. 포함할 내용은 언어 목록, run 수(본 과제 6N, 후속 6N), 두 모델, `MAX_SEC`·`MAX_ITER`, 비용 상한, `preflight-report.md`의 추정 비용, 예상 소요 시간, 구독 한도 위험, Fargate 측정에 드는 AWS 비용이다. 보고한 뒤 `AskUserQuestion`으로 실행 여부를 묻는다. 사용자가 진행을 고른 경우에만 `bash $BASE/approve.sh "<답변 원문>"`을 실행한다. 승인이 없으면 `run-all.sh`가 실행을 거부한다.

7. **실행**: 기존 langbench 절차의 6-9단계를 그대로 따른다.

   ```
   nohup caffeinate -is bash $BASE/run-all.sh >/dev/null 2>&1 &
   nohup caffeinate -is bash $BASE/run_followups.sh >/dev/null 2>&1 &   # run-all의 done-all 뒤
   ```

   `run-all.sh`는 `LANGS`와 `CONDITIONS`로 run 목록을 만들고 블록별로 순서를 섞는다. 이어서 완주 run마다 독립 재검증(`measure.sh`·`measure_docker.sh` 2회)과 오염 검사(`contamination_check.py`)를 하고, 그 뒤에 후속 과제를 돌린다. 구독 한도로 보류된 run은 서비스 장애 보류이므로 처음부터 다시 실행한다. 완료 기준은 `done-all`, 후속 과제 완료, `recheck.csv`의 `13,154` 일치, 오염 검사 기록이다.

8. **측정**: 기본은 AWS Fargate Spot에서 병렬 측정이다. 로컬 `runtime.sh`는 순차이고 느리므로 필요할 때만 쓴다. 새 AWS 자원은 만들지 않고, 2026-10-18에 남겨 둔 자원(us-east-1의 ECR `langbench`, S3 `langbench-exp001-<계정>`, ECS 클러스터 `langbench`, IAM 역할 `langbench-exec`·`langbench-task`, 보안 그룹, 로그 그룹 `/langbench`)을 재사용한다. 실험은 S3 접두어 `exp<NNN>/`와 태스크 태그 `experiment=EXP-NNN`으로 구분된다.

   첫째, 설정 파일을 만든다. `fargate.env`에는 계정 ID가 들어가므로 리포 밖인 `$BASE`에 둔다. 예시 파일의 값이 남겨 둔 자원의 값이다. 이 파일은 `KEY=값` 형식이라 셸에서 `source`할 수 있다.

   ```
   cp .claude/skills/langbench/fargate/fargate.env.example "$BASE/fargate.env"
   ```

   둘째, 2026-10-18에 ECR 이미지를 삭제했으므로 처음 쓰기 전에 `tools` 이미지를 다시 만들어 올린다. `Dockerfile.tools`의 `COPY`는 `probe.py`·`runner.sh`·`seed.py`·`load.js`가 빌드 컨텍스트의 최상위에 있다고 가정한다. 그래서 임시 디렉터리에 모아 그 디렉터리를 컨텍스트로 쓰고, `-f`에도 그 디렉터리 안의 `Dockerfile.tools` 경로를 준다.

   ```
   CTX="$(mktemp -d)"
   cp .claude/skills/langbench/fargate/Dockerfile.tools .claude/skills/langbench/fargate/probe.py .claude/skills/langbench/fargate/runner.sh .claude/skills/langbench/scripts/seed.py .claude/skills/langbench/assets/k6/load.js "$CTX/"
   source "$BASE/fargate.env"
   AWS_PROFILE=roboco aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "${ECR_REPO%%/*}"
   docker buildx build --platform linux/arm64 -f "$CTX/Dockerfile.tools" -t "${ECR_REPO}:tools" --push "$CTX"
   ```

   태그는 반드시 `"${ECR_REPO}:tools"`처럼 중괄호로 쓴다. zsh는 `$ECR_REPO:tools`의 `:t`를 변수 수정자로 해석해 태그를 `langbenchools`로 깨뜨린다(2026-10-19 EXP-002에서 관측).

   여기까지가 5단계 (사) 파일럿의 선행 작업이다. 셋째, 측정을 실행한다. 셸에서 먼저 `source "$BASE/bench.env"`를 해 둔다(5단계 (마)). 언어마다 첫 run 하나를 `--runs`로 먼저 측정해 `$BASE/runtime-fg-<run>/`에 언어 스캐너 보고서(java `osv.json`, php `composer-audit.json`, csharp `dotnet.json`)가 생겼는지 확인한다. 보고서가 없으면 `error.txt`의 스캐너 실패 사유를 보고 나머지를 측정하기 전에 사용자에게 알린다. 그다음 나머지를 측정한다.

   ```
   AWS_PROFILE=roboco python3 .claude/skills/langbench/fargate/orchestrate.py "$BASE" --exp EXP-<NNN> --config "$BASE/fargate.env" --runs <run1>,<run2>,<run3>
   AWS_PROFILE=roboco python3 .claude/skills/langbench/fargate/orchestrate.py "$BASE" --exp EXP-<NNN> --config "$BASE/fargate.env" --runs <나머지 run을 쉼표로 구분>
   ```

   `--runs`를 빼면 `$BASE/run-order.txt`의 run 전체를 측정하고 이미 측정한 run도 다시 측정하므로, 두 번째 실행에는 나머지 run만 넘긴다.

   `--parallel`·`--attempts`로 동시성과 재시도 횟수를 정한다. 산출물은 `$BASE/runtime-fg-<run>/`에 합쳐진다. 실행 전에 Fargate 측정에 필요한 비용과 시간이 6단계 승인에 포함됐는지 확인한다. 측정 사양이 Graviton4(ARM64)인지 `attempts.csv`와 `probe` 기록으로 확인한다. Spot 회수가 `--attempts`를 넘으면 그 run은 빈 칸으로 두고 이유를 보고서에 적는다. 새 언어의 앱이 ARM64 이미지로 빌드·기동되지 않으면 그 사실이 곧 측정 결과이므로 임의로 바꾸지 않는다. 완료 기준은 run마다 `runtime-fg-<run>/`의 부하 요약 3개와 메모리·시작 시간 파일이 있고 `error.txt`가 없는 상태다.

9. **집계와 보고**: 원 run마다 행을 추가한다. 미측정 값은 빈 칸이고 0으로 채우지 않는다.

   ```
   python3 scripts/runs_row.py $BASE EXP-NNN <run> --append analysis/metrics/runs.csv
   ```

   그다음 langbench 절차의 12-14단계(보관 전 `redact_keys.py` 마스킹, `templates/report.md`로 `report.md` 작성, 로컬 정리)를 한다. 이어서 repo `CLAUDE.md`의 "실험 종료 시 필수 절차"를 수행한다.
   - report.md 헤더 세 줄을 쓴다. 보고서에는 4단계의 EXP-001 대비 버전 차이 표와 "탐색적 비교" 범위 한정 문구를 넣는다.
   - `scripts/readme_i18n.json`에 영·일·중 번역을 더하고 `python3 scripts/update_readme_results.py`를 실행한다.
   - `python3 scripts/build_dashboard_data.py`로 `dashboard/data.js`를 다시 만든다. 새 언어는 `experiment.json`을 읽어 실험 번호·실행일 배지와 함께 대시보드에 표시된다. 한 언어가 두 실험에 걸치면 이 단계가 오류로 멈춘다.
   - `hypotheses/catalog.md`와 `ROADMAP.md`를 갱신한다. README의 `### 종합 인사이트` 절은 4개 언어(한국어·영어·일본어·중국어) 모두 손으로 고친다.
   - main에 push한 뒤 GitHub Actions 배포와 Pages 반영을 확인한다.

   종합 점수는 비교 대상 언어 안에서 정규화하므로, 새 언어가 들어오면 기존 다섯 언어의 표시 점수와 순위가 바뀔 수 있다. 보고서에 이 점과, EXP-001 판정은 EXP-001 보고서가 기준이라는 점을 적는다. 완료 기준은 스크립트 테스트 전체 통과, Pages에서 새 언어와 배지 확인이다.

## 흔한 실수

| 실수 | 결과 | 대응 |
|---|---|---|
| 새 매니페스트·리포트 형식을 레지스트리에만 적음 | `validate` 실패 또는 `runtime_parse`에서 `KeyError` | 2단계처럼 `langreg`의 상수 집합과 `runtime_parse`의 `DEPS_BY_KIND`·`_EXTRACT`를 함께 고치고 픽스처 테스트를 쓴다 |
| 새 툴체인이 PATH 첫 번째가 아님 | 하네스가 낮은 버전(예: asdf의 Java 21)으로 빌드하고 `toolchain.txt`에도 그 버전이 남는다 | `bench.env`에 PATH·`JAVA_HOME` 등을 export하고 기록한다. 직접 돌리는 `runtime.sh`·`orchestrate.py` 셸에서는 `bench.env`를 source한다 |
| `checksums.md5` 기존 줄이나 템플릿·정본을 수정 | 모든 setup과 게이트가 해시 불일치로 중단되고 EXP-001과 비교할 수 없다 | 새 PROMPT 줄만 추가한다. `git diff`로 기존 줄 불변을 확인한다 |
| 이미 등록된 언어(EXP-002의 java·php·csharp)에 3단계 `md5 -r … >> checksums.md5`를 다시 실행 | `checksums.md5`에 같은 줄이 중복된다 | 등록 여부를 `grep PROMPT-<key>.md .claude/skills/langbench/assets/checksums.md5`로 먼저 확인한다. EXP-002는 4단계부터 시작한다 |
| 스캐너가 의존성을 해석하지 못하고 빈 결과를 정상 종료 코드로 냄 | 보고서가 유효하게 받아들여져 `vulns`가 조용히 적게 세어진다 | 2단계처럼 알려진 취약 버전을 고정한 작은 프로젝트로 실제 취약점이 잡히는지 확인한다(java는 이 문제로 Maven SBOM을 거친다) |
| Fargate 파일럿 없이 전체 측정 | `local_prep.sh` 결함이 모든 run의 측정을 동시에 망친다 | 5단계 (사) 파일럿과 8단계의 언어별 첫 run 확인을 먼저 한다 |
| 버전 차이 표 없이 EXP-001과 한 순위로 서술 | 시점이 다른 결과가 같은 조건의 결과처럼 읽힌다 | 4단계 표와 "탐색적 비교" 문구를 설계 README와 보고서에 넣는다 |
| 같은 언어를 다른 실험에서 다시 실행 | `build_dashboard_data.py`가 오류로 멈춘다 | 이 스킬의 범위 밖이다. 한 언어는 한 실험에만 둔다 |
| ECR `tools` 이미지 없이 Fargate 실행 | 앱 태스크의 probe 사이드카와 부하 태스크가 이미지를 못 받아 기동하지 못한다 | 8단계대로 `tools` 이미지를 다시 빌드해 push한다 |
| 승인 없이 실행 | `run-all.sh`가 거부한다. 승인을 우회하면 규칙 위반이다 | 6단계의 `AskUserQuestion` 후 `approve.sh` |
| 에이전트 run 안에서 툴체인 설치 | 언어 간 환경 차이 | 실험자가 5단계에서 미리 설치한다 |
