# run-all.sh·run_followups.sh가 source하는 공용 함수. BASE는 호출한 쪽이 정의한다.
archive_sessions(){ # $1 run — 기본 설정 디렉터리의 해당 app 프로젝트 로그만 복사
  # 내용(src/.)을 대상 안으로 복사한다. 대상이 이미 있어도 하위에 중첩 디렉터리를 만들지 않는다(R20). 같은 세션 id 파일은 덮어쓴다.
  local enc src; enc=$(echo "$BASE/app-$1" | sed 's|[/.]|-|g'); src="$HOME/.claude/projects/$enc"
  [ -d "$src" ] || return 0
  mkdir -p "$BASE/sessions-$1" && cp -R "$src/." "$BASE/sessions-$1/" 2>/dev/null
}
