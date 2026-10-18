# shellcheck shell=bash
# run-all.sh·run_followups.sh·driver.sh·measure.sh·measure_docker.sh가 source하는 공용 함수. BASE는 호출한 쪽이 정의한다.
archive_sessions(){ # $1 run — 기본 설정 디렉터리의 해당 app 프로젝트 로그만 복사
  # 내용(src/.)을 대상 안으로 복사한다. 대상이 이미 있어도 하위에 중첩 디렉터리를 만들지 않는다(R20). 같은 세션 id 파일은 덮어쓴다.
  local enc src; enc=$(echo "$BASE/app-$1" | sed 's|[/.]|-|g'); src="$HOME/.claude/projects/$enc"
  [ -d "$src" ] || return 0
  mkdir -p "$BASE/sessions-$1" && cp -R "$src/." "$BASE/sessions-$1/" 2>/dev/null
}

restart_held(){ # $1 run — 구독 한도로 보류된(hold-<run>) run의 부분 산출물을 held/<run>-<시각>/로 옮겨 처음부터 다시 돌릴 수 있게 한다(R18)
  # 옮기는 것: app-<run>, metrics-<run>.csv, ralph-run-<run>.log, hold-<run>, 세션(보관본 sessions-<run>과 ~/.claude/projects의 원본).
  # 세션은 held/…/sessions-<run>에 합쳐 두므로 driver의 비용 가드가 실험 누적 비용에 계속 넣는다. 옮긴 디렉터리를 출력한다.
  local run="$1" dst enc live f
  [ -f "$BASE/hold-$run" ] || return 1
  dst="$BASE/held/$run-$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$dst/sessions-$run" || return 1
  enc=$(echo "$BASE/app-$run" | sed 's|[/.]|-|g'); live="$HOME/.claude/projects/$enc"
  [ -d "$live" ] && cp -R "$live/." "$dst/sessions-$run/" && rm -rf "$live"
  [ -d "$BASE/sessions-$run" ] && cp -R "$BASE/sessions-$run/." "$dst/sessions-$run/" && rm -rf "$BASE/sessions-$run"
  for f in "app-$run" "metrics-$run.csv" "ralph-run-$run.log" "hold-$run"; do
    [ -e "$BASE/$f" ] && mv "$BASE/$f" "$dst/"
  done
  echo "$dst"
}

hold_stop(){ # $1 run — driver가 75(구독 한도 보류)로 끝났을 때 배치 전체를 멈춘다(R18). 호출한 스크립트의 log 함수를 쓴다.
  local why; why=$(tail -1 "$BASE/hold-$1" 2>/dev/null)
  log "HOLD: subscription limit — $1 보류, 배치 중단 ($why)"
  echo "HOLD: subscription limit — $1 보류, 배치 중단 ($why). 한도가 풀린 뒤 같은 명령을 다시 실행하면 이 run을 처음부터 다시 시작한다."
  exit 75
}

# 채점 정본·하네스 무결성(R24, R28). 에이전트는 $BASE/app-<run>에서 권한 확인 없이 돌므로 $BASE 안의 정본·스크립트·해시 목록을 모두 고칠 수 있다.
# 그래서 기준은 $BASE 사본이 아니라 스킬 리포(git 추적)다. setup.sh가 스킬 경로를 $BASE/bench.env의 LB_SKILL_DIR에 기록한다.
# 스킬 디렉터리 자체의 변경은 run-all.sh·run_followups.sh가 매 run 시작 전에 skill_clean(git status)으로 막는다.
skill_dir(){ sed -n 's/^LB_SKILL_DIR="\{0,1\}\([^"]*\)"\{0,1\}$/\1/p' "$BASE/bench.env" 2>/dev/null | tail -1; }
# harness_intact: 0이면 무결. 다음을 모두 확인한다.
#   (1) 스킬 assets/checksums.md5가 스킬 assets와 일치  (2) $BASE의 같은 경로(PROMPT·Hurl)가 그 해시와 일치
#   (3) $BASE의 harness-hurl/·followup-hurl/·k6/가 스킬 assets와 파일 구성·내용까지 같음(목록 밖 파일 추가 검출)
#   (4) $BASE의 하네스 스크립트가 스킬 scripts/와 같음
# driver.sh는 시작 시와 매 게이트 전에, measure.sh·measure_docker.sh는 $BASE 안의 Hurl로 채점할 때(독립 재검증 포함) 호출한다.
harness_intact(){
  local skill f d sums; skill=$(skill_dir)
  [ -n "$skill" ] && sums="$skill/assets/checksums.md5" && [ -s "$sums" ] || return 1
  # shellcheck disable=SC2046  # 정본 파일 이름에 공백이 없다
  ( cd "$skill/assets" && md5 -r $(cut -d' ' -f2 checksums.md5) 2>/dev/null | diff -q - checksums.md5 >/dev/null ) || return 1
  # shellcheck disable=SC2046
  ( cd "$BASE" && md5 -r $(cut -d' ' -f2 "$sums") 2>/dev/null | diff -q - "$sums" >/dev/null ) || return 1
  for d in harness-hurl followup-hurl k6; do
    if [ -d "$skill/assets/$d" ] || [ -e "$BASE/$d" ]; then diff -rq "$skill/assets/$d" "$BASE/$d" >/dev/null 2>&1 || return 1; fi
  done
  for f in "$skill"/scripts/*; do
    [ -f "$f" ] || continue
    cmp -s "$f" "$BASE/$(basename "$f")" || return 1
  done
}
# skill_clean: 스킬 디렉터리가 git 리포 안에 있고 커밋되지 않은 변경(미추적 포함)이 없으면 0.
skill_clean(){
  local skill; skill=$(skill_dir)
  [ -n "$skill" ] && git -C "$skill" rev-parse --git-dir >/dev/null 2>&1 || return 1
  [ -z "$(git -C "$skill" status --porcelain -- . 2>/dev/null)" ]
}
# grades_from_base <hurl 디렉터리...>: 채점 디렉터리 중 하나라도 $BASE 안이면 0(하네스 채점이므로 무결성 검사 대상).
grades_from_base(){
  local d p
  for d in "$@"; do
    p=$(cd "$d" 2>/dev/null && pwd) || continue
    case "$p" in "$BASE"/*) return 0 ;; esac
  done
  return 1
}
