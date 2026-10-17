#!/bin/bash
# 사용: bash driver.sh <run>   (run = <조건>-<언어>-<n>, 예: sol-en-1)
#       FOLLOWUP=1 bash driver.sh <run>   후속 과제: app-<run>을 app-<run>-fu로 복사해 이어서 작업(모델은 원 run의 조건)
# 랄프 루프 1 run: iteration마다 에이전트 1회 호출 → measure.sh(호스트)·measure_docker.sh(Docker) 채점 → .ralph-done 게이트.
# claude-native 전용 driver (자매 리포 EXP-026 driver에서 이식).
RUN="$1"
BASE="$(cd "$(dirname "$0")" && pwd)"
source "$BASE/bench.env"
# shellcheck source=common.sh
source "$BASE/common.sh"   # harness_intact
COND="${RUN%%-*}"; REST="${RUN#*-}"; LANG_="${REST%%-*}"
MODEL=""
for c in $CONDITIONS; do [ "${c%%:*}" = "$COND" ] && MODEL="${c#*:}"; done
[ -n "$MODEL" ] || { echo "unknown condition $COND ($RUN)" >> "$BASE/driver.log"; exit 1; }
PROMPT="$BASE/PROMPT-$LANG_.md"
SRC_REPO="$BASE/app-$RUN"
if [ "${FOLLOWUP:-}" = 1 ]; then
  # 후속 과제: 원 run에서 모델·언어를 정하고, 이후 모든 산출물은 <run>-fu 이름을 쓴다.
  PROMPT="$BASE/PROMPT-followup.md"
  [ -d "$SRC_REPO" ] || { echo "no original app for follow-up ($RUN)" >> "$BASE/driver.log"; exit 1; }
  RUN="$RUN-fu"
  MAX_ITER="${FU_MAX_ITER:-$MAX_ITER}"; MAX_SEC="${FU_MAX_SEC:-$MAX_SEC}"
  HURL_DIRS="${HURL_DIRS:-$BASE/harness-hurl $BASE/followup-hurl}"
else
  [ -f "$PROMPT" ] || { echo "unknown language $LANG_ ($RUN)" >> "$BASE/driver.log"; exit 1; }
fi
[ -f "$PROMPT" ] || { echo "missing $(basename "$PROMPT") ($RUN)" >> "$BASE/driver.log"; exit 1; }

REPO="$BASE/app-$RUN"; MET="$BASE/metrics-$RUN.csv"; LOG="$BASE/ralph-run-$RUN.log"
[ -f "$BASE/done-$RUN" ] && exit 0
# 구독 한도 보류 run은 이어서 돌리지 않는다. run-all.sh·run_followups.sh가 부분 산출물을 held/로 옮기고 처음부터 다시 시작한다(R18).
[ -f "$BASE/hold-$RUN" ] && { echo "hold-$RUN 있음 — 오케스트레이터가 처음부터 다시 시작해야 함 ($RUN)" >> "$BASE/driver.log"; exit 75; }
# 채점 정본·하네스 스크립트가 setup 때와 다르면 시작하지 않는다(R24)
harness_intact || { touch "$BASE/tampered-$RUN"; echo "=== RUN $RUN 거부: 채점 정본·하네스 해시 불일치($BASE/checksums.md5): $(date '+%F %T') ===" >> "$BASE/driver.log"; exit 1; }

# 빈 포트 선택: 3000번대 고정 포트가 외부 프로세스(VS Code Live Preview 등)와 충돌하지 않도록 (EXP-029 D-1, EXP-031)
free_port() {
  local p
  for _ in $(seq 1 50); do
    p=$(( 40000 + RANDOM % 10000 ))
    lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1 || { echo "$p"; return; }
  done
}

