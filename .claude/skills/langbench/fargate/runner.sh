#!/bin/bash
# 부하 태스크: runtime.sh 1부의 3-6단계(유휴 메모리 수집 → 시드 → 워밍업 → 부하 3회 → soak)를 원격 앱 태스크에 대해 수행한다.
# 단계·파일 이름·실패 문구는 runtime.sh와 같다. 콜드 스타트와 유휴 메모리는 앱 태스크의 probe가 잰 값을 받아 적는다.
# 환경변수: RUN, APP_URL(http://<앱 태스크 IP>:<PORT>), PROBE_URL(http://<앱 태스크 IP>:9000), BUCKET, SOAK_SEC(기본 600)
# 산출물은 s3://$BUCKET/${S3_PREFIX}runtime/$RUN/ 에 올린다(S3_PREFIX는 오케스트레이터가 주는 exp002/ 형태, 없으면 빈 값). env.json에 앱 태스크의 AZ·CPU 정보를 남긴다.
OUT=/tmp/out; TMP=/tmp/work; mkdir -p "$OUT" "$TMP"
SOAK_SEC="${SOAK_SEC:-600}"; VUS=32
SAMPLER=""; K6PID=""
TARGET_CPU="${TARGET_CPU:-0xd4f}"  # Graviton4(Neoverse V2). 측정 전 환경 통제: 다른 세대면 측정 없이 끝낸다.

# 0. CPU 세대 확인(업로드 전). 부하 태스크가 다르면 42, 앱 태스크가 다르면 43으로 끝나고 오케스트레이터가 태스크를 새로 띄운다.
MYCPU=$(grep -m1 -i 'cpu part' /proc/cpuinfo | awk -F: '{gsub(/ /,"",$2); print $2}')
[ "$MYCPU" = "$TARGET_CPU" ] || { echo "load cpu $MYCPU != $TARGET_CPU"; exit 42; }
APPCPU=""
for _ in $(seq 1 120); do
  APPCPU=$(curl -s -m 2 "$PROBE_URL/status" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("cpu_part") or "")' 2>/dev/null)
  [ -n "$APPCPU" ] && break; sleep 1
done
[ "$APPCPU" = "$TARGET_CPU" ] || { echo "app cpu '$APPCPU' != $TARGET_CPU"; exit 43; }

upload() {
  python3 - "$OUT" "$BUCKET" "${S3_PREFIX:-}runtime/$RUN" <<'PY'
import os, sys, boto3
out, bucket, prefix = sys.argv[1:4]
s3 = boto3.client("s3")
for f in sorted(os.listdir(out)):
    s3.upload_file(os.path.join(out, f), bucket, f"{prefix}/{f}")
PY
}
finish() {
  [ -n "$SAMPLER" ] && kill "$SAMPLER" 2>/dev/null
  [ -n "$K6PID" ] && kill "$K6PID" 2>/dev/null
  cp "$TMP/k6.log" "$OUT/k6.log" 2>/dev/null
  curl -s -m 5 "$PROBE_URL/status" > "$OUT/env.json" 2>/dev/null
  echo "{\"load_cpu_part\": \"$MYCPU\"}" > "$OUT/load_env.json"
  upload
}
trap finish EXIT
fail() { echo "$1" > "$OUT/error.txt"; echo "runtime 실패: $1" >&2; exit 1; }

mem_now() { curl -s -m 2 "$PROBE_URL/mem" | python3 -c 'import json,sys; m=json.load(sys.stdin).get("mem"); print(m if m is not None else "")' 2>/dev/null; }
alive() { curl -s -m 2 "$PROBE_URL/mem" | grep -q '"running": true'; }

# 2-3. probe가 잰 콜드 스타트·유휴 메모리를 기다린다(앱 기동 60초 + 유휴 10초 + 여유)
ST=""
for _ in $(seq 1 180); do
  ST=$(curl -s -m 2 "$PROBE_URL/status")
  echo "$ST" | python3 -c 'import json,sys; d=json.load(sys.stdin); sys.exit(0 if (d.get("error") or d.get("idle_mem") is not None) else 1)' 2>/dev/null && break
  sleep 1
done
ERR=$(echo "$ST" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("error") or "")' 2>/dev/null)
[ -n "$ERR" ] && fail "$ERR"
CS=$(echo "$ST" | python3 -c 'import json,sys; v=json.load(sys.stdin).get("cold_start_ms"); print("" if v is None else v)' 2>/dev/null)
IM=$(echo "$ST" | python3 -c 'import json,sys; v=json.load(sys.stdin).get("idle_mem"); print("" if v is None else v)' 2>/dev/null)
[ -n "$CS" ] || fail "cold_start: probe 응답 없음"
echo "$CS" > "$OUT/cold_start_ms.txt"
[ -n "$IM" ] || fail "idle_mem"
echo "$IM" > "$OUT/idle_mem.txt"

# 4. 시드
python3 /opt/lb/seed.py "$APP_URL" "$TMP/seed.json" || fail "seed"

k6run() {
  env SEED="$TMP/seed.json" BASE="$APP_URL" VUS="$VUS" DURATION="$1" \
    k6 run --quiet --no-color --summary-export "$2" /opt/lb/load.js >> "$TMP/k6.log" 2>&1
}

# 5. 워밍업 → 측정 3회(1초 간격 메모리 샘플의 최대값 = peak_mem)
k6run 15s "$TMP/warmup.json" || fail "warmup"
alive || fail "warmup: 컨테이너 종료됨"
: > "$TMP/mem_samples.txt"
( while :; do mem_now >> "$TMP/mem_samples.txt"; sleep 1; done ) &
SAMPLER=$!
for i in 1 2 3; do
  k6run 30s "$OUT/load-$i.json" || { kill "$SAMPLER" 2>/dev/null; SAMPLER=""; fail "load-$i"; }
  alive || { kill "$SAMPLER" 2>/dev/null; SAMPLER=""; fail "load-$i: 컨테이너 종료됨"; }
done
kill "$SAMPLER" 2>/dev/null; SAMPLER=""
PEAK=$(awk 'NF && $1+0 > m { m=$1+0 } END { if (m > 0) printf "%.1f\n", m }' "$TMP/mem_samples.txt")
[ -n "$PEAK" ] || fail "peak_mem: 샘플 없음"
echo "$PEAK" > "$OUT/peak_mem.txt"

# 6. soak
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
exit 0
