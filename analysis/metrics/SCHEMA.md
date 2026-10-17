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
