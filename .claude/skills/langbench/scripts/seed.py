#!/usr/bin/env python3
"""고정 시드로 RealWorld API에 부하 측정용 데이터를 넣는다.
사용: seed.py <base_url> <out.json>
사용자 50, 사용자당 아티클 10(총 500), 태그 풀 20 중 아티클당 3, 앞 100개 아티클에 코멘트 5개씩(500),
사용자마다 다음 5명 팔로우(250), 사용자마다 아티클 5개 즐겨찾기(250).
out.json = {"users":[{"username","token","favorited":[slug...]}], "slugs":[...], "tags":[...]}. 요청이 하나라도 실패하면 종료 코드 1.
"""
import json
import random
import sys
import urllib.error
import urllib.request

N_USERS, PER_USER, N_TAGS, FOLLOWS, FAVS, COMMENTED, COMMENTS = 50, 10, 20, 5, 5, 100, 5
TAGS = ["tag%02d" % i for i in range(N_TAGS)]


class Failed(Exception):
    pass


def call(base, method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    if data is not None:  # 본문 없는 요청에 Content-Type을 붙이면 Fastify 등이 400을 준다
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Token " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read() or b"{}")
    except (urllib.error.URLError, OSError, ValueError) as e:
        raise Failed("%s %s: %s" % (method, path, e))


def main(base, out):
    rng = random.Random(20261017)
    users = []
    for i in range(N_USERS):
        name = "lb%d" % i
        r = call(base, "POST", "/api/users",
                 {"user": {"username": name, "email": name + "@lb.test", "password": "password123"}})
        users.append({"username": name, "token": r["user"]["token"]})
    slugs = []
    for u in users:
        for j in range(PER_USER):
            r = call(base, "POST", "/api/articles", {"article": {
                "title": "%s article %d" % (u["username"], j),
                "description": "seed article %d" % j,
                "body": "seed body " * 20,
                "tagList": rng.sample(TAGS, 3)}}, u["token"])
            slugs.append(r["article"]["slug"])
    for k, slug in enumerate(slugs[:COMMENTED]):
        for c in range(COMMENTS):
            u = users[(k + c + 1) % N_USERS]
            call(base, "POST", "/api/articles/%s/comments" % slug,
                 {"comment": {"body": "seed comment %d" % c}}, u["token"])
    for i, u in enumerate(users):
        for d in range(1, FOLLOWS + 1):
            call(base, "POST", "/api/profiles/%s/follow" % users[(i + d) % N_USERS]["username"], None, u["token"])
        u["favorited"] = rng.sample(slugs, FAVS)  # load.js가 이미 즐겨찾기한 slug를 피하는 데 쓴다
        for slug in u["favorited"]:
            call(base, "POST", "/api/articles/%s/favorite" % slug, None, u["token"])
    with open(out, "w") as f:
        json.dump({"users": users, "slugs": slugs, "tags": TAGS}, f)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("사용: seed.py <base_url> <out.json>")
    try:
        main(sys.argv[1].rstrip("/"), sys.argv[2])
    except (Failed, KeyError) as e:
        print("seed 실패: %s" % e, file=sys.stderr)
        sys.exit(1)
