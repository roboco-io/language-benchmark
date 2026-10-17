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

# 채점 정본·하네스 무결성(R24). 에이전트는 $BASE/app-<run>에서 권한 확인 없이 돌므로 바로 위의 채점 정본과 스크립트에 쓸 수 있다.
# write_manifest: setup.sh가 정본 해시 검증을 통과한 직후 PROMPT·Hurl·k6·스크립트의 md5를 $BASE/checksums.md5에 쓴다.
write_manifest(){
  ( cd "$BASE" || exit 1
    # shellcheck disable=SC2046,SC2012  # 파일 이름에 공백이 없다(정본·스크립트 이름 고정)
    md5 -r $(ls PROMPT-*.md ./*.sh ./*.py 2>/dev/null | sed 's|^\./||') $(find harness-hurl followup-hurl k6 -type f 2>/dev/null | sort) > checksums.md5 )
}
# harness_intact: checksums.md5와 대조해 같으면 0. 목록 파일이 없거나 해시가 다르거나 채점 디렉터리에 목록에 없는 파일이 생기면 1.
# driver.sh는 매 게이트 전에, measure.sh·measure_docker.sh는 $BASE 안의 Hurl로 채점할 때(독립 재검증 포함) 호출한다.
harness_intact(){
  ( cd "$BASE" && [ -s checksums.md5 ] || exit 1
    # shellcheck disable=SC2046
    md5 -r $(cut -d' ' -f2 checksums.md5) 2>/dev/null | diff -q - checksums.md5 >/dev/null || exit 1
    [ "$(find harness-hurl followup-hurl -type f 2>/dev/null | sort)" = "$(cut -d' ' -f2 checksums.md5 | grep -E '^(harness|followup)-hurl/' | sort)" ] )
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
