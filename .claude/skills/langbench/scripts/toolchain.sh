#!/bin/bash
# 사용: bash toolchain.sh
# 실험에 필요한 도구의 절대 경로와 버전을 한 줄씩 "<이름> <경로> <버전>"으로 stdout에 출력한다.
# 하나라도 없으면 stderr에 "toolchain: 없음: <이름>"을 쓰고 모든 도구를 확인한 뒤 종료 코드 1로 끝난다.
# setup.sh가 이 출력을 $BASE/toolchain.txt로 저장한다(실험 README의 툴체인 버전 기록 근거).
# 설치는 하지 않는다. 경로는 runtime.sh와 같은 방식으로 찾는다:
#   PIP_AUDIT_BIN(기본 $HOME/.local/share/langbench-tools/venv/bin/pip-audit), GOVULNCHECK_BIN(기본 $HOME/go/bin/govulncheck),
#   cargo-audit는 $HOME/.cargo/bin, hurl은 /opt/homebrew/bin/hurl 절대 경로(PATH의 hurl은 쓰지 않는다).
PIP_AUDIT_BIN="${PIP_AUDIT_BIN:-$HOME/.local/share/langbench-tools/venv/bin/pip-audit}"
GOVULNCHECK_BIN="${GOVULNCHECK_BIN:-$HOME/go/bin/govulncheck}"
CARGO_AUDIT_BIN="$HOME/.cargo/bin/cargo-audit"
HURL_BIN="/opt/homebrew/bin/hurl"
fail=0

# check <이름> <경로 또는 빈 값> <버전 명령...> — 경로가 실행 가능하면 첫 줄 버전과 함께 출력한다.
check() {
  local name="$1" path="$2" ver; shift 2
  if [ -z "$path" ] || [ ! -x "$path" ]; then
    echo "toolchain: 없음: $name" >&2; fail=1; return
  fi
  ver=$("$@" 2>&1 | head -"${VER_LINES:-1}" | tr '\n' ' ' | sed 's/ *$//')
  echo "$name $path $ver"
}
which_() { command -v "$1" 2>/dev/null; }

p=$(which_ node);    check node "$p" "$p" --version
p=$(which_ npm);     check npm "$p" "$p" --version
p=$(which_ python3); check python3 "$p" "$p" --version
p=$(which_ go);      check go "$p" "$p" version
p=$(which_ cargo);   check cargo "$p" "$p" --version
p=$(which_ rustc);   check rustc "$p" "$p" --version
p=$(which_ cmake);   check cmake "$p" "$p" --version
p=$(which_ docker);  check docker "$p" "$p" --version
p=$(which_ k6);      check k6 "$p" "$p" version
p=$(which_ scc);     check scc "$p" "$p" --version
p=$(which_ trivy);   check trivy "$p" "$p" --version
check pip-audit "$PIP_AUDIT_BIN" "$PIP_AUDIT_BIN" --version
VER_LINES=2 check govulncheck "$GOVULNCHECK_BIN" "$GOVULNCHECK_BIN" -version
check cargo-audit "$CARGO_AUDIT_BIN" "$CARGO_AUDIT_BIN" --version
check hurl "$HURL_BIN" "$HURL_BIN" --version

# Drogon(C++ 프레임워크): brew 설치 버전 또는 pkg-config 버전
dv=""; dp=""
if command -v brew >/dev/null 2>&1; then
  dv=$(brew list --versions drogon 2>/dev/null | awk '{print $2}'); dp=$(command -v brew)
fi
if [ -z "$dv" ] && command -v pkg-config >/dev/null 2>&1; then
  dv=$(pkg-config --modversion drogon 2>/dev/null); dp=$(command -v pkg-config)
fi
if [ -n "$dv" ]; then echo "drogon $dp $dv"; else echo "toolchain: 없음: drogon" >&2; fail=1; fi

exit $fail
