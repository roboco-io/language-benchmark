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

1. **언어 조사와 스택 확정**: 언어마다 대표 웹 프레임워크, SQLite 접근 라이브러리, 취약점 스캐너, 의존성 매니페스트 형식을 조사한다. 선정 기준은 과제 README와 같다(해당 언어에서 가장 널리 쓰이고 LLM 학습 데이터가 풍부한 조합, DB는 SQLite). 근거와 확인일을 붙여 스택 후보를 사용자에게 보여 주고 `AskUserQuestion`으로 확정한다. 확정한 스택이 기존 레지스트리 형식으로 표현되는지도 이때 가린다. 완료 기준은 언어별 프레임워크·DB 라이브러리·스캐너·매니페스트가 사용자 승인으로 정해진 상태다.

2. **레지스트리 항목 작성**: 기존 파일(예: `.claude/skills/langbench/languages/go.json`)을 본떠 `languages/<key>.json`을 쓴다. 항목은 `key`, `name`, `order`(다음 정렬 값), `prompt`(`STACK`, `WORKERS`), `stack`(`framework`, `db`), `toolchain`, `scanner`, `deps`, `loc`, `hints`, `cleanup`이다. 그다음 검사한다.

   ```
   python3 .claude/skills/langbench/scripts/langreg.py validate <keys>
   python3 .claude/skills/langbench/scripts/langreg.py keys
   ```

   완료 기준은 `validate`가 오류 없이 끝나고 `keys`에 새 키가 순서대로 보이는 것이다.

   **스크립트를 고쳐야 하는 경우**: 허용된 형식 종류는 `langreg.DEPS_KINDS`(`package-json`, `pyproject-or-requirements`, `go-mod`, `cargo-toml`, `cmake`)와 `langreg.SCANNER_FORMATS`(`npm`, `pip`, `go`, `cargo`, `none`)다. 새 언어의 매니페스트(Maven `pom.xml`, Gradle, NuGet, Composer 등)나 스캐너 리포트가 이 목록에 없으면 다음 네 곳을 함께 고친다.
   - `deps` 형식: `langreg.DEPS_KINDS`에 이름을 더하고, 같은 이름의 계수 함수를 `scripts/runtime_parse.py`의 `DEPS_BY_KIND`에 더한다.
   - 스캐너 형식: `langreg.SCANNER_FORMATS`에 이름을 더하고, 리포트에서 ID와 alias를 뽑는 함수를 `runtime_parse.py`의 `_EXTRACT`에 더한다.
   - `scripts/test_runtime_parse.py`에 대표 매니페스트·리포트 픽스처로 새 함수의 테스트를 쓴다. `langreg`의 상수 집합과 `runtime_parse`의 `DEPS_BY_KIND`·`_EXTRACT` 키는 서로 거울이어야 하는데 이를 자동으로 검사하는 테스트는 없으므로, 두 곳의 이름이 같은지 직접 대조한다.
   - `analysis/metrics/SCHEMA.md`의 deps·vulns 규칙은 레지스트리를 참조한다. 새 형식의 계산 방식만 그 절에 한 줄 더한다.

   테스트는 다음으로 실행한다(둘 다 통과해야 한다).

   ```
   cd scripts && python3 -m unittest discover -p 'test_*.py'
   cd .claude/skills/langbench && python3 -m unittest discover -s tests
   ```

   새 형식이 필요 없으면 이 하위 단계는 건너뛴다.

3. **프롬프트**: 렌더링하고 체크섬을 등록한다.

   ```
   python3 .claude/skills/langbench/scripts/render_prompts.py
   cd .claude/skills/langbench/assets && md5 -r PROMPT-<key>.md >> checksums.md5
   ```

   `assets/prompt/template.md`와 채점 정본(`harness-hurl/`, `followup-hurl/`, `k6/`)은 고치지 않는다. 기존 다섯 언어 PROMPT 파일의 md5가 `checksums.md5`의 기존 줄과 그대로인지(`render_prompts.py --check`), 템플릿과 정본 해시가 EXP-001 때와 같은지 `git diff`로 확인한다. 완료 기준은 기존 줄이 하나도 바뀌지 않고 새 줄만 늘어난 상태다.

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

