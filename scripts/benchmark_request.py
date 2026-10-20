"""벤치마크 요청 이슈(.github/ISSUE_TEMPLATE/benchmark-request.yml)가 이미 결과가 있는 언어+프레임워크인지 판정한다.

결과가 있는 조합은 analysis/metrics/runs.csv에 행이 있는 언어와, 그 언어의 레지스트리
(.claude/skills/langbench/languages/<key>.json) stack.framework다. 같은 언어라도 프레임워크가 다르면 중복이 아니다.

사용(GitHub Actions): python3 scripts/benchmark_request.py <이벤트 JSON> <출력 파일>
중복이면 출력 파일에 안내 댓글(한국어·영어)을 쓰고 종료 코드 0, 중복이 아니면 출력 파일을 만들지 않는다.
"""
import csv
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPO_URL = "https://github.com/roboco-io/language-benchmark"
DASHBOARD_URL = "https://roboco.io/language-benchmark/"

# 이슈 폼 제목(### 다음 줄) -> 필드
FIELDS = {"언어 / Language": "language", "웹 프레임워크 / Web framework": "framework",
          "DB 접근 라이브러리 / DB library": "db"}

LANGUAGE_ALIASES = {"c#": "csharp", "csharp": "csharp", "csharp.net": "csharp", "dotnet": "csharp", ".net": "csharp",
                    "c++": "cpp", "cpp": "cpp", "cplusplus": "cpp", "golang": "go", "ts": "typescript",
                    "py": "python", "python3": "python"}


def parse_form(body):
    """GitHub 이슈 폼 본문(### 제목 + 값)을 필드 dict로 바꾼다. 응답이 없으면 빈 문자열."""
    out = {}
    for title, value in re.findall(r"^###\s*(.+?)\s*\n(.*?)(?=^###\s|\Z)", body or "", re.S | re.M):
        key = FIELDS.get(title.strip())
        if key:
            value = value.strip()
            out[key] = "" if value == "_No response_" else value
    return out


def _lang_key(text, names):
    t = re.sub(r"\s+", "", (text or "").lower())
    if not t:
        return None
    if t in LANGUAGE_ALIASES:
        return LANGUAGE_ALIASES[t]
    return names.get(t)


def _framework(text):
    """괄호 앞 이름만 보고, 영숫자만 남기고, 끝의 'framework'를 뗀다(Play Framework -> play)."""
    t = re.sub(r"[^a-z0-9]", "", (text or "").split("(")[0].lower())
    return t[:-len("framework")] if t.endswith("framework") and len(t) > len("framework") else t


def _same_framework(a, b):
    a, b = _framework(a), _framework(b)
    if len(a) < 3 or len(b) < 3:
        return False
    return a == b or a in b or b in a


def find_existing(language, framework, root=ROOT):
    """결과가 있는 같은 언어+프레임워크면 {language, framework, exp, report}, 아니면 None."""
    reg = {}
    for p in sorted((root / ".claude" / "skills" / "langbench" / "languages").glob("*.json")):
        d = json.loads(p.read_text())
        reg[d["key"]] = d
    names = {re.sub(r"\s+", "", d["name"].lower()): k for k, d in reg.items()}
    names.update({k: k for k in reg})
    key = _lang_key(language, names)
    if key not in reg or not _same_framework(framework, reg[key]["stack"]["framework"]):
        return None
    with open(root / "analysis" / "metrics" / "runs.csv", newline="") as f:
        exps = sorted({r["exp"] for r in csv.DictReader(f) if r["language"] == key})
    if not exps:
        return None
    folders = {}
    for p in (root / "experiments").glob("*/experiment.json"):
        d = json.loads(p.read_text())
        folders[d["exp"]] = d["folder"]
    exp = exps[-1]
    return {"language": reg[key]["name"], "framework": reg[key]["stack"]["framework"], "exp": exp,
            "report": f"{REPO_URL}/blob/main/experiments/{folders.get(exp, '')}/report.md"}


def message(m):
    combo = f"{m['language']} + {m['framework']}"
    return (f"이 요청은 이미 측정한 조합(**{combo}**)이라 자동으로 닫습니다. "
            f"결과는 [{m['exp']} 보고서]({m['report']})와 [대시보드]({DASHBOARD_URL})에서 볼 수 있습니다. "
            "같은 언어의 다른 프레임워크를 원하시면 프레임워크를 바꿔 새 이슈를 열어 주세요. "
            "판정이 잘못되었다고 생각하시면 이 이슈에 댓글로 알려 주세요.\n\n"
            f"This request was closed automatically because **{combo}** has already been benchmarked. "
            f"See the [{m['exp']} report]({m['report']}) and the [dashboard]({DASHBOARD_URL}). "
            "For a different framework in the same language, please open a new issue with that framework. "
            "If you think this check is wrong, please leave a comment on this issue.\n")


def main(event_path, out_path):
    issue = json.loads(Path(event_path).read_text())["issue"]
    form = parse_form(issue.get("body"))
    m = find_existing(form.get("language"), form.get("framework"))
    if m:
        Path(out_path).write_text(message(m))
        print(f"duplicate: {m['language']} + {m['framework']} ({m['exp']})")
    else:
        print(f"not duplicate: {form.get('language')!r} + {form.get('framework')!r}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
