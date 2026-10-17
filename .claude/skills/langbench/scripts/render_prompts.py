#!/usr/bin/env python3
"""template.md의 {{STACK}}·{{WORKERS}}를 stacks.json 값으로 치환해 PROMPT-<lang>.md를 만든다.

인자 없음: 5개 파일을 쓴다. --check: 디스크 파일이 렌더 결과와 다르면 종료 코드 1.
바이트 그대로 다룬다(newline 변환 없음, 원문은 끝 개행이 없다).
"""
import json
import os
import sys

ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "assets")


def render():
    with open(os.path.join(ASSETS, "prompt", "template.md"), newline="") as f:
        template = f.read()
    with open(os.path.join(ASSETS, "prompt", "stacks.json")) as f:
        stacks = json.load(f)
    return {lang: template.replace("{{STACK}}", v["STACK"]).replace("{{WORKERS}}", v["WORKERS"])
            for lang, v in stacks.items()}


def main():
    check = "--check" in sys.argv[1:]
    bad = 0
    for lang, text in render().items():
        path = os.path.join(ASSETS, f"PROMPT-{lang}.md")
        if check:
            try:
                with open(path, newline="") as f:
                    same = f.read() == text
            except FileNotFoundError:
                same = False
            if not same:
                print(f"불일치: PROMPT-{lang}.md", file=sys.stderr)
                bad = 1
        else:
            with open(path, "w", newline="") as f:
                f.write(text)
    return bad


if __name__ == "__main__":
    sys.exit(main())