5. **툴체인과 Phase 0**: 실험자가 새 언어의 툴체인을 설치한다. 설치 명령은 제안할 수 있지만 run 안에서 실행하지 않는다. 설치한 도구가 PATH에서 첫 번째가 아니면 하네스가 잘못된 버전을 쓴다. 예를 들어 OpenJDK 25는 `/opt/homebrew/opt/openjdk@25`에 설치만 되고 링크되지 않아 있고, PATH의 `java`는 asdf의 더 낮은 버전(21)일 수 있다. 이런 경우 `bench.env`에 PATH와 `JAVA_HOME`을 명시하고 그 사실을 실험 README에 기록한다.

   ```
   export JAVA_HOME=/opt/homebrew/opt/openjdk@25
   export PATH="$JAVA_HOME/bin:$PATH"
   ```

   `bench.env`의 `export`는 `setup.sh`(toolchain.sh 포함)와 `driver.sh`가 읽는다. 하네스 밖에서 직접 돌리는 `runtime.sh`와 `orchestrate.py`는 `bench.env`를 읽지 않으므로, 같은 셸에서 `source $BASE/bench.env`를 먼저 하고 `command -v java`와 `java -version`으로 의도한 버전인지 확인한다. 이어서 `bench.env`를 `scripts/bench.env.example`에서 복사해 채운다. `EXP`는 새 번호, `LANGS`는 새 언어 키만(예: `"java php csharp"`), `N=3`, `CONDITIONS`는 EXP-001과 같은 두 모델이다. `MODEL_SPECS`는 모델 사양 JSON이며, 비용 상한 `RUN_BUDGET_USD`·`EXP_BUDGET_USD`도 필수다. 그다음 진행한다.

   ```
   bash .claude/skills/langbench/scripts/setup.sh <bench.env>
   ```

   setup은 `LANGS`를 `toolchain.sh`에 넘겨 새 언어 도구가 없으면 중단하고, 레지스트리 검증과 PROMPT·체크섬 대조를 하며, `languages/`를 하네스 BASE에 복사한다. 완료 기준은 setup 성공, `$BASE/toolchain.txt`에 새 도구 버전이 기록된 상태, `smoke.sh` 전체 PASS, 언어별 hello-world 빌드 시간 확인과 `runs/phase0.md` 기록이다. 공통 `MAX_SEC`는 EXP-001과 같은 값을 쓴다. 빌드가 느린 언어는 같은 상한 안에서 iteration 여유가 줄고 이는 측정 대상이다.

6. **승인(실행 전 필수)**: 사양과 예상 사용량을 보고한다. 포함할 내용은 언어 목록, run 수(본 과제 6N, 후속 6N), 두 모델, `MAX_SEC`·`MAX_ITER`, 비용 상한, `preflight-report.md`의 추정 비용, 예상 소요 시간, 구독 한도 위험, Fargate 측정에 드는 AWS 비용이다. 보고한 뒤 `AskUserQuestion`으로 실행 여부를 묻는다. 사용자가 진행을 고른 경우에만 `bash $BASE/approve.sh "<답변 원문>"`을 실행한다. 승인이 없으면 `run-all.sh`가 실행을 거부한다.

7. **실행**: 기존 langbench 절차의 6-9단계를 그대로 따른다.

   ```
   nohup caffeinate -is bash $BASE/run-all.sh >/dev/null 2>&1 &
   nohup caffeinate -is bash $BASE/run_followups.sh >/dev/null 2>&1 &   # run-all의 done-all 뒤
   ```

   `run-all.sh`는 `LANGS`와 `CONDITIONS`로 run 목록을 만들고 블록별로 순서를 섞는다. 이어서 완주 run마다 독립 재검증(`measure.sh`·`measure_docker.sh` 2회)과 오염 검사(`contamination_check.py`)를 하고, 그 뒤에 후속 과제를 돌린다. 구독 한도로 보류된 run은 서비스 장애 보류이므로 처음부터 다시 실행한다. 완료 기준은 `done-all`, 후속 과제 완료, `recheck.csv`의 `13,154` 일치, 오염 검사 기록이다.

