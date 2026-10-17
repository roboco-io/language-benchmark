#!/bin/bash
# 런타임 측정 1부: 빌드 → 콜드 스타트 → 유휴 메모리 → 시드 → 부하 3회(+워밍업) → soak
# 사용: runtime.sh <repo> <run>
# 산출물: $BASE/runtime-<run>/ 아래
#   build.json {build_sec,image_mb}, cold_start_ms.txt(ms), idle_mem.txt(MiB), load-{1,2,3}.json(k6 --summary-export),
#   peak_mem.txt(MiB, 측정 3회 동안 1초 샘플 최대), soak.json, soak_mem_start.txt·soak_mem_end.txt(MiB), error.txt(실패 시)
# 실패하면 이후 단계를 건너뛰고 실패 단계 이름을 error.txt에 쓴 뒤 종료 코드 1로 끝난다(뒤 단계 파일은 없다 = 빈 칸).
# 환경변수: SOAK_SEC(soak 길이, 기본 600), DOCKER_BUILD_SEC(빌드 한도, 기본 900)
# 빌드는 repo의 git HEAD를 임시 디렉터리에 새로 clone해서 한다(작업 트리의 node_modules·target·DB 같은 미추적 파일이 build_sec·image_mb에 섞이지 않게).
# 컨테이너는 --cpus=2 --memory=1g (모든 언어 동일). 포트는 40000-49999의 빈 포트를 PORT로 주입한다.
BASE="$(cd "$(dirname "$0")" && pwd)"
REPO="$1"; RUN="$2"
[ -n "$REPO" ] && [ -n "$RUN" ] || { echo "사용: runtime.sh <repo> <run>" >&2; exit 2; }
REPO="$(cd "$REPO" && pwd)" || exit 2
OUT="$BASE/runtime-$RUN"
NAME="lb-rt-$RUN"; IMAGE="lb/$RUN:rt"
DOCKER_BUILD_SEC="${DOCKER_BUILD_SEC:-900}"
SOAK_SEC="${SOAK_SEC:-600}"
VUS=32
TMP="$(mktemp -d "${TMPDIR:-/tmp}/lb-rt.XXXXXX")"
SAMPLER=""; K6PID=""
LOADJS="$BASE/k6/load.js"

# shellcheck disable=SC2329  # trap으로 호출된다
cleanup() {
  [ -n "$SAMPLER" ] && kill "$SAMPLER" 2>/dev/null
  [ -n "$K6PID" ] && kill "$K6PID" 2>/dev/null
  docker rm -f "$NAME" >/dev/null 2>&1
  rm -rf "$TMP"
}
trap cleanup EXIT

rm -rf "$OUT"; mkdir -p "$OUT"
fail() { echo "$1" > "$OUT/error.txt"; echo "runtime 실패: $1" >&2; exit 1; }

now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }

free_port() {
  local p
  for _ in $(seq 1 50); do
    p=$(( 40000 + RANDOM % 10000 ))
    lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1 || { echo "$p"; return; }
  done
}

# 컨테이너 메모리(MiB). docker stats MemUsage 앞쪽 값을 환산한다. 실패하면 빈 문자열.
mem_now() {
  docker stats --no-stream --format '{{.MemUsage}}' "$NAME" 2>/dev/null | awk '
    { v=$1; u=v; sub(/^[0-9.]+/, "", u); sub(/[A-Za-z]+$/, "", v)
      if (u=="KiB") v/=1024; else if (u=="GiB") v*=1024; else if (u=="TiB") v*=1048576; else if (u=="B") v/=1048576
      printf "%.1f\n", v; exit }'
}

# 1. 빌드 (fresh clone, --no-cache)
SRC="$TMP/src"
git clone -q --no-hardlinks "$REPO" "$SRC" 2>"$TMP/clone.log" || fail "clone"
[ -f "$SRC/Dockerfile" ] || fail "build: Dockerfile이 git HEAD에 없음"
T0=$(now_ms)
docker build --no-cache -t "$IMAGE" "$SRC" > "$TMP/build.log" 2>&1 < /dev/null &
BP=$!
for _ in $(seq 1 "$DOCKER_BUILD_SEC"); do kill -0 "$BP" 2>/dev/null || break; sleep 1; done
if kill -0 "$BP" 2>/dev/null; then
  pkill -9 -P "$BP" 2>/dev/null; kill -9 "$BP" 2>/dev/null
  fail "build: ${DOCKER_BUILD_SEC}s 초과"
