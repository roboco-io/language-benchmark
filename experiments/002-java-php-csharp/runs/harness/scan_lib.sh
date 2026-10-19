#!/bin/bash
# 정적 분석 공용 함수(runtime.sh와 Fargate local_prep.sh가 source한다). 언어별 값은 langreg.py(레지스트리)에서 온다.
# 호출 측이 정의해야 하는 것: run_scan <이름> <출력파일> <필수 키|-> <작업 디렉터리> <명령...>, scan_fail <이름> <사유>.
# langreg.py는 이 파일 옆에서 찾는다(setup.sh가 스크립트를 $BASE에 평평하게 복사하고 languages/도 $BASE/languages로 복사한다).
_LB_SCAN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PIP_AUDIT_BIN="${PIP_AUDIT_BIN:-$HOME/.local/share/langbench-tools/venv/bin/pip-audit}"
export GOVULNCHECK_BIN="${GOVULNCHECK_BIN:-$HOME/go/bin/govulncheck}"

# lb_static_scans <SRC> <OUT> <언어 키> <이미지>
# scc 코드량, hints.txt, 언어 스캐너, 이미지 trivy 스캔을 수행한다. OUT에 산출물을 쓴다.
lb_static_scans() {
  local SRC="$1" OUT="$2" LANG_KEY="$3" IMAGE="$4"
  local LR_SCAN_FORMAT LR_SCAN_NAME LR_SCAN_REPORT LR_SCAN_KEY LR_SCAN_OK_RC LR_LOC_EXCLUDE LR_LOC_NOTMATCH LR_HINTS
  local -a LR_SCAN_ARGV
  local vars
  # 언어를 모르면 스캐너 값을 못 얻는다. 그래도 scc·hints는 언어와 무관하므로 합집합 값만 따로 얻어 돌린다.
  if ! vars="$(python3 "$_LB_SCAN_DIR/langreg.py" shell "$LANG_KEY" "$SRC" 2>/dev/null)"; then
    vars="$(python3 "$_LB_SCAN_DIR/langreg.py" shell "$(python3 "$_LB_SCAN_DIR/langreg.py" keys | cut -d' ' -f1)" "$SRC")"
    eval "$vars"
    LR_SCAN_FORMAT=""
    _lb_scc_hints "$SRC" "$OUT"
    scan_fail lang "언어를 알 수 없음: run=${RUN:-} 언어=$LANG_KEY"
    _lb_trivy "$OUT" "$IMAGE"
    return 0
  fi
  eval "$vars"
  _lb_scc_hints "$SRC" "$OUT"
  # 언어 스캐너 (R4): 보고서 이름은 레지스트리 scanner.report. format이 none이면(cpp) 없다.
  # govulncheck는 패키지 로드·빌드 실패에도 config·progress 메시지만 담긴 유효 JSON을 낸다. 종료 코드 0(취약점 없음)·3(취약점 있음)이고
  # 스트림에 config 메시지가 있을 때만 보고서로 인정한다. 그 밖은 실패로 보아 go.json을 만들지 않는다(빈 칸, R22).
  if [ "$LR_SCAN_FORMAT" != none ]; then
    SCAN_OK_RC="$LR_SCAN_OK_RC" run_scan "$LR_SCAN_NAME" "$LR_SCAN_REPORT" "${LR_SCAN_KEY:--}" "$SRC" "${LR_SCAN_ARGV[@]}"
  fi
  _lb_trivy "$OUT" "$IMAGE"
}

# scc: 주 언어 코드량. 테스트·산출물 디렉터리와 테스트 파일은 뺀다.
# hints.txt: 빌드 모드·워커 설정 단서. 최종 판정은 실험자가 이 파일을 보고 report에 적는다(반자동).
_lb_scc_hints() {
  local SRC="$1" OUT="$2"
  run_scan scc scc.json - "$SRC" scc --format json --exclude-dir "$LR_LOC_EXCLUDE" --not-match "$LR_LOC_NOTMATCH" .
  ( cd "$SRC" && grep -rnE --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=target --exclude-dir=.venv --exclude-dir=vendor \
      -e "$LR_HINTS" . ) \
    > "$OUT/hints.txt" 2>/dev/null < /dev/null  # 일치가 없으면 grep이 1을 돌려주지만 빈 파일이 맞다
}

# trivy: 빌드된 이미지를 스캔한다. 이미지가 없으면(1부 build 실패) 보고서도 없다. 정리는 호출 측이 한다.
_lb_trivy() {
  local OUT="$1" IMAGE="$2"
  if docker image inspect "$IMAGE" >/dev/null 2>&1; then
    run_scan trivy trivy.json SchemaVersion "${TMP:?}" trivy image --quiet --format json --scanners vuln "$IMAGE"
  fi
}
