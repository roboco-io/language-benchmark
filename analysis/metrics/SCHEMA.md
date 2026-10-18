# 지표 스키마 (`runs.csv`)

`runs.csv`는 run 하나당 한 행이다. `scripts/build_dashboard_data.py`가 이 파일을 언어 × 모델로 집계해 `dashboard/data.js`를 만든다. 지표 목록과 표시 이름의 정본은 `scripts/metrics.py`이며, 열을 바꾸면 이 문서와 함께 고친다.

**빈 칸은 미측정이다.** 0으로 채우지 않는다. 0은 실제로 측정한 0(예: 취약점 0건)만 뜻한다.

## 열

| 열 | 단위 | 뜻 |
|---|---|---|
| `exp`, `run`, `rep` | | 실험 ID(`EXP-001`), run ID, 반복 번호 |
| `language` | | `typescript`, `python`, `go`, `rust`, `cpp` 중 하나 |
| `model` | | `sonnet55`(claude-sonnet-5-5), `opus55`(claude-opus-5-5) |
| `pass` | 0/1 | 호스트(`start.sh`)와 Docker 양쪽 Hurl 154요청 통과 여부 |
| `wall_min` | 분 | 첫 프롬프트부터 합격 판정까지 벽시계 시간 |
| `iterations` | 회 | 합격까지 Ralph loop iteration 수 |
| `interventions` | 회 | 실험자 수동 개입 수 |
| `token_proxy` | K 토큰 | `input + output + cache_creation`(message.id 중복 제거). 청구액 아님 |
| `cost_usd` | USD | API 공개 단가 환산 추정. 구독 실행이므로 실제 지출 아님 |
| `rps` | req/s | 고정 동시성 k6 혼합 시나리오 처리량(3회 중앙값) |
| `p50_ms`, `p95_ms`, `p99_ms` | ms | 같은 측정의 지연 백분위 |
| `error_rate` | % | 같은 측정의 실패 요청 비율 |
| `idle_rss_mb`, `peak_rss_mb` | MB | 기동 후 시드 전 RSS, 부하 중 최대 RSS(`docker stats`) |
| `soak_error_rate`, `soak_mem_growth_mb` | %, MB | 장시간 부하의 에러율과 시작 대비 종료 시점 메모리 증가 |
| `loc`, `deps` | 줄, 개 | 테스트 제외 코드 줄 수, 매니페스트의 직접 의존성 수 |
| `build_sec`, `image_mb`, `cold_start_ms` | s, MB, ms | 캐시 없는 Docker 빌드 시간, 최종 이미지 크기, 컨테이너 기동부터 첫 정상 응답까지 |
| `vulns` | 건 | Trivy 이미지 스캔 + 언어별 스캐너(`npm audit`, `pip-audit`, `govulncheck`, `cargo audit`)의 고유 취약점 수 |
| `followup_pass`, `followup_min`, `followup_token_proxy` | 0/1, 분, K | 후속 과제(코멘트 수정 API) 성공 여부, 시간, 토큰 대리지표 |

## 집계 규칙

셀은 언어 × 모델(`sonnet55`, `opus55`)과 두 모델을 합친 `all`이다.

- `pass_rate` = 합격 run 수 / 전체 run 수.
- `cost_per_pass` = 전체 run(실패 포함)의 `cost_usd` 합 / 합격 run 수. 합격이 0건이거나 비용이 하나라도 미측정이면 값을 내지 않는다(실험 품질 규칙 5절).
- 나머지 지표는 **합격 run 중 값이 있는 것의 중앙값**이다. 표본 수는 `<지표>_n`에 기록하고 대시보드에 "측정 run 수/전체 run 수"(예: `0/3`, 두 모델 합산이면 `/6`)로 표시한다. 합격 run이 없으면 `0/3`처럼 표시해 "run 없음"과 구분한다. 실패 run의 결과물 수치는 쓰지 않는다.
- `followup_pass`는 합격 run 중 후속 과제를 실행한 run의 성공 비율이다.
- 좋은 방향: `pass_rate`, `rps`, `followup_pass`는 클수록 좋고 나머지는 작을수록 좋다.

## 기본 가중치

대시보드의 종합 점수 기본 가중치는 `METRICS`의 24개 지표 모두 1(균등)이다. 실험 전에 특정 지표를 더 중시할 근거가 없고, 대시보드가 이미 균등 가중을 기본으로 구현하고 있어 문서와 화면이 일치하기 때문이다. 이 값은 설계자 판단으로 정했다. 대시보드의 초기화와 리셋 버튼은 둘 다 `dashboard/score.js`의 `defaultWeights(metrics)`를 호출하므로 기본값은 한 곳에서만 정의된다.

### 목적별 프리셋 (2026-10-18 추가)

