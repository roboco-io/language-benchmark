// RealWorld 혼합 부하. 환경변수: SEED(seed.json 경로), BASE(http://host:port), VUS, DURATION(예: 30s)
// 비율: 목록 35, 단건 20, 태그 10, 피드 10, 코멘트 목록 15, 아티클 작성 4, 코멘트 작성 3, 즐겨찾기 3 (%)
import http from "k6/http";
import { Counter } from "k6/metrics";

// SEED가 없으면(k6 inspect 등) 빈 시드로 로드만 한다. 실제 실행에는 SEED가 필수다.
const seed = __ENV.SEED ? JSON.parse(open(__ENV.SEED)) : { users: [{ username: "", token: "", favorited: [] }], slugs: [""], tags: [""] };
const BASE = __ENV.BASE;

// 실패 원인 확인용 상태 클래스별 카운터(--summary-export에 포함된다)
const status2xx = new Counter("status_2xx");
const status4xx = new Counter("status_4xx");
const status5xx = new Counter("status_5xx");

// 2xx만 성공으로 본다(404·401 등은 http_req_failed에 집계)
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }));

export const options = {
  vus: parseInt(__ENV.VUS || "32", 10),
  duration: __ENV.DURATION || "30s",
  summaryTrendStats: ["p(50)", "p(95)", "p(99)"],
};

// body가 있을 때만 Content-Type을 붙인다(본문 없는 요청에 붙이면 Fastify 등이 400을 준다)
function params(user, hasBody) {
  const headers = {};
  if (user) headers.Authorization = "Token " + user.token;
  if (hasBody) headers["Content-Type"] = "application/json";
  return { headers };
}

function send(method, url, body, user) {
  const res = http.request(method, url, body === null ? null : JSON.stringify(body), params(user, body !== null));
  // 0도 add해서 한 번도 안 나온 클래스도 summary에 count 0으로 남긴다
  status2xx.add(res.status >= 200 && res.status < 300 ? 1 : 0);
  status4xx.add(res.status >= 400 && res.status < 500 ? 1 : 0);
  status5xx.add(res.status >= 500 ? 1 : 0);
  return res;
}

function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

export default function () {
  // VU마다 사용자 1명에 고정한다
  const user = seed.users[(__VU - 1) % seed.users.length];
  const slug = pick(seed.slugs);
  const r = Math.random() * 100;
  if (r < 35) {
    send("GET", `${BASE}/api/articles?limit=20&offset=${Math.floor(Math.random() * 25) * 20}`, null);
  } else if (r < 55) {
    send("GET", `${BASE}/api/articles/${slug}`, null);
  } else if (r < 65) {
    send("GET", `${BASE}/api/tags`, null);
  } else if (r < 75) {
    send("GET", `${BASE}/api/articles/feed`, null, user);
  } else if (r < 90) {
    send("GET", `${BASE}/api/articles/${slug}/comments`, null);
  } else if (r < 94) {
    const n = Math.floor(Math.random() * 1e9);
    send("POST", `${BASE}/api/articles`, { article: {
      title: `load ${n}`, description: "load", body: "load body", tagList: [pick(seed.tags)] } }, user);
  } else if (r < 97) {
    send("POST", `${BASE}/api/articles/${slug}/comments`, { comment: { body: "load comment" } }, user);
  } else {
    // 즐겨찾기 쌍: 시드 때 이 사용자가 즐겨찾기하지 않은 slug에 favorite 후 unfavorite → 재즐겨찾기 없이 상태 복원
    let fav = pick(seed.slugs);
    while (user.favorited.indexOf(fav) >= 0) fav = pick(seed.slugs);
    send("POST", `${BASE}/api/articles/${fav}/favorite`, null, user);
    send("DELETE", `${BASE}/api/articles/${fav}/favorite`, null, user);
  }
}
