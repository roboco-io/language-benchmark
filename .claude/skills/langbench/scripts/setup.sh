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
  bash "$SKILL/scripts/toolchain.sh" > "$BASE/toolchain.txt" \
    || { echo "툴체인 점검 실패 — 위 '없음' 도구를 설치한 뒤 다시 실행 (설치는 사용자 승인 후)" >&2; exit 1; }
fi
cp "$CFG" "$BASE/bench.env"
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
# 각 언어의 PROMPT가 있고 checksums.md5에 고정돼 있어야 한다
for l in $LANGS; do
  [ -f "$SKILL/assets/PROMPT-$l.md" ] && grep -q " PROMPT-$l.md\$" "$SKILL/assets/checksums.md5" \
    || { echo "PROMPT-$l.md 없음 또는 checksums.md5에 미등록" >&2; exit 1; }
done
cp "$SKILL"/scripts/* "$BASE/"
# 실행 중 무결성 검사용 해시 목록(R24): 검증을 통과한 정본과 동결한 스크립트를 $BASE/checksums.md5에 고정한다.
# driver.sh는 매 게이트 전에, measure.sh·measure_docker.sh는 재검증 때 이 목록과 대조한다.
# shellcheck source=common.sh
source "$BASE/common.sh"; write_manifest

command -v /opt/homebrew/bin/hurl >/dev/null || { echo "hurl 없음 (/opt/homebrew/bin/hurl)" >&2; exit 1; }
echo "OK: $BASE ($HARNESS)"
echo
python3 "$BASE/preflight.py" "$BASE" || true
echo
echo "다음: 위 보고서($BASE/preflight-report.md)를 사용자에게 보여 주고 AskUserQuestion으로 진행 여부를 묻는다."
echo "      승인 시에만 bash $BASE/approve.sh \"<답변 원문>\" → bash $BASE/smoke.sh → nohup caffeinate -is bash $BASE/run-all.sh >/dev/null 2>&1 &"
