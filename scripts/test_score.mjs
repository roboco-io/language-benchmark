// node scripts/test_score.mjs — dashboard/score.js의 정규화·종합 점수 검증
import assert from "node:assert/strict";
import { createRequire } from "node:module";
const { normalize, composite, sampleLabel, defaultWeights } = createRequire(import.meta.url)("../dashboard/score.js");

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
console.log("test_score: all passed");