EXP-001 결과를 본 뒤, 24개 슬라이더는 쓰기 어렵고 지표 대부분이 결과물 지표라 균등 가중이 런타임 성능이 좋은 언어에 유리하다는 문제가 드러났다. 그래서 대시보드는 목적별 프리셋을 기본 조작으로 제공하고 슬라이더는 "고급"으로 접는다. 프리셋 정의는 `dashboard/score.js`의 `GROUP_PRESETS`와 `presetWeights()` 한 곳에 있다.

| 프리셋 | 질문 | 가중치 |
|--------|------|--------|
| `prereg` 사전 등록 | EXP-001 판정 기준 재현 | 24개 지표 모두 1 |
| `speed` 구현 속도 | 기능을 가장 빠르고 싸게 만드는 언어 | wall_min 3, cost_per_pass 2, followup_min 2, token_proxy 1, followup_token_proxy 1 |
| `perf` 성능·인프라 비용 | 같은 트래픽을 더 적은 서버로 처리하는 언어 | rps 3, p95_ms 2, peak_rss_mb 2, p99_ms·idle_rss_mb·cold_start_ms·image_mb·soak_mem_growth_mb·error_rate·soak_error_rate 각 1 |
| `maintain` 유지보수·보안 | 유지·변경하기 쉬운 언어 | vulns 2, followup_pass 2, deps·loc·followup_min·build_sec 각 1 |
| `balanced` 균형 | 종합 | speed·perf·maintain 묶음 안 가중치를 합 1로 나눠 더한다(세 묶음이 1/3씩 기여) |

프리셋은 해석 보조이며 EXP-001 판정을 바꾸지 않는다. EXP-001 판정은 사전 등록대로 `prereg`(24개 균등) 기준이고, 대시보드 기본 선택도 `prereg`다. 다음 실험부터 주 지표로 `balanced`를 쓸지는 그 실험의 설계 문서에서 실행 전에 정한다.

대시보드는 현재 보기(단일 모델, 또는 두 모델 비교)에서 모든 언어가 같은 값인 지표의 열을 숨기고 숨긴 지표 이름을 범례에 적는다(`constantMetrics()`). 숨김은 표시에만 적용하며 종합 점수는 선택한 가중치대로 계산한다. 상수 지표는 모든 언어에 같은 값을 더하므로 순위는 바꾸지 않는다.

EXP-001 실행을 시작한 뒤에는 이 기본값을 바꾸지 않는다. 결과를 본 뒤 가중치를 바꾸면 원하는 순위를 만드는 방향으로 조정할 위험이 있기 때문이다. 다른 가중치를 쓰려면 새 실험 번호를 정하고, 그 실험을 실행하기 전에 가중치와 근거를 실험 README에 사전 등록한다. 화면에서 사용자가 슬라이더로 바꾼 가중치는 해석 보조이며 기본값을 바꾸지 않는다.

## 측정 정의

모든 정의는 언어 간에 같은 조건으로 적용한다. 아래 값 중 하네스 구현 과제(Task 5-8)에서 확정되는 항목은 해당 과제의 정의를 옮긴 것이며, 구현 값이 바뀌면 그 과제의 커밋에서 이 절도 함께 고친다.

**컨테이너 제한.** 런타임 측정(`rps`, 지연, 메모리, soak, `cold_start_ms`)용 컨테이너는 모든 언어에서 `--cpus=2 --memory=1g`로 실행한다. Docker Desktop 할당이 6 CPU·7.7 GiB이므로 k6가 쓸 CPU 4개를 남기기 위한 값이다.

**메모리 지표의 한계.** `idle_rss_mb`와 `peak_rss_mb`는 `docker stats`가 보고하는 컨테이너 cgroup 메모리 사용량(`MemUsage`)이다. 프로세스 하나의 엄밀한 RSS가 아니다. 컨테이너 안의 모든 프로세스와 커널 페이지 캐시 일부가 포함될 수 있으므로, 언어 간 상대 비교에만 쓰고 절대값으로 해석하지 않는다. `soak_mem_growth_mb`는 같은 방식으로 잰 soak 종료 시점 값에서 시작 시점 값을 뺀 값(end − start)이다.

**k6 시나리오.** 시드는 사용자 50명, 글 500개, 태그 풀 20개(글당 태그 3개), 앞의 글 100개에 각 댓글 5개, 팔로우 250건, 즐겨찾기 250건이다. 부하 스크립트 `assets/k6/load.js`는 VU 32개로 다음 비율의 요청을 섞는다: 글 목록 35%, 글 단건 20%, 태그 10%, 피드 10%, 댓글 15%, 쓰기 10%(글 작성·즐겨찾기·댓글 작성이 반복 비율 4:3:3). 쓰기는 모두 같은 VU의 사용자로 곧바로 되돌리는 요청 쌍이다. 글은 작성 후 그 글을 삭제하고, 댓글은 작성 후 그 댓글을 삭제하며, 즐겨찾기는 favorite 후 unfavorite한다. 그래서 요청 수 기준 쓰기 비율은 8:6:6이고, 처리량이 높은 서버일수록 데이터가 커지는 효과 없이 부하 3회와 soak의 데이터 크기가 시드 상태로 유지된다(R23). 측정 순서는 워밍업 15초 후 30초 부하 3회이며, `rps`는 3회의 중앙값이다. 최대 메모리(`peak_rss_mb`)는 이 부하 중에 잰다. 이어서 600초 soak를 실행해 `soak_error_rate`와 `soak_mem_growth_mb`를 얻는다.