fi
wait "$BP" || fail "build"
T1=$(now_ms)
SIZE=$(docker image inspect -f '{{.Size}}' "$IMAGE" 2>/dev/null) || fail "build: image inspect"
awk -v ms=$(( T1 - T0 )) -v sz="$SIZE" 'BEGIN { printf "{\"build_sec\": %.1f, \"image_mb\": %.1f}\n", ms/1000, sz/1e6 }' > "$OUT/build.json"

# 2. 콜드 스타트: docker run 직전부터 GET /api/tags 200까지 (50ms 간격 폴링, 최대 60초)
P="$(free_port)"; [ -n "$P" ] || fail "port"
URL="http://127.0.0.1:$P"
docker rm -f "$NAME" >/dev/null 2>&1
T0=$(now_ms)
docker run -d --name "$NAME" --cpus=2 --memory=1g -e PORT="$P" -p 127.0.0.1:"$P":"$P" "$IMAGE" >/dev/null 2>&1 || fail "run"
UP=0
while [ $(( $(now_ms) - T0 )) -lt 60000 ]; do
  [ "$(curl -s -o /dev/null -m 1 -w '%{http_code}' "$URL/api/tags")" = 200 ] && { UP=1; break; }
  sleep 0.05
done
T1=$(now_ms)
[ "$UP" = 1 ] || fail "cold_start: 60초 안에 GET /api/tags 200 없음"
echo $(( T1 - T0 )) > "$OUT/cold_start_ms.txt"

# 3. 유휴 메모리: 기동 10초 뒤
sleep 10
M=$(mem_now); [ -n "$M" ] || fail "idle_mem"
echo "$M" > "$OUT/idle_mem.txt"

# 4. 시드
python3 "$BASE/seed.py" "$URL" "$TMP/seed.json" || fail "seed"

alive() { [ "$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null)" = true ]; }
k6run() {  # k6run <duration> <summary-export>
  # load.js가 읽는 BASE는 대상 URL이다(하네스 BASE와 다름). env로 넘겨 이 스크립트의 $BASE와 섞이지 않게 한다.
  env SEED="$TMP/seed.json" BASE="$URL" VUS="$VUS" DURATION="$1" \
    k6 run --quiet --no-color --summary-export "$2" "$LOADJS" >> "$TMP/k6.log" 2>&1
}

# 5. 워밍업(결과 버림) → 측정 3회. 측정 중 1초 간격 메모리 샘플의 최대값을 peak_mem.txt에 쓴다.
k6run 15s "$TMP/warmup.json" || fail "warmup"
alive || fail "warmup: 컨테이너 종료됨"
: > "$TMP/mem_samples.txt"
( while :; do mem_now >> "$TMP/mem_samples.txt"; sleep 1; done ) &
SAMPLER=$!
disown "$SAMPLER" 2>/dev/null  # 종료 시 "Terminated" 알림이 stderr에 섞이지 않게
for i in 1 2 3; do
  k6run 30s "$OUT/load-$i.json" || { kill "$SAMPLER" 2>/dev/null; SAMPLER=""; fail "load-$i"; }
  alive || { kill "$SAMPLER" 2>/dev/null; SAMPLER=""; fail "load-$i: 컨테이너 종료됨"; }
done
kill "$SAMPLER" 2>/dev/null; SAMPLER=""
PEAK=$(awk 'NF && $1+0 > m { m=$1+0 } END { if (m > 0) printf "%.1f\n", m }' "$TMP/mem_samples.txt")
[ -n "$PEAK" ] || fail "peak_mem: 샘플 없음"
echo "$PEAK" > "$OUT/peak_mem.txt"

# 6. soak: 시작 30초 뒤와 종료 5초 전 메모리 (SOAK_SEC가 짧으면 구간 비율로 줄인다)
S_AT=30; E_AT=$(( SOAK_SEC - 5 ))
[ "$SOAK_SEC" -le 40 ] && { S_AT=$(( SOAK_SEC / 4 )); E_AT=$(( SOAK_SEC - 1 )); }
k6run "${SOAK_SEC}s" "$OUT/soak.json" &
K6PID=$!
sleep "$S_AT"
M=$(mem_now); [ -n "$M" ] && echo "$M" > "$OUT/soak_mem_start.txt"
sleep $(( E_AT - S_AT ))
M=$(mem_now); [ -n "$M" ] && echo "$M" > "$OUT/soak_mem_end.txt"
wait "$K6PID" || { K6PID=""; fail "soak"; }
K6PID=""
alive || fail "soak: 컨테이너 종료됨"
[ -s "$OUT/soak_mem_start.txt" ] && [ -s "$OUT/soak_mem_end.txt" ] || fail "soak_mem"

# 7. 컨테이너·임시 clone 제거는 trap(cleanup)이 한다.
exit 0
