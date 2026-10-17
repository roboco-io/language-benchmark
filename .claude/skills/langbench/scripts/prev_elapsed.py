#!/usr/bin/env python3
"""재개한 run의 이전 구간 wall-clock 초 합(R19). driver.sh의 prev_elapsed가 호출한다.
사용: prev_elapsed.py <driver.log> <run> <metrics-<run>.csv>   출력: 정수 초
구간 규칙은 scripts/runs_row.py run_minutes와 같다(scripts/test_runs_row.py가 같은 fixture로 대조한다, R29).
"""
import csv, re, sys
from datetime import datetime
F = "%Y-%m-%d %H:%M:%S"
TS = r"(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)"
log, run, met = sys.argv[1:4]
try:
    text = open(log).read()
except OSError:
    print(0)
    sys.exit()
name = re.escape(run)
start_re = re.compile(r"^=== RUN " + name + r" \(.*\) start: " + TS + " ===")
end_re = re.compile(r"^=== RUN " + name + r" finished: " + TS + " ")
held_re = re.compile(r"^=== RUN " + name + r" held: ")
segs = []
for line in text.splitlines():
    if held_re.match(line):
        segs = []
    elif start_re.match(line):
        segs.append([datetime.strptime(start_re.match(line).group(1), F), None])
    elif end_re.match(line) and segs and segs[-1][1] is None:
        segs[-1][1] = datetime.strptime(end_re.match(line).group(1), F)
stamps = []
try:
    for row in csv.reader(open(met)):
        try:
            stamps.append(datetime.strptime(row[1], F))
        except (IndexError, ValueError):
            pass
except OSError:
    pass
total = 0.0
for k, (start, end) in enumerate(segs):
    if end is None:
        nxt = segs[k + 1][0] if k + 1 < len(segs) else datetime.max
        end = max([t for t in stamps if start <= t < nxt] or [start])
    total += (end - start).total_seconds()
print(int(total))