# 채점 Hurl 디렉터리(공백 구분). 본 과제는 정본 13파일, 후속 과제는 followup-hurl(1파일)을 더해 14파일이다.
HURL_DIRS="${HURL_DIRS:-$BASE/harness-hurl}"
EXPECT_FILES=0
for d in $HURL_DIRS; do EXPECT_FILES=$(( EXPECT_FILES + $(ls "$d"/*.hurl 2>/dev/null | wc -l) )); done
# 기대 파일이 0이면 "0 = 0"으로 게이트가 통과해 버리므로 채점 디렉터리가 비어 있으면 시작하지 않는다.
[ "$EXPECT_FILES" -gt 0 ] || { echo "no hurl files in HURL_DIRS ($RUN)" >> "$BASE/driver.log"; exit 1; }

source "$BASE/watchdog.sh"
STALL_SEC="${STALL_SEC:-300}"   # iteration 정체 한도(초). 세션·로그·앱 파일이 이 시간 동안 갱신되지 않으면 iteration 종료 (EXP-039)

# 비용 가드 (EXP-042 재발 방지, 2026-10-10): 진행 중 run과 실험 전체의 추정 비용을 30초마다 확인한다.
# 추정은 cost_guard.py — 제공자 보고 usage와 대화 기록 바이트 기반 하한 중 큰 값(보고 누락 대비).
export START_SEC DOCKER_BUILD_SEC PRICE_IN PRICE_OUT PRICE_CACHE_READ PRICE_TIERS CONDITIONS
cost_path() {  # 진행 중 run의 세션 기록 위치
  echo "$HOME/.claude/projects/$(echo "$REPO" | sed 's|[/.]|-|g')"
}
budget_check() {  # 상한 초과 시 사유를 출력, 아니면 아무것도 출력하지 않음
  local cur prev p others=()
  # 추정 실패는 0으로 보지 않고 run을 멈춘다(fail-closed). EXP-045에서 예외를 0으로 삼켜 가드가 무력화됐다.
  cur=$(python3 "$BASE/cost_guard.py" "$BASE" "$HARNESS" "$RUN" "$(cost_path)" 2>>"$BASE/cost-guard.err") \
    || { echo "$(date '+%F %T'),$RUN,ERR," >> "$BASE/cost-guard.csv"; echo "비용 추정 실패(cost-guard.err 참조)"; return; }
  # 구독 한도로 보류돼 held/에 옮긴 시도의 세션도 실험 누적 비용에 넣는다(R18).
  for p in "$BASE"/sessions-* "$BASE"/held/*/sessions-*; do [ -d "$p" ] && [ "$p" != "$BASE/sessions-$RUN" ] && others+=("$p"); done
  prev=0; [ ${#others[@]} -gt 0 ] && { prev=$(python3 "$BASE/cost_guard.py" "$BASE" "$HARNESS" ALL "${others[@]}" 2>>"$BASE/cost-guard.err") \
    || { echo "$(date '+%F %T'),$RUN,$cur,ERR" >> "$BASE/cost-guard.csv"; echo "비용 추정 실패(cost-guard.err 참조)"; return; }; }
  echo "$(date '+%F %T'),$RUN,$cur,$prev" >> "$BASE/cost-guard.csv"
  awk -v c="$cur" -v p="${prev:-0}" -v rb="$RUN_BUDGET_USD" -v eb="$EXP_BUDGET_USD" 'BEGIN{
    if (c+0 >= rb+0) printf "run 추정 $%.2f ≥ RUN_BUDGET_USD $%s", c, rb;
    else if (c+p >= eb+0) printf "실험 누적 추정 $%.2f ≥ EXP_BUDGET_USD $%s", c+p, eb }'
}

# 정체 판단에 쓰는 갱신 감시 경로: 실행 로그·앱 작업 트리·하네스별 세션 기록
watch_paths() {
  echo "$LOG"; echo "$REPO"
  echo "$HOME/.claude/projects/$(echo "$REPO" | sed 's|[/.]|-|g')"
}

# 구독 한도·과부하 메시지 패턴(대소문자 무시 ERE, R18). 실제 문구는 Phase 0에서 확인해 phase0.md에 기록한다.
LIMIT_PATTERNS='usage limit|limit reached|hit your limit|rate[ _-]?limit|API Error: 429|overloaded'
# limit_hit <iteration 출력 시작 바이트> <iteration 시작 표시 파일> <exit> — 한도 메시지를 찾으면 그 줄을 출력한다.
# 오탐을 줄이려고 범위를 좁힌다. claude stdout·stderr는 비정상 종료(0·124·125·126 외)일 때만 본다. 정상 종료 출력에는
# 에이전트가 쓴 요약(예: "429 rate limit 구현")이 있을 수 있기 때문이다. 세션 jsonl은 이 iteration에 갱신된 파일의
# API 오류 기록(isApiErrorMessage 또는 model "<synthetic>")만 본다. 서브에이전트 세션도 포함한다.
limit_hit() {
  local off="$1" marker="$2" rc="$3" line
  case "$rc" in 0|124|125|126) ;; *)
    line=$(tail -c +"$((off + 1))" "$LOG" | grep -iE -m1 "$LIMIT_PATTERNS")
    [ -n "$line" ] && { echo "stdout: ${line:0:300}"; return; } ;;
  esac
  find "$(cost_path)" -name '*.jsonl' -newer "$marker" 2>/dev/null | LIMIT_PATTERNS="$LIMIT_PATTERNS" python3 -c '
import json, os, re, sys
pat = re.compile(os.environ["LIMIT_PATTERNS"], re.I)
for path in sys.stdin.read().splitlines():
    try:
        lines = open(path, errors="replace").read().splitlines()
    except OSError:
        continue
    for line in lines:
        try:
            r = json.loads(line)
        except ValueError:
            continue
        m = r.get("message") if isinstance(r, dict) else None
        if not isinstance(m, dict) or not (r.get("isApiErrorMessage") or m.get("model") == "<synthetic>"):
            continue
        c = m.get("content")
        text = " ".join(x.get("text", "") for x in c if isinstance(x, dict)) if isinstance(c, list) else str(c)
        if pat.search(text):
            print("session: " + " ".join(text.split())[:300])
            sys.exit()
'
}

