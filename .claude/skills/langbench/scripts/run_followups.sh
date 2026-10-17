#!/bin/bash
# 사용: nohup caffeinate -is bash $BASE/run_followups.sh >/dev/null 2>&1 &
# done-all 이후 실행. metrics의 마지막 게이트가 pass인 run마다 후속 과제(FOLLOWUP=1 driver.sh)를 순차 실행한다.
BASE="$(cd "$(dirname "$0")" && pwd)"
source "$BASE/bench.env"
source "$BASE/gate.sh"; require_approval 2>>"$BASE/orchestrator.log" || { echo "=== 거부: 실행 승인 없음 또는 승인 뒤 설정 변경 : $(date '+%F %T') ===" >> "$BASE/orchestrator.log"; exit 1; }
source "$BASE/common.sh"
LOG="$BASE/orchestrator.log"
log(){ echo "=== $* : $(date '+%F %T') ===" >> "$LOG"; }
[ -f "$BASE/done-all" ] || { log "거부: done-all 없음 — 본 과제 run이 끝나기 전에는 후속 과제를 시작하지 않는다"; exit 1; }
gate_of(){ # $1 run — metrics 마지막 데이터 행(숫자 iter로 시작)의 마지막 열
  grep '^[0-9]' "$BASE/metrics-$1.csv" 2>/dev/null | tail -1 | awk -F, '{print $NF}'
}
run_fu(){
  local run="$1" rc held
  [ "$(gate_of "$run")" = pass ] || { log "skip $run-fu (원 run 게이트 $(gate_of "$run"))"; return; }
  [ -f "$BASE/done-$run-fu" ] && { log "skip $run-fu"; return; }
  [ -f "$BASE/budget-stop" ] && { log "skip $run-fu (budget-stop)"; return; }
  ls "$BASE"/tampered-* >/dev/null 2>&1 && { log "skip $run-fu (tampered: $(cd "$BASE" && ls tampered-* | tr '\n' ' '))"; return; }
  # 구독 한도 보류 run은 app-<run>-fu까지 held/로 옮겨 원 run 앱에서 다시 복사해 시작한다(R18)
  held=$(restart_held "$run-fu") && log "restart $run-fu from iteration 1 (보류 시도 보관: $held)"
  log "start $run-fu"
  FOLLOWUP=1 bash "$BASE/driver.sh" "$run"; rc=$?
  archive_sessions "$run-fu"
  log "end $run-fu (exit $rc) | $(tail -1 "$BASE/metrics-$run-fu.csv" 2>/dev/null)"
  [ "$rc" = 75 ] && hold_stop "$run-fu"
}
log "starting follow-up runs ($HARNESS: $CONDITIONS / $LANGS / n=$N)"
# 본 과제와 같은 순서(run-order.txt, R26)로 돈다.
[ -s "$BASE/run-order.txt" ] || { log "거부: run-order.txt 없음 — run-all.sh가 만든 실행 순서가 필요하다"; exit 1; }
for r in $(cat "$BASE/run-order.txt"); do run_fu "$r"; done
log "ALL FOLLOW-UPS FINISHED"
touch "$BASE/done-followups"
