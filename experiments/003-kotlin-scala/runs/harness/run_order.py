#!/usr/bin/env python3
"""run 실행 순서(R26, R27). 사용: run_order.py <seed> "<CONDITIONS>" "<LANGS>" <N>
반복 번호 블록(1, 2, …, N)을 차례로 두고, 블록 안의 조건×언어 run(<조건>-<언어>-<n>)을 블록별 seed
"<seed>-<n>"으로 결정적으로 섞어 한 줄에 하나씩 출력한다. 반복 블록 교차는 설계 4.3의 규칙이다.
블록 안에서 조건 순서를 고정하면 먼저 도는 모델이 Docker·의존성 캐시를 채우는 비용을 늘 떠안으므로 섞는다.
"""
import random
import sys


def run_order(seed, conditions, langs, n):
    out = []
    for rep in range(1, n + 1):
        block = ["%s-%s-%d" % (c.split(":", 1)[0], l, rep) for l in langs.split() for c in conditions.split()]
        random.Random("%s-%d" % (seed, rep)).shuffle(block)
        out += block
    return out


if __name__ == "__main__":
    if len(sys.argv) != 5:
        sys.exit(__doc__.splitlines()[0])
    print("\n".join(run_order(int(sys.argv[1]), sys.argv[2], sys.argv[3], int(sys.argv[4]))))