# 이전 구간들이 쓴 wall-clock 초 합(R19). scripts/runs_row.py run_minutes와 같은 구간 규칙이다.
# 구간 = driver.log의 `=== RUN <run> (…) start:` ~ `=== RUN <run> finished:`. `=== RUN <run> held:` 줄은 그때까지의 구간을 모두 버린다
# (보류 run은 처음부터 다시 돌리므로). finished 줄 없이 끝난 구간(driver 강제 종료)은 그 구간의 마지막 metrics 행 시각까지로 센다.
prev_elapsed() {
  python3 - "$BASE/driver.log" "$RUN" "$MET" <<'EOF'
import csv, re, sys
from datetime import datetime
F = "%Y-%m-%d %H:%M:%S"
TS = r"(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)"
log, run, met = sys.argv[1:4]
try:
    text = open(log).read()
except OSError:
    print(0)
    sys.exit()
name = re.escape(run)
start_re = re.compile(r"^=== RUN " + name + r" \(.*\) start: " + TS + " ===")
end_re = re.compile(r"^=== RUN " + name + r" finished: " + TS + " ")
held_re = re.compile(r"^=== RUN " + name + r" held: ")
segs = []
for line in text.splitlines():
    if held_re.match(line):
        segs = []
    elif start_re.match(line):
        segs.append([datetime.strptime(start_re.match(line).group(1), F), None])
    elif end_re.match(line) and segs and segs[-1][1] is None:
        segs[-1][1] = datetime.strptime(end_re.match(line).group(1), F)
stamps = []
try:
    for row in csv.reader(open(met)):
        try:
            stamps.append(datetime.strptime(row[1], F))
        except (IndexError, ValueError):
            pass
except OSError:
    pass
total = 0.0
for k, (start, end) in enumerate(segs):
    if end is None:
        nxt = segs[k + 1][0] if k + 1 < len(segs) else datetime.max
        end = max([t for t in stamps if start <= t < nxt] or [start])
    total += (end - start).total_seconds()
print(int(total))
EOF
}

invoke_agent() {
  # 구독 인증 강제: 셸에 API 키·토큰이 있으면 구독 로그인보다 우선해 API 과금·5분 캐시 TTL이 된다 (EXP-037)
  env -u ANTHROPIC_API_KEY -u ANTHROPIC_AUTH_TOKEN -u ANTHROPIC_BASE_URL \
    claude -p "$(cat "$REPO/PROMPT.md")" --model "$MODEL" --dangerously-skip-permissions
}

if [ "${FOLLOWUP:-}" = 1 ] && [ ! -d "$REPO" ]; then
  # 최초 실행 시에만 원 run 앱을 .git 포함 복사한다. 원본은 건드리지 않고 완료 선언만 지운다.
  cp -R "$SRC_REPO" "$REPO" && rm -f "$REPO/.ralph-done"
