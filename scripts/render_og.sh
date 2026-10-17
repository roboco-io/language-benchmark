#!/bin/bash
# dashboard/data.js로 SNS 공유용 Open Graph 카드(dashboard/og.png, 1200×630)를 렌더링한다.
# GitHub Pages 배포 워크플로가 업로드 직전에 실행한다(og.png는 커밋하지 않는 빌드 산출물). 로컬 미리보기용으로도 실행 가능.
# 필요: Google Chrome(또는 Chromium). CHROME 환경변수로 실행 파일을 지정할 수 있다.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -z "${CHROME:-}" ]; then
  for c in "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" google-chrome google-chrome-stable chromium chromium-browser; do
    if [ -x "$c" ] || command -v "$c" >/dev/null 2>&1; then CHROME="$c"; break; fi
  done
fi
[ -n "${CHROME:-}" ] || { echo "Chrome not found (set CHROME)" >&2; exit 1; }
EXTRA=()
[ "$(uname)" = "Linux" ] && EXTRA+=(--no-sandbox)  # CI 러너용
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
cp "$ROOT/scripts/og/og-card.html" "$TMP/card.html"
cp "$ROOT/dashboard/data.js" "$TMP/data.js"
rm -f "$ROOT/dashboard/og.png"
"$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
  --window-size=1200,630 --virtual-time-budget=5000 ${EXTRA[@]+"${EXTRA[@]}"} \
  --screenshot="$ROOT/dashboard/og.png" "file://$TMP/card.html" 2>/dev/null
[ -s "$ROOT/dashboard/og.png" ] || { echo "og.png was not produced" >&2; exit 1; }
echo "wrote dashboard/og.png"
