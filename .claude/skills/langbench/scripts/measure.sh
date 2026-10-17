#!/bin/bash
# 계측 v5: 리포의 start.sh 기동 → 리포 소속 리스닝 포트 자동 탐지 → 공식 Hurl 채점
# 사용: measure.sh <repo> <hurl-dir>...   (채점 대상은 각 디렉터리의 *.hurl)
# stdout: "succ_files,exec_reqs"   start.sh가 없으면 "0,0"
# 환경변수: START_SEC(기동 대기 한도, 기본 600)
# EXP-016/021 measure v4 공통형. hurl 절대 경로 고정
#   (EXP-020 게이트 오검 — @hurl/cli npm shim이 PATH에서 실 Hurl을 가린 사례 재발 방지)
BASE="$(cd "$(dirname "$0")" && pwd)"
REPO="$1"; shift
[ -d "$REPO" ] && REPO="$(cd "$REPO" && pwd -P)"   # lsof가 보고하는 실경로와 맞춘다(macOS /var → /private/var)
START_SEC="${START_SEC:-600}"
HURL_FILES=()
for d in "$@"; do for f in "$d"/*.hurl; do [ -f "$f" ] && HURL_FILES+=("$f"); done; done
HURL_BIN="/opt/homebrew/bin/hurl"
# 하네스 안의 Hurl로 채점하면(driver·독립 재검증) 먼저 정본·스크립트 해시를 대조한다(R24). 다르면 "tampered,0", 종료 코드 3.
# shellcheck source=common.sh
source "$BASE/common.sh"
if grades_from_base "$@" && ! harness_intact; then
  echo "채점 정본·하네스 해시 불일치($BASE/checksums.md5) — 채점하지 않음" >&2; echo "tampered,0"; exit 3
fi

# 프로세스가 리포 소속인지: command에 리포 경로 포함 OR CWD가 리포 안
belongs_to_repo() {
  local pid="$1" cwd cmd
  # 경로 경계를 지킨다: app-x-1이 app-x-1-fu의 프로세스를 소속으로 오판하지 않게 "$REPO/"·"$REPO "·끝에서만 일치시킨다
  cmd=$(ps -p "$pid" -o command= 2>/dev/null)
  case "$cmd" in *"$REPO/"*|*"$REPO "*|*"$REPO") return 0;; esac
  cwd=$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -1)
  case "$cwd" in "$REPO"|"$REPO"/*) return 0;; esac
  return 1
}

# 서버 프로세스 트리 전체 종료 — 상대 경로 watcher(tsx watch 등)는 pkill -f "$REPO"로 안 잡힌다 (EXP-027 sol-en-2 행)
kill_tree() {
  local c
  for c in $(pgrep -P "$1" 2>/dev/null); do kill_tree "$c"; done
  kill -9 "$1" 2>/dev/null
}

cleanup() {
  [ -n "$SRV" ] && kill_tree "$SRV"
  for pid in $(lsof -nP -iTCP -sTCP:LISTEN -t 2>/dev/null); do
    belongs_to_repo "$pid" && kill -9 "$pid" 2>/dev/null
  done
  pkill -9 -f "$REPO/" 2>/dev/null
  sleep 1
  pkill -9 -f "$REPO/" 2>/dev/null
}

# 빈 포트 선택: 3000번대 고정 포트가 외부 프로세스(VS Code Live Preview 등)와 충돌하지 않도록 (EXP-029 D-1, EXP-031)
free_port() {
  local p
  for _ in $(seq 1 50); do
    p=$(( 40000 + RANDOM % 10000 ))
    lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1 || { echo "$p"; return; }
  done
}

# measure.sh의 조상 중 하나를 조상으로 둔(또는 그 자체인) 프로세스인지.
# driver.sh가 리포 안에서 measure.sh를 실행하므로 driver와 그 자손(watchdog 등)은 리포 소속 서버가 아니다.
# start.sh가 죽은 뒤 남은 백그라운드 서버는 init(ppid 1)에 입양되므로 이 사슬에 걸리지 않는다.
is_self_related() {
  local p="$1"
  while [ -n "$p" ] && [ "$p" -gt 1 ] 2>/dev/null; do
    case " $ANCESTORS " in *" $p "*) return 0;; esac
    p=$(ps -o ppid= -p "$p" 2>/dev/null | tr -d ' ')
  done
  return 1
}
ANCESTORS=""
_a=$$; while [ -n "$_a" ] && [ "$_a" -gt 1 ] 2>/dev/null; do ANCESTORS="$ANCESTORS $_a"; _a=$(ps -o ppid= -p "$_a" 2>/dev/null | tr -d ' '); done

# 리포 소속 프로세스(CWD가 리포 안이거나 명령줄에 리포 경로 포함)가 measure.sh 계보 밖에 남아 있으면 0
# start.sh가 죽은 뒤에도 백그라운드 서버가 남았는지 판단한다.
repo_alive() {
  local pid
  for pid in $( { lsof -d cwd -Fpn 2>/dev/null | awk -v r="$REPO" '/^p/{pid=substr($0,2)} /^n/{p=substr($0,2); if (p==r || index(p, r"/")==1) print pid}'
                  ps -axo pid=,command= 2>/dev/null | awk -v r="$REPO" '{c=$0; sub(/^ *[0-9]+ /,"",c); if (index(c, r"/")>0 || index(c, r" ")>0) print $1}'
                } | sort -u ); do
    kill -0 "$pid" 2>/dev/null || continue   # 목록을 만드는 동안 끝난 프로세스(lsof·ps 파이프라인 자체 등)는 제외
    is_self_related "$pid" || return 0
  done
  return 1
}

# 리포 소속 프로세스가 LISTEN 중인 첫 포트 반환
find_port() {
  lsof -nP -iTCP -sTCP:LISTEN 2>/dev/null | tail -n +2 | while read -r _ pid _ _ _ _ _ _ name _; do
    belongs_to_repo "$pid" || continue
    echo "${name##*:}"
    break
  done
}

cleanup
[ -d "$REPO" ] && [ -f "$REPO/start.sh" ] || { echo "0,0"; exit 0; }
# 서브셸 stdout을 분리해야 호출 측 $(measure.sh)가 서버 수명 동안 파이프에 묶이지 않는다
SPORT="$(free_port)"
( cd "$REPO" && PORT="$SPORT" exec bash start.sh > "$BASE/server-iter.log" 2>&1 < /dev/null ) > /dev/null 2>&1 &
SRV=$!
PORT=""
for _ in $(seq 1 "$START_SEC"); do
  PORT=$(find_port | head -1)
  [ -n "$PORT" ] && break
  # start.sh가 이미 죽었고 리포 소속 프로세스도 남아 있지 않으면 기다려도 LISTEN하지 않는다
  kill -0 "$SRV" 2>/dev/null || repo_alive || break
  sleep 1
done
if [ -z "$PORT" ]; then cleanup; echo "0,0"; exit 0; fi

HURL_OUT="$BASE/hurl-last.log"
UID_VAL="$(date +%s)$$"
( "$HURL_BIN" --test --jobs 1 --max-time 10 \
    --variable "host=http://localhost:$PORT" --variable "uid=$UID_VAL" \
    "${HURL_FILES[@]}" > "$HURL_OUT" 2>&1 ) &
HP=$!
for _ in $(seq 1 300); do
  kill -0 "$HP" 2>/dev/null || break
  sleep 1
done
kill -9 "$HP" 2>/dev/null
cleanup
SF=$(grep -oE 'Succeeded files:[[:space:]]*[0-9]+' "$HURL_OUT" | grep -oE '[0-9]+' | tail -1)
ER=$(grep -oE 'Executed requests:[[:space:]]*[0-9]+' "$HURL_OUT" | grep -oE '[0-9]+' | tail -1)
echo "${SF:-0},${ER:-0}"