fi
mkdir -p "$REPO"
cp "$PROMPT" "$REPO/PROMPT.md"
git -C "$REPO" rev-parse --git-dir >/dev/null 2>&1 || git -C "$REPO" init -q
cd "$REPO" || exit 1
DONE_ITER=$(grep -c '^[0-9]' "$MET" 2>/dev/null | head -1)
DONE_ITER=${DONE_ITER:-0}
# 재개한 run도 run 전체 상한은 MAX_SEC 하나다. 이번 구간 예산 = MAX_SEC - 이전 구간 경과(R19).
PREV_SEC=$(prev_elapsed) || PREV_SEC=0
REMAIN=$(( MAX_SEC - ${PREV_SEC:-0} ))
START=$(date +%s)
# 에이전트 세션이 띄우는 서버도 빈 포트를 쓰도록 run 단위로 PORT 주입 (PROMPT는 불변)
export PORT="$(free_port)"
echo "=== RUN $RUN ($HARNESS $MODEL, $(basename "$PROMPT"), PORT=$PORT, budget ${REMAIN}s of ${MAX_SEC}s) start: $(date '+%F %T') ===" >> "$BASE/driver.log"
for i in $(seq $((DONE_ITER + 1)) "$MAX_ITER"); do
  if [ "$REMAIN" -le 0 ] || [ $(( $(date +%s) - START )) -gt "$REMAIN" ]; then
    echo "=== [$RUN] wall-clock cap ${MAX_SEC}s reached before iteration $i (이번 구간 예산 ${REMAIN}s): $(date '+%F %T') ===" >> "$LOG"
    echo "timeout,$(date '+%F %T')" >> "$MET"
    break
  fi
  OVER=$(budget_check)
  if [ -n "$OVER" ]; then
    echo "=== [$RUN] 비용 상한 도달 — iteration $i 미실행: $OVER : $(date '+%F %T') ===" >> "$LOG"
    echo "budget,$(date '+%F %T')" >> "$MET"; touch "$BASE/budget-stop"
    break
  fi
  echo "=== [$RUN] iteration $i start: $(date '+%F %T') ===" >> "$LOG"
  OFF=$(wc -c < "$LOG" | tr -d ' ')
  ITER_MARK="$BASE/.iter-start-$RUN"; touch "$ITER_MARK"
  WATCH=(); while IFS= read -r w; do WATCH+=("$w"); done < <(watch_paths)
  run_with_watchdog "$STALL_SEC" $((START + REMAIN)) "${WATCH[@]}" -- invoke_agent < /dev/null >> "$LOG" 2>&1
  EXIT=$?
  HIT=$(limit_hit "$OFF" "$ITER_MARK" "$EXIT" | head -1)
  rm -f "$ITER_MARK"
  if [ -n "$HIT" ]; then
    # 구독 한도: 이 iteration은 metrics에 남기지 않고 done도 만들지 않는다. 오케스트레이터가 배치를 멈춘다(R18).
    NOW="$(date '+%F %T')"
    printf '%s\n%s\n' "$NOW" "$HIT" > "$BASE/hold-$RUN"
    echo "=== [$RUN] HOLD: subscription limit — iteration $i 미기록 (exit $EXIT): $HIT : $NOW ===" >> "$LOG"
    echo "=== RUN $RUN held: $NOW | $HIT ===" >> "$BASE/driver.log"
    exit 75
  fi
  [ -n "$WATCHDOG_REASON" ] && echo "=== [$RUN] watchdog: iteration $i 종료 — $WATCHDOG_REASON (exit $EXIT, 124=정체·125=run 상한·126=비용 상한) ===" >> "$LOG"
  echo "=== [$RUN] iteration $i end (exit $EXIT): $(date '+%F %T') ===" >> "$LOG"
  RESULT=$(bash "$BASE/measure.sh" "$REPO" $HURL_DIRS)
  # Docker 채점은 호스트가 기대 파일 수를 모두 통과한 iteration에서만 실행한다(빌드 비용 절약).
  DRES=","
  [ "${RESULT%%,*}" = "$EXPECT_FILES" ] && DRES=$(bash "$BASE/measure_docker.sh" "$REPO" "$RUN-i$i" $HURL_DIRS)
  CLAIM=0; [ -f "$REPO/.ralph-done" ] && CLAIM=1
  GATE=na
  # 게이트 판정 전 무결성 검사(R24): 채점 정본·스크립트가 바뀌었으면 이 iteration 채점을 믿을 수 없으므로 pass 없이 run을 끝낸다.
  if ! harness_intact; then
    GATE=tampered
    rm -f "$REPO/.ralph-done"; touch "$BASE/tampered-$RUN"
    echo "=== [$RUN] GATE tampered: 채점 정본·하네스 해시 불일치 iter $i ===" >> "$LOG"
  elif [ "$CLAIM" = 1 ]; then
    if [ "${RESULT%%,*}" = "$EXPECT_FILES" ] && [ "${DRES%%,*}" = "$EXPECT_FILES" ]; then
      GATE=pass
    else
      GATE=rejected
      rm -f "$REPO/.ralph-done"
      echo "=== [$RUN] GATE 기각 (host $RESULT, docker $DRES) iter $i ===" >> "$LOG"
    fi
  fi
  # metrics 열: iter,종료시각,exit,host_succ,host_reqs,docker_succ,docker_reqs,완료 선언,게이트
  echo "$i,$(date '+%F %T'),$EXIT,$RESULT,$DRES,$CLAIM,$GATE" >> "$MET"
  [ "$GATE" = tampered ] && break
  [ -f "$REPO/.ralph-done" ] && break
  if [ "$EXIT" = 126 ]; then
    echo "budget,$(date '+%F %T')" >> "$MET"; touch "$BASE/budget-stop"
    break
  fi
done
touch "$BASE/done-$RUN"
echo "=== RUN $RUN finished: $(date '+%F %T') | $(tail -1 "$MET") ===" >> "$BASE/driver.log"