8. **측정**: 기본은 AWS Fargate Spot에서 병렬 측정이다. 로컬 `runtime.sh`는 순차이고 느리므로 필요할 때만 쓴다. 새 AWS 자원은 만들지 않고, 2026-10-18에 남겨 둔 자원(us-east-1의 ECR `langbench`, S3 `langbench-exp001-<계정>`, ECS 클러스터 `langbench`, IAM 역할 `langbench-exec`·`langbench-task`, 보안 그룹, 로그 그룹 `/langbench`)을 재사용한다. 실험은 S3 접두어 `exp<NNN>/`와 태스크 태그 `experiment=EXP-NNN`으로 구분된다.

   2026-10-18에 ECR 이미지를 삭제했으므로 처음 쓰기 전에 `tools` 이미지를 다시 만들어 올린다. 빌드 컨텍스트에는 `fargate/`의 `Dockerfile.tools`·`probe.py`·`runner.sh`와 `scripts/seed.py`, `assets/k6/load.js`가 같은 디렉터리에 있어야 한다(Dockerfile의 `COPY` 대상). 임시 디렉터리에 모아 빌드한다.

   ```
   AWS_PROFILE=roboco aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin <계정>.dkr.ecr.us-east-1.amazonaws.com
   docker buildx build --platform linux/arm64 -f Dockerfile.tools -t <ECR_REPO>:tools --push <컨텍스트 디렉터리>
   ```

   그다음 설정을 만들고 실행한다. 설정은 `fargate.env.example`을 `fargate.env`로 복사해 쓴다(예시의 값이 남겨 둔 자원의 값이다).

   ```
   AWS_PROFILE=roboco python3 .claude/skills/langbench/fargate/orchestrate.py $BASE --exp EXP-NNN --config fargate.env
   ```

   `--runs r1,r2`로 일부만, `--parallel`·`--attempts`로 동시성과 재시도 횟수를 정한다. 산출물은 `$BASE/runtime-fg-<run>/`에 합쳐진다. 실행 전에 Fargate 측정에 필요한 비용과 시간이 6단계 승인에 포함됐는지 확인한다. 측정 사양이 Graviton4(ARM64)인지 `attempts.csv`와 `probe` 기록으로 확인한다. Spot 회수가 `--attempts`를 넘으면 그 run은 빈 칸으로 두고 이유를 보고서에 적는다. 새 언어의 앱이 ARM64 이미지로 빌드·기동되지 않으면 그 사실이 곧 측정 결과이므로 임의로 바꾸지 않는다. 완료 기준은 run마다 `runtime-fg-<run>/`의 부하 요약 3개와 메모리·시작 시간 파일이 있고 `error.txt`가 없는 상태다.

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
| 버전 차이 표 없이 EXP-001과 한 순위로 서술 | 시점이 다른 결과가 같은 조건의 결과처럼 읽힌다 | 4단계 표와 "탐색적 비교" 문구를 설계 README와 보고서에 넣는다 |
| 같은 언어를 다른 실험에서 다시 실행 | `build_dashboard_data.py`가 오류로 멈춘다 | 이 스킬의 범위 밖이다. 한 언어는 한 실험에만 둔다 |
| ECR `tools` 이미지 없이 Fargate 실행 | 앱 태스크의 probe 사이드카와 부하 태스크가 이미지를 못 받아 기동하지 못한다 | 8단계대로 `tools` 이미지를 다시 빌드해 push한다 |
| 승인 없이 실행 | `run-all.sh`가 거부한다. 승인을 우회하면 규칙 위반이다 | 6단계의 `AskUserQuestion` 후 `approve.sh` |
| 에이전트 run 안에서 툴체인 설치 | 언어 간 환경 차이 | 실험자가 5단계에서 미리 설치한다 |
