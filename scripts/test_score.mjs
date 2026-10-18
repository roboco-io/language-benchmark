// node scripts/test_score.mjs — dashboard/score.js의 정규화·종합 점수 검증
import assert from "node:assert/strict";
import { createRequire } from "node:module";
const { normalize, composite, sampleLabel, defaultWeights, presetWeights, constantMetrics, modelDiff, ranks } = createRequire(import.meta.url)("../dashboard/score.js");

assert.deepEqual(normalize([1, 2, 3], "lower"), [1, 0.5, 0]);
assert.deepEqual(normalize([1, 2, 3], "higher"), [0, 0.5, 1]);
assert.deepEqual(normalize([5, null], "higher"), [1, null]);
assert.deepEqual(normalize([2, 2], "lower"), [1, 1]);
assert.deepEqual(normalize([null, null], "lower"), [null, null]);

const metrics = [{ key: "rps", better: "higher" }, { key: "wall_min", better: "lower" }];
const cells = {
  go: { rps: 300, wall_min: 10 },
  rust: { rps: 100, wall_min: 5 },
  cpp: { rps: null, wall_min: null },
};
const equal = composite(cells, metrics, { rps: 1, wall_min: 1 });
assert.equal(equal.go, 0.5);
assert.equal(equal.rust, 0.5);
assert.equal(equal.cpp, null);
assert.deepEqual(composite(cells, metrics, { rps: 0, wall_min: 0 }), { go: null, rust: null, cpp: null });
assert.equal(composite(cells, metrics, { rps: 3, wall_min: 1 }).go, 0.75);
// 결과물 지표 칸의 표본 수: 측정 run 수 / 전체 run 수, 실패만 있어도 표시
assert.equal(sampleLabel({ n: 3, passed: 0, rps_n: 0 }, { key: "rps", group: "product" }), "0/3");
assert.equal(sampleLabel({ n: 3, passed: 2, rps_n: 2 }, { key: "rps", group: "product" }), "2/3");
assert.equal(sampleLabel({ n: 0, passed: 0, rps_n: 0 }, { key: "rps", group: "product" }), "");
assert.equal(sampleLabel({ n: 3, passed: 3, wall_min_n: 3 }, { key: "wall_min", group: "process" }), "");
// 기본 가중치: METRICS 전 키가 1
const dm = [{ key: "rps" }, { key: "wall_min" }, { key: "loc" }];
assert.deepEqual(defaultWeights(dm), { rps: 1, wall_min: 1, loc: 1 });
assert.ok(Object.values(defaultWeights(dm)).every((w) => w === 1));
// 프리셋: prereg는 전부 1, 묶음 프리셋은 목록 밖 지표 0, balanced는 세 묶음 합이 각각 1
const pm = ["wall_min", "cost_per_pass", "followup_min", "token_proxy", "followup_token_proxy", "rps", "p95_ms",
  "peak_rss_mb", "p99_ms", "idle_rss_mb", "cold_start_ms", "image_mb", "soak_mem_growth_mb", "error_rate",
  "soak_error_rate", "vulns", "followup_pass", "deps", "loc", "build_sec", "pass_rate", "iterations"].map((key) => ({ key }));
assert.ok(Object.values(presetWeights("prereg", pm)).every((w) => w === 1));
const sp = presetWeights("speed", pm);
assert.equal(sp.wall_min, 3); assert.equal(sp.rps, 0); assert.equal(sp.pass_rate, 0);
const bal = presetWeights("balanced", pm);
const sum = Object.values(bal).reduce((a, b) => a + b, 0);
assert.ok(Math.abs(sum - 3) < 1e-9);
assert.ok(Math.abs(bal.followup_min - (2 / 9 + 1 / 8)) < 1e-9); // speed 합 9, maintain 합 8에 함께 들어 있다
assert.equal(bal.pass_rate, 0);
// 상수 지표: 모든 언어·모든 묶음에서 같은 값이면 숨김, 묶음 사이에서만 달라도 남김
const s1 = { go: { pass_rate: 1, rps: 1 }, rust: { pass_rate: 1, rps: 2 } };
const s2 = { go: { pass_rate: 1, rps: 3 }, rust: { pass_rate: 1, rps: 3 } };
const cm = [{ key: "pass_rate" }, { key: "rps" }];
assert.deepEqual(constantMetrics([s1], cm), ["pass_rate"]);
assert.deepEqual(constantMetrics([s2], cm), ["pass_rate", "rps"]);
assert.deepEqual(constantMetrics([s1, s2], cm), ["pass_rate"]);
assert.deepEqual(constantMetrics([{ go: { rps: null } }], [{ key: "rps" }]), []);
// 모델 차이: b(Opus)가 a(Sonnet) 대비 몇 %인지와 더 나은 쪽
assert.deepEqual(modelDiff(100, 120, "higher"), { pct: 20, better: "b" });
assert.deepEqual(modelDiff(100, 120, "lower"), { pct: 20, better: "a" });
assert.deepEqual(modelDiff(5, 5, "lower"), { pct: 0, better: null });
assert.deepEqual(modelDiff(null, 5, "lower"), { pct: null, better: null });
assert.deepEqual(ranks({ go: 0.7, rust: 0.9, cpp: null }), { go: 2, rust: 1, cpp: null });
console.log("test_score: all passed");
