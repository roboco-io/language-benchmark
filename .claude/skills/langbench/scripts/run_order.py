#!/usr/bin/env python3
"""run 실행 순서(R26). 사용: run_order.py <seed> "<CONDITIONS>" "<LANGS>" <N>
조건×언어×반복 run 목록(<조건>-<언어>-<n>)을 seed로 결정적으로 섞어 한 줄에 하나씩 출력한다.
조건 순서를 고정하면 먼저 도는 모델이 Docker·의존성 캐시를 채우는 비용을 늘 떠안으므로, 순서를 섞어 모델·언어·반복 간 캐시 상태를 균형 있게 한다.
"""
import random
import sys


def run_order(seed, conditions, langs, n):
    runs = ["%s-%s-%d" % (c.split(":", 1)[0], l, r)
            for r in range(1, n + 1) for l in langs.split() for c in conditions.split()]
    random.Random(seed).shuffle(runs)
    return runs


if __name__ == "__main__":
    if len(sys.argv) != 5:
        sys.exit(__doc__.splitlines()[0])
    print("\n".join(run_order(int(sys.argv[1]), sys.argv[2], sys.argv[3], int(sys.argv[4]))))
