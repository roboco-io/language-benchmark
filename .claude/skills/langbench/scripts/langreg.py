#!/usr/bin/env python3
"""언어 레지스트리 로더. languages/<key>.json 한 파일이 언어 하나의 설정 전부를 담는다.

import해서 쓰거나(`langreg.keys()`) CLI로 쓴다: keys | validate [key...] | detect <run> | get <key> <dotted.path>.
표준 라이브러리만 쓴다.
"""
import json
import os
import sys
from pathlib import Path

DEPS_KINDS = {"package-json", "pyproject-or-requirements", "go-mod", "cargo-toml", "cmake"}
SCANNER_FORMATS = {"npm", "pip", "go", "cargo", "none"}

_HERE = Path(__file__).resolve().parent


def lang_dir():
    """LB_LANG_DIR → <이 파일 디렉터리>/languages → <이 파일 디렉터리>/../languages 중 처음 존재하는 곳."""
    env = os.environ.get("LB_LANG_DIR")
    candidates = ([Path(env)] if env else []) + [_HERE / "languages", _HERE.parent / "languages"]
    for c in candidates:
        if c.is_dir():
            return c
    raise FileNotFoundError("언어 디렉터리를 찾을 수 없음: " + ", ".join(str(c) for c in candidates))


def load_all():
    entries = []
    for p in sorted(lang_dir().glob("*.json")):
        with open(p, encoding="utf-8") as f:
            entries.append(json.load(f))
    return sorted(entries, key=lambda e: e.get("order", 0))


def keys():
    return [e["key"] for e in load_all()]


def get(key):
    for e in load_all():
        if e["key"] == key:
            return e
    raise KeyError(f"알 수 없는 언어: {key}")


def _need(entry, path, typ, errors):
    """entry에서 점 경로 값을 꺼내 타입을 확인한다. 없거나 타입이 다르면 오류를 쌓고 None을 돌려준다."""
    cur = entry
    for part in path.split("."):
        if not isinstance(cur, dict) or part not in cur:
            errors.append(f"{path}: 필수 필드 없음")
            return None
        cur = cur[part]
    if not isinstance(cur, typ) or (typ is int and isinstance(cur, bool)):
        errors.append(f"{path}: 타입이 {typ.__name__}이어야 함")
        return None
    return cur


def validate(entry):
    """오류 메시지 목록을 돌려준다. 빈 목록이면 통과."""
    errors = []
    for path, typ in (("key", str), ("name", str), ("order", int), ("prompt.STACK", str), ("prompt.WORKERS", str),
                      ("stack.framework", str), ("stack.db", str), ("loc.test_pattern", str)):
        _need(entry, path, typ, errors)
    for path in ("loc.scc_names", "loc.exclude_dirs", "hints", "cleanup"):
        v = _need(entry, path, list, errors)
        if v is not None and not all(isinstance(x, str) for x in v):
            errors.append(f"{path}: 문자열 목록이어야 함")
    tc = _need(entry, "toolchain", list, errors)
    for i, t in enumerate(tc or []):
        if not isinstance(t, dict) or not isinstance(t.get("name"), str) or not isinstance(t.get("args"), list):
            errors.append(f"toolchain[{i}]: name(str)과 args(list) 필요")
    fmt = _need(entry, "scanner.format", str, errors)
    if fmt is not None and fmt not in SCANNER_FORMATS:
        errors.append(f"scanner.format: {fmt!r}는 허용되지 않음 {sorted(SCANNER_FORMATS)}")
    kind = _need(entry, "deps.kind", str, errors)
    if kind is not None and kind not in DEPS_KINDS:
        errors.append(f"deps.kind: {kind!r}는 허용되지 않음 {sorted(DEPS_KINDS)}")
    return errors


def detect(run):
    """run 이름을 '-'로 나눈 조각 중 등록된 키와 정확히 일치하는 것을 돌려준다. 없거나 둘 이상이면 ValueError."""
    known = set(keys())
    found = [s for s in run.split("-") if s in known]
    if len(found) != 1:
        raise ValueError(f"run 이름에서 언어를 하나로 정할 수 없음: {run} (일치: {found})")
    return found[0]


def main(argv):
    if not argv:
        print(__doc__, file=sys.stderr)
        return 2
    cmd, args = argv[0], argv[1:]
    try:
        if cmd == "keys":
            print(" ".join(keys()))
        elif cmd == "validate":
            bad = 0
            for e in ([get(k) for k in args] if args else load_all()):
                for m in validate(e):
                    print(f"{e.get('key', '?')}: {m}", file=sys.stderr)
                    bad = 1
            return bad
        elif cmd == "detect":
            print(detect(args[0]))
        elif cmd == "get":
            cur = get(args[0])
            for part in args[1].split("."):
                cur = cur[int(part)] if isinstance(cur, list) else cur[part]
            print(cur if isinstance(cur, str) else json.dumps(cur, ensure_ascii=False))
        else:
            print(f"알 수 없는 명령: {cmd}", file=sys.stderr)
            return 2
    except (KeyError, ValueError, IndexError) as e:
        print(e.args[0] if e.args else e, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
