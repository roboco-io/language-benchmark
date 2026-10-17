#!/bin/bash
# 사용: nohup caffeinate -is bash $BASE/run-all.sh >/dev/null 2>&1 &
# 순차 실행(동시 실행 금지). 순서는 조건×언어×반복 목록을 RUN_ORDER_SEED(기본 20261018)로 결정적으로 섞은 것이다(R26).
# 목록은 첫 run 전에 run-order.txt로 남기고, 다시 실행하면(재개·보류 재시작) 그 파일을 그대로 쓴다.
# run 종료마다 세션 로그를 run별 디렉터리로 옮겨 usage 계측 범위를 분리한다.
BASE="$(cd "$(dirname "$0")" && pwd)"
source "$BASE/bench.env"
source "$BASE/gate.sh"; require_approval 2>>"$BASE/orchestrator.log" || { echo "=== 거부: 실행 승인 없음 또는 승인 뒤 설정 변경 : $(date '+%F %T') ===" >> "$BASE/orchestrator.log"; exit 1; }
LOG="$BASE/orchestrator.log"
log(){ echo "=== $* : $(date '+%F %T') ===" >> "$LOG"; }
source "$BASE/common.sh"   # archive_sessions, restart_held, hold_stop
run_one(){
  local rc held
  [ -f "$BASE/done-$1" ] && { log "skip $1"; return; }
  # 비용 상한으로 멈춘 실험은 남은 run을 시작하지 않는다 (EXP-042 재발 방지)
  [ -f "$BASE/budget-stop" ] && { log "skip $1 (budget-stop)"; return; }
  # 채점 정본·하네스 변조가 감지되면(tampered-<run>) 남은 run도 같은 하네스를 쓰므로 시작하지 않는다(R24)
  ls "$BASE"/tampered-* >/dev/null 2>&1 && { log "skip $1 (tampered: $(cd "$BASE" && ls tampered-* | tr '\n' ' '))"; return; }
  # 구독 한도로 보류된 run은 부분 산출물을 held/로 옮기고 iteration 1부터 다시 시작한다(R18, CLAUDE.md 서비스 장애 보류)
  held=$(restart_held "$1") && log "restart $1 from iteration 1 (보류 시도 보관: $held)"
  log "start $1"
  bash "$BASE/driver.sh" "$1"; rc=$?
  archive_sessions "$1"
  log "end $1 (exit $rc) | $(tail -1 "$BASE/metrics-$1.csv" 2>/dev/null)"
  # 75 = 구독 한도 보류. 남은 run도 같은 한도에 걸리므로 배치 전체를 멈춘다.
  [ "$rc" = 75 ] && hold_stop "$1"
}
ORDER="$BASE/run-order.txt"
if [ ! -s "$ORDER" ]; then
  python3 "$BASE/run_order.py" "${RUN_ORDER_SEED:-20261018}" "$CONDITIONS" "$LANGS" "$N" > "$ORDER.tmp" && mv "$ORDER.tmp" "$ORDER" \
    || { log "거부: run-order.txt 생성 실패"; exit 1; }
  log "run order written: $ORDER (seed ${RUN_ORDER_SEED:-20261018})"
fi
log "starting sequential runs ($HARNESS: $CONDITIONS / $LANGS / n=$N, order $ORDER)"
for r in $(cat "$ORDER"); do run_one "$r"; done
log "ALL RUNS FINISHED"
touch "$BASE/done-all"
