# 실행 전 점검 보고서 (preflight)

- 하네스: `/Users/dohyunjung/experiments/langbench-exp002` · HARNESS `claude-native` · 조건 2개 × 언어 3 × n=3 = run 18개
- 상한: run당 40분 · 비용 run당 $15 · 실험 $200 → **최악 총액 $200** (상한이 강제되는 경우)
- 적합성 기준: 입력 컨텍스트 256,000 이상 적합, 200,000 이상 주의, 미만 부적합. 근거: 과거 완주 run 최대 프롬프트 p50 96,524 · p90 152,713 · 최대 174,422 토큰 (61 run, 2026-10-10 측정)

| 조건 | 모델 | 제공자 | 컨텍스트 | 최대 출력 | 적합성 | 과금 | 입력/출력 단가(100만 토큰) | 구간 단가 | run당 추정 p50 / p90 |
|---|---|---|---|---|---|---|---|---|---|
| sonnet55 | `claude-sonnet-5-5` | Anthropic (Claude Code 구독) | 1,000,000 | 128,000 | **적합** | subscription | $2.0/$10.0 | 없음 | $4.75 / $17.19 |
| opus55 | `claude-opus-5-5` | Anthropic (Claude Code 구독) | 1,000,000 | 128,000 | **적합** | subscription | $4.0/$20.0 | 없음 | $9.49 / $34.38 |

- run당 추정은 과거 완주 run의 총 입력(p50 2,217,287 · p90 8,278,631)과 출력(p50 31,102 · p90 63,420)에 캐시 할인 없이 단가를 곱한 값이다. 입력 단가는 최대 프롬프트 p90이 속한 구간을 적용했다. 완주하지 못하고 상한까지 도는 run은 이보다 클 수 있으며, 그 경우 비용 상한이 run을 멈춘다.
- 실험 전체 추정(p50 기준): $128.13 · p90 기준: $464.17
- 출처: `claude-sonnet-5-5` 컨텍스트 claude-api skill Current Models (cached 2026-10-06), 단가 claude-api skill: Current Models table + prompt-caching.md (5m write 1.25x); platform.claude.com/docs/en/about-claude/pricing (2026-10-18 확인); `claude-opus-5-5` 컨텍스트 claude-api skill Current Models (cached 2026-10-06), 단가 claude-api skill: Current Models table + prompt-caching.md (5m write 1.25x); platform.claude.com/docs/en/about-claude/pricing (2026-10-18 확인)

다음 단계: 이 보고서를 사용자에게 보여 주고 AskUserQuestion으로 진행 여부를 묻는다. 사용자가 진행을 고른 경우에만 `bash approve.sh "<답변 원문>"`을 실행한다.
