// RealWorld 혼합 부하. 환경변수: SEED(seed.json 경로), BASE(http://host:port), VUS, DURATION(예: 30s)
// 비율: 목록 35, 단건 20, 태그 10, 피드 10, 코멘트 목록 15, 아티클 작성 4, 코멘트 작성 3, 즐겨찾기 3 (%)
import http from "k6/http";

// SEED가 없으면(k6 inspect 등) 빈 시드로 로드만 한다. 실제 실행에는 SEED가 필수다.
const seed = __ENV.SEED ? JSON.parse(open(__ENV.SEED)) : { users: [], slugs: [], tags: [] };
const BASE = __ENV.BASE;
const JSON_H = { "Content-Type": "application/json" };

// 2xx만 성공으로 본다(404·401 등은 http_req_failed에 집계)
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }));

export const options = {
  vus: parseInt(__ENV.VUS || "32", 10),
  duration: __ENV.DURATION || "30s",
  summaryTrendStats: ["p(50)", "p(95)", "p(99)"],
};

function auth(user) {
  return { headers: Object.assign({ Authorization: "Token " + user.token }, JSON_H) };
}

function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

export default function () {
  const user = pick(seed.users);
  const slug = pick(seed.slugs);
  const r = Math.random() * 100;
  if (r < 35) {
    http.get(`${BASE}/api/articles?limit=20&offset=${Math.floor(Math.random() * 25) * 20}`);
  } else if (r < 55) {
    http.get(`${BASE}/api/articles/${slug}`);
  } else if (r < 65) {
    http.get(`${BASE}/api/tags`);
  } else if (r < 75) {
    http.get(`${BASE}/api/articles/feed`, auth(user));
  } else if (r < 90) {
    http.get(`${BASE}/api/articles/${slug}/comments`);
  } else if (r < 94) {
    const n = Math.floor(Math.random() * 1e9);
    http.post(`${BASE}/api/articles`, JSON.stringify({ article: {
      title: `load ${n}`, description: "load", body: "load body", tagList: [pick(seed.tags)] } }), auth(user));
  } else if (r < 97) {
    http.post(`${BASE}/api/articles/${slug}/comments`, JSON.stringify({ comment: { body: "load comment" } }), auth(user));
  } else {
    http.post(`${BASE}/api/articles/${slug}/favorite`, null, auth(user));
  }
}
