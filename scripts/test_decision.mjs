import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync, existsSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
const { summarize, evidenceLinks, scope, tradeoffs } = createRequire(import.meta.url)('../dashboard/decision.js');
const rows = [
  { exp: 'EXP-001', run: 'sonnet55-go-1', language: 'go', model: 'sonnet55', pass: '1', rps: '0', cost_usd: '2' },
  { exp: 'EXP-001', run: 'sonnet55-go-2', language: 'go', model: 'sonnet55', pass: '1', rps: '10', cost_usd: '3' },
  { exp: 'EXP-001', run: 'sonnet55-go-3', language: 'go', model: 'sonnet55', pass: '0', rps: '999', cost_usd: '4' },
  { exp: 'EXP-001', run: 'opus55-go-1', language: 'go', model: 'opus55', pass: '1', rps: '100', cost_usd: '1' },
];
const s = summarize(rows, 'go', 'sonnet55', 'rps');
assert.deepEqual({ median:s.median, min:s.min, max:s.max, measured:s.measured, total:s.total },
  { median:5, min:0, max:10, measured:2, total:3 });
assert.deepEqual(s.values, [0,10]);
assert.equal(summarize(rows, 'go', 'sonnet55', 'cost_per_pass').median, 4.5);
assert.equal(summarize(rows.map(r => ({...r,cost_usd:''})), 'go', 'sonnet55', 'cost_per_pass').median, null);
assert.equal(summarize(rows, 'go', 'sonnet55', 'missing').median, null);
assert.equal(summarize(rows, 'rust', 'sonnet55', 'rps').total, 0);
assert.equal(summarize(rows, 'go', 'sonnet55', 'pass_rate').median, 2/3);
assert.equal(summarize([{...rows[0], rps:null}], 'go', 'sonnet55', 'rps').measured, 0);
assert.deepEqual(scope(rows), { total:4, passed:3, experiments:1 });
const links = evidenceLinks(rows[0]);
assert.ok(links.source.endsWith('/runs/apps/sonnet55-go-1'));
assert.ok(links.report.endsWith('/report.md'));
assert.ok(links.deviations.endsWith('/runs/deviations.md'));
assert.equal(evidenceLinks({...rows[0],exp:'EXP-999'}), null);
const ms=[{key:'rps',better:'higher'},{key:'wall_min',better:'lower'}];
assert.deepEqual(tradeoffs({go:{rps:10,wall_min:20},rust:{rps:5,wall_min:10}}, 'go', ms, {rps:1,wall_min:1}),
  {strength:'rps',burden:'wall_min'});
assert.deepEqual(tradeoffs({go:{rps:10},rust:{rps:10}}, 'go', ms, {rps:1}), {strength:null,burden:null});
// 새 UI가 기존 공개 집계값을 보존하고 실제 증거 파일로 연결하는지 검사한다.
const context={window:{}};
runInNewContext(readFileSync(new URL('../dashboard/data.js',import.meta.url),'utf8'),context);
const bench=context.window.BENCH;
for (const lang of bench.languages) for (const model of bench.models) for (const m of bench.metrics) {
  const actual=summarize(bench.runs,lang.key,model.key,m.key).median;
  const expected=bench.cells[lang.key][model.key][m.key];
  assert.ok(actual === expected || Math.abs(actual-expected) < 1e-10, `${lang.key}/${model.key}/${m.key}: ${actual} vs ${expected}`);
}
for (const row of bench.runs) for (const [kind,url] of Object.entries(evidenceLinks(row))) {
  const path=url.replace(/^https:\/\/github.com\/roboco-io\/language-benchmark\/(tree|blob)\/main\//,'');
  assert.ok(existsSync(new URL('../'+path,import.meta.url)), `${kind}: ${path}`);
}
console.log('test_decision: all passed');
