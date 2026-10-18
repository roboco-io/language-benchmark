# Phase 0 — EXP-001

- 기록 시작: 2026-10-18 09:50:53 +0900
- claude: 2.1.302 (Claude Code)
- hurl: hurl 8.0.1 (x86_64-apple-darwin25.0) libcurl/8.7.1 (SecureTransport) LibreSSL/3.3.6 zlib/1.2.12 nghttp2/1.68.1
- PATH hurl: /Users/dohyunjung/.local/bin/hurl → hurl 8.0.1 (x86_64-apple-darwin25.0) libcurl/8.7.1 (SecureTransport) LibreSSL/3.3.6 zlib/1.2.12 nghttp2/1.68.1

## toolchain.txt
```
node /Users/dohyunjung/.asdf/shims/node v24.14.0
npm /Users/dohyunjung/.asdf/shims/npm 11.19.1
python3 /opt/homebrew/bin/python3 Python 3.14.4
go /opt/homebrew/bin/go go version go1.27.2 darwin/arm64
cargo /Users/dohyunjung/.cargo/bin/cargo cargo 1.99.1 (5f94df478 2026-08-27)
rustc /Users/dohyunjung/.cargo/bin/rustc rustc 1.99.1 (58cf8f9c3 2026-10-13)
cmake /opt/homebrew/bin/cmake cmake version 4.4.4
docker /usr/local/bin/docker Docker version 29.8.0, build 88096ef
k6 /opt/homebrew/bin/k6 k6 v1.1.0 (go1.24.4, darwin/arm64)
scc /opt/homebrew/bin/scc scc version 4.1.0
trivy /opt/homebrew/bin/trivy Version: 0.76.0
pip-audit /Users/dohyunjung/.local/share/langbench-tools/venv/bin/pip-audit pip-audit 2.10.1
govulncheck /Users/dohyunjung/go/bin/govulncheck Go: go1.27.2 Scanner: govulncheck@v1.9.0
cargo-audit /Users/dohyunjung/.cargo/bin/cargo-audit cargo-audit 0.22.2
hurl /opt/homebrew/bin/hurl hurl 8.0.1 (x86_64-apple-darwin25.0) libcurl/8.7.1 (SecureTransport) LibreSSL/3.3.6 zlib/1.2.12 nghttp2/1.68.1
drogon /opt/homebrew/bin/brew 1.9.14
```

## 공통 MAX_SEC
- hello-world 빌드(호스트 콜드 / docker no-cache, 초): ts 13/45, py 6/13, go 22/46, rust 36/87, cpp 7/66(Drogon 소스 빌드). → MAX_SEC=2400 (모든 언어 공통, R17).

## TS 기준선 재현 (sonnet55-typescript-0)
- 시작 09:50:54, iteration 1 pass 09:54:59 (약 4.1분), 호스트 13/154, Docker 13/154, 추정 비용 약 $0.54(API 정가 환산).
- 자매 리포 기준선(EXP-031 Sonnet 5.5: iteration 1 완주, 세션 4-8분)과 일치 → 30 run 진행.
- 참고: driver 출력의 `Killed: 9`는 채점 후 서버 정리(SIGKILL) 메시지다.

## 에이전트 노출
- claude-native 비격리: ~/.claude/CLAUDE.md, 사용자 스킬, MCP(browsermcp, context7, exa). 리포 CLAUDE.md는 앱 경로가 리포 밖이라 비노출.

## 구독 한도 메시지
- Phase 0에서 미관측.
