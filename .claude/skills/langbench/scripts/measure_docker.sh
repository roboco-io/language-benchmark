#!/bin/bash
# Docker 채점: Dockerfile 빌드 → 컨테이너 기동 → 공식 Hurl 채점
# 사용: measure_docker.sh <repo> <tag> <hurl-dir>...   (채점 대상은 각 디렉터리의 *.hurl)
# stdout: "succ_files,exec_reqs"   Dockerfile이 없거나 빌드 실패·DOCKER_BUILD_SEC 초과·기동 실패면 "0,0"
# 환경변수: DOCKER_BUILD_SEC(빌드 한도, 기본 900)
# 컨테이너 이름 lb-<tag>, 이미지 lb/<tag>. 이미지는 남긴다(정리는 cleanup.sh가 lb/* 삭제).
# 로그: $BASE/docker-build-last.log, $BASE/hurl-last.log
BASE="$(cd "$(dirname "$0")" && pwd)"
REPO="$1"; TAG="$2"; shift 2
HURL_BIN="/opt/homebrew/bin/hurl"
DOCKER_BUILD_SEC="${DOCKER_BUILD_SEC:-900}"
NAME="lb-$TAG"; IMAGE="lb/$TAG"
HURL_FILES=()
for d in "$@"; do for f in "$d"/*.hurl; do [ -f "$f" ] && HURL_FILES+=("$f"); done; done

trap 'docker rm -f "$NAME" >/dev/null 2>&1' EXIT

# 빈 포트 선택 (measure.sh와 같은 방식)
free_port() {
  local p
  for _ in $(seq 1 50); do
    p=$(( 40000 + RANDOM % 10000 ))
    lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1 || { echo "$p"; return; }
  done
}

[ -d "$REPO" ] && [ -f "$REPO/Dockerfile" ] || { echo "0,0"; exit 0; }
docker rm -f "$NAME" >/dev/null 2>&1

# 빌드: 백그라운드로 돌리고 한도를 폴링한다. 초과 시 빌드 클라이언트를 종료한다.
BUILD_LOG="$BASE/docker-build-last.log"
docker build -t "$IMAGE" "$REPO" > "$BUILD_LOG" 2>&1 < /dev/null &
BP=$!
for _ in $(seq 1 "$DOCKER_BUILD_SEC"); do
  kill -0 "$BP" 2>/dev/null || break
  sleep 1
done
if kill -0 "$BP" 2>/dev/null; then
  echo "docker build ${DOCKER_BUILD_SEC}s 초과 — 종료" >> "$BUILD_LOG"
  pkill -9 -P "$BP" 2>/dev/null; kill -9 "$BP" 2>/dev/null
  echo "0,0"; exit 0
fi
wait "$BP" || { echo "0,0"; exit 0; }

P="$(free_port)"
docker run -d --name "$NAME" --cpus=2 --memory=1g -e PORT="$P" -p 127.0.0.1:"$P":"$P" "$IMAGE" >/dev/null 2>&1 \
  || { echo "0,0"; exit 0; }

# 기동 대기: GET /api/tags가 200을 줄 때까지 최대 60초
UP=0
for _ in $(seq 1 60); do
  [ "$(curl -s -o /dev/null -m 2 -w '%{http_code}' "http://localhost:$P/api/tags")" = 200 ] && { UP=1; break; }
  sleep 1
done
[ "$UP" = 1 ] || { echo "0,0"; exit 0; }

HURL_OUT="$BASE/hurl-last.log"
UID_VAL="$(date +%s)$$"
( "$HURL_BIN" --test --jobs 1 --max-time 10 \
    --variable "host=http://localhost:$P" --variable "uid=$UID_VAL" \
    "${HURL_FILES[@]}" > "$HURL_OUT" 2>&1 ) &
HP=$!
for _ in $(seq 1 300); do
  kill -0 "$HP" 2>/dev/null || break
  sleep 1
done
kill -9 "$HP" 2>/dev/null
SF=$(grep -oE 'Succeeded files:[[:space:]]*[0-9]+' "$HURL_OUT" | grep -oE '[0-9]+' | tail -1)
ER=$(grep -oE 'Executed requests:[[:space:]]*[0-9]+' "$HURL_OUT" | grep -oE '[0-9]+' | tail -1)
echo "${SF:-0},${ER:-0}"
