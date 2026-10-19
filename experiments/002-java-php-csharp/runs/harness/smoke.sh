#!/bin/bash
# Phase 0 스모크: 조건별 모델 ID·인증·usage 기록 확인. 결과는 phase0.log, 세션은 본 계측에서 제외(삭제).
BASE="$(cd "$(dirname "$0")" && pwd)"
source "$BASE/bench.env"
source "$BASE/gate.sh"; require_approval || exit 1
OUT="$BASE/phase0.log"; mkdir -p "$BASE/smoke-tmp"
Q="Output exactly the string SMOKE-OK and nothing else."
fail=0
for v in RUN_BUDGET_USD EXP_BUDGET_USD; do
  [ -n "${!v:-}" ] || { echo "FAIL $v 미설정 — 비용 상한 없이 기동 금지 (EXP-042)" | tee -a "$OUT"; fail=1; }
done
# 비용 가드 실행 점검 (EXP-045: 빈 PRICE_IN으로 cost_guard.py가 예외를 내 가드가 0만 기록)
for c in $CONDITIONS; do
  e=$(env PRICE_IN="$PRICE_IN" PRICE_OUT="$PRICE_OUT" PRICE_CACHE_READ="$PRICE_CACHE_READ" PRICE_TIERS="$PRICE_TIERS" CONDITIONS="$CONDITIONS" \
      python3 "$BASE/cost_guard.py" "$BASE" "$HARNESS" "${c%%:*}-smoke" "$BASE/smoke-tmp/none" 2>&1) \
    || { echo "FAIL cost_guard.py 실행 오류(${c%%:*}) — 비용 상한이 작동하지 않는다: $e" | tee -a "$OUT"; fail=1; }
done
# 채점 교란 방지 (EXP-029 D-1: VS Code Live Preview가 127.0.0.1:3000 점유 → 거짓 음성)
busy=$(lsof -nP -iTCP -sTCP:LISTEN 2>/dev/null | awk 'NR>1{print $1" "$9}' | grep -E ':3[0-9]{3}$')
[ -n "$busy" ] && { echo "INFO 3000번대 포트 외부 LISTEN — 종료 불필요. 하네스는 빈 포트(PORT)를 주입하고 리포 소속 프로세스의 포트로만 채점한다. PORT를 무시하고 이 포트에 고정 바인딩한 앱은 기동 실패로 기각될 수 있다(산출물 결함, 부검 시 확인):"; echo "$busy"; } | tee -a "$OUT"
for c in $CONDITIONS; do
  m="${c#*:}"; echo "=== $m ($HARNESS) $(date '+%F %T') ===" >> "$OUT"
  r=$(cd "$BASE/smoke-tmp" && env -u ANTHROPIC_API_KEY -u ANTHROPIC_AUTH_TOKEN -u ANTHROPIC_BASE_URL \
        claude -p "$Q" --model "$m" --output-format json < /dev/null 2>&1)
  # 구독 인증 확인 (EXP-037: API 키 혼입으로 apiKeySource=ANTHROPIC_API_KEY)
  echo "$r" | grep -q '"apiKeySource":"none"' || { echo "FAIL $m — 구독 인증 아님: $(echo "$r" | grep -o '"apiKeySource":"[^"]*"')"; fail=1; }
  echo "$r" | tail -20 >> "$OUT"
  if echo "$r" | grep -q 'SMOKE-OK' && ! echo "$r" | grep -qiE '"is_error":true|ERROR:|"status":"ERROR"'; then echo "PASS $m"; else echo "FAIL $m"; fail=1; fi
done
rm -rf "$BASE/smoke-tmp/sessions"
exit $fail
