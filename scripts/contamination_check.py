#!/usr/bin/env python3
"""run 세션에서 자기 앱 디렉터리 밖의 하네스 경로를 참조한 도구 호출을 찾는다(R24).

사용: python3 scripts/contamination_check.py <BASE> <run> [--sessions DIR]
run은 세션을 만든 run 이름이다(후속 과제는 <run>-fu). 세션은 <BASE>/sessions-<run>(없으면
~/.claude/projects/<app 경로 인코딩>)에서 서브에이전트 세션까지 재귀로 읽는다.
assistant 기록의 tool_use 입력에서 <BASE> 아래 경로(절대 경로, ~/ 형식, 실경로)와
../app-*·../harness-hurl·../followup-hurl·../metrics-*·../sessions-* 상대 경로를 찾는다.
자기 앱(<BASE>/app-<run>) 안의 경로는 제외한다. app-<run>-fu는 app-<run>과 다른 디렉터리로 본다.
출력: 참조마다 "<파일>:<줄>: <도구>: <경로>"와 마지막 "hits: N". 참조가 있으면 종료 코드 1, 없으면 0,
세션이 없으면 2.
"""
import argparse
import json
import os
import re
import sys
from pathlib import Path

RELATIVE = re.compile(r"\.\./((?:app-|metrics-|sessions-)[^\s\"'`;|&<>()/\\]*|harness-hurl|followup-hurl)")
PATH_TAIL = r"(/[^\s\"'`;|&<>()\\]*)?"


def prefixes(base):
    out = {base, os.path.realpath(base)}
    home = os.path.expanduser("~")
    for b in list(out):
        if b.startswith(home + "/"):
            out.add("~" + b[len(home):])
            out.add("$HOME" + b[len(home):])
    return sorted(out, key=len, reverse=True)


def session_dir(base, run):
    archived = Path(base) / ("sessions-%s" % run)
    if archived.is_dir():
        return archived
    enc = re.sub(r"[/.]", "-", "%s/app-%s" % (base, run))
    return Path(os.path.expanduser("~")) / ".claude" / "projects" / enc


def references(text, base, run):
    """tool_use 입력 문자열에서 자기 앱 밖의 하네스 경로를 돌려준다."""
    own = "app-" + run
    found = []

    def absolute(m, prefix):
        rest = m.group(1) or ""
        if os.path.normpath("." + rest).split(os.sep)[0] != own:
            found.append(prefix + rest)
        return "\0"  # 찾은 경로를 지워 짧은 접두사(실경로 등)나 상대 경로 규칙으로 다시 세지 않는다

    for prefix in prefixes(base):
        # 접두사 바로 뒤가 경로 구분자·끝이어야 한다(langbench-exp001이 langbench-exp0012와 섞이지 않게)
        pat = re.escape(prefix) + r"(?=[/\s\"'`;|&<>()\\]|$)" + PATH_TAIL
        text = re.sub(pat, lambda m, p=prefix: absolute(m, p), text)
    for m in RELATIVE.finditer(text):
        if m.group(1) != own:
            found.append("../" + m.group(1))
    return found


def scan(base, run, sessions):
    hits = []
    for path in sorted(Path(sessions).rglob("*.jsonl")):
        for number, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            try:
                record = json.loads(line)
            except ValueError:
                continue
            message = record.get("message") if isinstance(record, dict) else None
            if record.get("type") != "assistant" or not isinstance(message, dict):
                continue
            for item in message.get("content") or []:
                if not isinstance(item, dict) or item.get("type") != "tool_use":
                    continue
                text = json.dumps(item.get("input"), ensure_ascii=False)
                for ref in dict.fromkeys(references(text, base, run)):
                    hits.append("%s:%d: %s: %s" % (path, number, item.get("name"), ref))
    return hits


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("base")
    ap.add_argument("run")
    ap.add_argument("--sessions", help="세션 디렉터리(기본: <BASE>/sessions-<run>, 없으면 ~/.claude/projects 원본)")
    args = ap.parse_args()
    base = os.path.abspath(args.base).rstrip("/")
    sessions = Path(args.sessions) if args.sessions else session_dir(base, args.run)
    if not sessions.is_dir():
        print("세션 없음: %s" % sessions, file=sys.stderr)
        return 2
    hits = scan(base, args.run, sessions)
    for h in hits:
        print(h)
    print("hits: %d" % len(hits))
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main())
