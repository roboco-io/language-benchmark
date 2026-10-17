# run-all.sh·run_followups.sh가 source하는 공용 함수. BASE는 호출한 쪽이 정의한다.
archive_sessions(){ # $1 run — 기본 설정 디렉터리의 해당 app 프로젝트 로그만 복사
  local enc; enc=$(echo "$BASE/app-$1" | sed 's|[/.]|-|g')
  cp -r "$HOME/.claude/projects/$enc" "$BASE/sessions-$1" 2>/dev/null
}
