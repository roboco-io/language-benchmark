# shellcheck shell=bash
# run-all.sh·run_followups.sh가 source하는 공용 함수. BASE는 호출한 쪽이 정의한다.
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
