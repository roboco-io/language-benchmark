#!/bin/bash
# 사용: bash setup.sh <bench.env>
# $HOME/experiments/langbench-exp$EXP 하네스를 만든다: 정본 PROMPT·Hurl 복사+해시 검증, 스크립트 동결 복사. claude-native 전용.
set -euo pipefail
CFG="$1"; SKILL="$(cd "$(dirname "$0")/.." && pwd)"
source "$CFG"
BASE="$HOME/experiments/langbench-exp$EXP"
HARNESS="${HARNESS:-}"
case "$HARNESS" in
  claude-native) : ;;   # 사용자 기본 설정 사용 — 노출된 지침·스킬·MCP를 phase0.md에 기록할 것
  *) echo "unknown HARNESS=$HARNESS (claude-native만 지원)" >&2; exit 1 ;;
esac
# 비용 상한 필수 (EXP-042: 사후 집계 상한만 두어 2 run에 약 $92 과금). 구독 실행은 단가 0을 명시한다.
for v in RUN_BUDGET_USD EXP_BUDGET_USD MODEL_SPECS; do
  [ -n "${!v:-}" ] || { echo "$v 미설정 — 비용 상한과 모델 사양 파일은 필수 (bench.env.example 참조)" >&2; exit 1; }
done
[ -f "$MODEL_SPECS" ] || { echo "MODEL_SPECS 파일 없음: $MODEL_SPECS — 모델별 입력 한도·과금 체계를 조사해 작성 (preflight.py 주석의 형식)" >&2; exit 1; }
NRUNS=$(( $(echo $CONDITIONS | wc -w) * $(echo $LANGS | wc -w) * N ))
echo "비용 상한: run당 \$$RUN_BUDGET_USD, 실험 \$$EXP_BUDGET_USD (run ${NRUNS}개, 최악 run당 상한×run 수 = \$$(awk -v a="$RUN_BUDGET_USD" -v n="$NRUNS" 'BEGIN{print a*n}'))"
[ -e "$BASE/done-all" ] && { echo "이미 완료된 하네스: $BASE" >&2; exit 1; }
mkdir -p "$BASE"
# 툴체인 점검: 하나라도 없으면 중단한다. 출력은 실험 README에 옮길 버전 기록이다.
# LB_SKIP_TOOLCHAIN=1은 테스트 전용 우회다(실험에서는 쓰지 않는다).
if [ "${LB_SKIP_TOOLCHAIN:-}" != 1 ]; then
  LANGS="$LANGS" bash "$SKILL/scripts/toolchain.sh" > "$BASE/toolchain.txt" \
    || { echo "툴체인 점검 실패 — 위 '없음' 도구를 설치한 뒤 다시 실행 (설치는 사용자 승인 후)" >&2; exit 1; }
fi
cp "$CFG" "$BASE/bench.env"
# 실행 중 무결성 검사의 기준(R28): $BASE 사본이 아니라 이 스킬 디렉터리(git 추적)의 assets/checksums.md5·assets·scripts다.
# driver.sh·measure.sh·measure_docker.sh가 이 경로를 읽어 대조하고, run-all.sh·run_followups.sh가 이 디렉터리의 git 변경을 막는다.
echo "LB_SKILL_DIR=\"$SKILL\"" >> "$BASE/bench.env"
cp "$MODEL_SPECS" "$BASE/model-specs.json"
cp "$SKILL/assets/reference-profile.json" "$BASE/"
rm -f "$BASE/preflight-approved"   # 설정을 다시 만들면 승인도 다시 받는다
rm -f "$BASE/run-order.txt"        # 실행 순서는 새 설정(CONDITIONS·LANGS·N·RUN_ORDER_SEED)으로 run-all.sh가 다시 만든다(R26)
# 정본 복사: PROMPT-*.md, harness-hurl/, followup-hurl/(있으면), k6/(있으면)
rm -rf "$BASE/harness-hurl" "$BASE/followup-hurl" "$BASE/k6"
cp "$SKILL"/assets/PROMPT-*.md "$BASE/" 2>/dev/null || true
cp -r "$SKILL/assets/harness-hurl" "$BASE/"
[ -d "$SKILL/assets/followup-hurl" ] && cp -r "$SKILL/assets/followup-hurl" "$BASE/"
[ -d "$SKILL/assets/k6" ] && cp -r "$SKILL/assets/k6" "$BASE/"
# 해시 검증은 checksums.md5에 적힌 파일 목록 기준이다
(cd "$SKILL/assets" && md5 -r $(cut -d' ' -f2 checksums.md5) | diff - checksums.md5) \
  || { echo "정본 해시 불일치 — assets 변경 여부 확인" >&2; exit 1; }
# 레지스트리 항목이 유효해야 한다(필드 누락·허용되지 않는 값이면 여기서 중단)
python3 "$SKILL/scripts/langreg.py" validate $LANGS || { echo "언어 레지스트리 검증 실패 — languages/*.json 확인" >&2; exit 1; }
# 각 언어의 PROMPT가 있고 checksums.md5에 고정돼 있어야 한다
for l in $LANGS; do
  [ -f "$SKILL/assets/PROMPT-$l.md" ] && grep -q " PROMPT-$l.md\$" "$SKILL/assets/checksums.md5" \
    || { echo "PROMPT-$l.md 없음 또는 checksums.md5에 미등록" >&2; exit 1; }
done
# 일반 파일만 복사한다. langreg를 import하면 scripts/__pycache__/가 생기는데, 디렉터리를 cp하면 set -e에서 중단된다.
find "$SKILL/scripts" -maxdepth 1 -type f -exec cp {} "$BASE/" \;
# 하네스 사본의 langreg.py·scan_lib.sh가 <스크립트 디렉터리>/languages에서 레지스트리를 찾는다
rm -rf "$BASE/languages"; cp -r "$SKILL/languages" "$BASE/languages"

command -v /opt/homebrew/bin/hurl >/dev/null || { echo "hurl 없음 (/opt/homebrew/bin/hurl)" >&2; exit 1; }
echo "OK: $BASE ($HARNESS)"
echo
python3 "$BASE/preflight.py" "$BASE" || true
echo
echo "다음: 위 보고서($BASE/preflight-report.md)를 사용자에게 보여 주고 AskUserQuestion으로 진행 여부를 묻는다."
echo "      승인 시에만 bash $BASE/approve.sh \"<답변 원문>\" → bash $BASE/smoke.sh → nohup caffeinate -is bash $BASE/run-all.sh >/dev/null 2>&1 &"