**빌드와 기동.** `build_sec`와 `image_mb`는 게이트가 채점한 앱 작업 트리를 임시 디렉터리로 내보내(`git ls-files -co --exclude-standard`, 즉 `.gitignore`가 가리는 의존성·빌드 산출물·DB 제외) 캐시 없이(no-cache) Docker 빌드한 시간과 최종 이미지 크기다. 내보낸 원본의 HEAD sha, 미커밋 변경 줄 수, 파일 수는 `runtime-<run>/source.txt`에 남는다. `cold_start_ms`는 컨테이너 기동부터 첫 정상 응답까지이고, `idle_rss_mb`는 기동 후 시드 전에 잰다.

**`loc`.** scc `--format json` 결과에서 주 언어의 코드 줄(Code)만 합산한다. 주 언어는 typescript는 `TypeScript`·`JavaScript`, python은 `Python`, go는 `Go`, rust는 `Rust`, cpp는 `C++`·`C++ Header`·`C Header`다. scc 호출 시 `--exclude-dir test,tests,__tests__,hurl,node_modules,target,build,.venv,vendor`와 `--not-match '(_test\.go|test_.*\.py|.*_test\.py|\.(test|spec)\.[jt]s|_test\.(cc|cpp))$'`로 테스트와 의존성·빌드 디렉터리를 뺀다. 구현은 `runtime_parse.loc`이다.

**`deps`.** 매니페스트의 직접 런타임 의존성 수이다(`runtime_parse.deps`). 매니페스트가 없으면 빈 칸이다.

| 언어 | 계산 방식 |
|---|---|
| typescript | `package.json`의 `dependencies` 키 수(`devDependencies` 제외) |
| python | `pyproject.toml`의 `[project] dependencies` 항목 수. 없으면 `requirements.txt`에서 주석·빈 줄·`-r` 줄을 뺀 줄 수 |
| go | `go.mod` `require` 중 `// indirect`가 아닌 모듈 수 |
| rust | `Cargo.toml`의 `[dependencies]` 키 수 |
| cpp | `CMakeLists.txt`의 `find_package(X`, `FetchContent_Declare(X`, `pkg_check_modules(… X`에서 고유 이름 수. `Threads`는 뺀다 |

**`vulns`.** 필요한 보고서가 모두 있을 때만 값을 낸다. 하나라도 없으면 빈 칸이다. 필요한 보고서는 Trivy 이미지 스캔과 언어별 스캐너이며, typescript는 `npm audit`, python은 `pip-audit`, go는 `govulncheck`, rust는 `cargo audit`이다. cpp는 Trivy만 쓴다. 각 보고서에서 ID와 alias를 뽑고, alias로 서로 연결되는 ID를 한 묶음으로 보아 묶음 수를 센다(`runtime_parse.vulns`). 추출 위치는 다음과 같다.

- Trivy: `Results[].Vulnerabilities[].VulnerabilityID`
- npm: `vulnerabilities.*.via[]` 중 dict인 항목의 `url` 끝에 있는 GHSA ID
- pip-audit: `dependencies[].vulns[].id`와 `aliases`
- govulncheck: JSON 스트림의 `osv.id`와 `osv.aliases`. 호출 경로가 있는 `finding`의 osv만 센다. 종료 코드가 0·3이 아니거나 스트림에 `config` 메시지가 없으면 보고서가 없는 것으로 본다(빈 칸)
- cargo audit: `vulnerabilities.list[].advisory.id`와 `aliases`

**`wall_min`.** `driver.log`의 run 구간(`=== RUN <run> (…) start:`부터 `=== RUN <run> finished:`까지)을 모두 합친 시간이다(분, 소수 첫째 자리). 재개된 run은 구간마다 더하고 마지막 구간은 `pass` 행 종료 시각까지 센다. 앞 구간에 finished 줄이 없으면 빈 칸이다. 구독 한도로 보류된 시도(`=== RUN <run> held:` 줄 앞의 구간)는 처음부터 다시 돌리므로 버린다. 불합격 run은 빈 칸이다. `followup_min`도 `<run>-fu` 구간에 같은 규칙을 쓴다.
