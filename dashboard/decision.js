// 언어 선택용 관측 요약. 점수·판정은 score.js와 원 보고서가 담당한다.
(function (root) {
  const number = (v) => v === null || v === undefined || String(v).trim() === '' || !Number.isFinite(+v) ? null : +v;
  function summarize(rows, language, model, key) {
    const selected = rows.filter(r => r.language === language && r.model === model);
    const passed = selected.filter(r => +r.pass === 1);
    const values = passed.map(r => number(r[key])).filter(v => v !== null).sort((a,b) => a-b);
    let median = values.length ? (values[Math.floor((values.length-1)/2)] + values[Math.floor(values.length/2)]) / 2 : null;
    if (key === 'cost_per_pass') {
      const costs = selected.map(r => number(r.cost_usd));
      median = passed.length && costs.every(v => v !== null) ? costs.reduce((a,b) => a+b,0) / passed.length : null;
    }
    if (key === 'pass_rate') median = selected.length ? passed.length / selected.length : null;
    if (key === 'followup_pass') median = values.length ? values.reduce((a,b) => a+b,0) / values.length : null;
    return { median, min: values.length ? values[0] : null, max: values.length ? values.at(-1) : null,
      values, measured: values.length, total: selected.length, passed: passed.length };
  }
  function scope(rows) {
    return { total:rows.length, passed:rows.filter(r => +r.pass === 1).length, experiments:new Set(rows.map(r => r.exp)).size };
  }
  // 증거 폴더는 BENCH.experiments(experiment.json에서 생성)에서 읽는다.
  function evidenceLinks(run, experiments) {
    const folder = experiments?.[run.exp]?.folder;
    if (!folder) return null;
    const base = 'https://github.com/roboco-io/language-benchmark';
    const path = `experiments/${folder}`;
    return { source:`${base}/tree/main/${path}/runs/apps/${encodeURIComponent(run.run)}`,
      report:`${base}/blob/main/${path}/report.md`, deviations:`${base}/blob/main/${path}/runs/deviations.md`,
      raw:`${base}/tree/main/${path}/runs/fargate/raw/${encodeURIComponent(run.run)}`,
      followup:`${base}/tree/main/${path}/runs/apps/${encodeURIComponent(run.run)}-fu`,
      csv:`${base}/blob/main/analysis/metrics/runs.csv` };
  }
  // 언어들이 속한 실험 중 날짜가 가장 이른 실험(기준선)을 반환한다. 날짜가 같으면 실험 ID 순이다.
  function baselineExp(languages, experiments) {
    const ids = [...new Set(languages.map(l => l.exp))].filter(id => experiments?.[id]?.date);
    ids.sort((a, b) => experiments[a].date.localeCompare(experiments[b].date) || a.localeCompare(b));
    return ids[0] ?? null;
  }
  // 현재 목적의 지표에서 다른 언어에 대한 상대적인 강점과 부담을 반환한다.
  // 강점은 최고값, 부담은 최저값인 지표다. 비교 언어의 절반 넘게 같은 값을 가지면 구별 정보가 없으므로 제외하고,
  // 중간 순위는 어느 쪽에도 넣지 않는다. 후보가 여럿이면 가중치, 다음 값과의 정규화 간격 순으로 고른다.
  function tradeoffs(cells, language, metrics, weights) {
    const strengths = [], burdens = [];
    for (const m of metrics) {
      if (!(weights[m.key] > 0)) continue;
      const vals = Object.values(cells).map(c => number(c[m.key])).filter(v => v !== null);
      const value = number(cells[language]?.[m.key]);
      const min = Math.min(...vals), max = Math.max(...vals);
      if (value === null || vals.length < 2 || max === min) continue;
      if (vals.filter(v => v === value).length > Math.max(1, Math.floor(vals.length / 2))) continue;
      const others = vals.filter(v => v !== value);
      const gap = Math.min(...others.map(v => Math.abs(v - value))) / (max - min);
      const best = m.better === 'higher' ? max : min, worst = m.better === 'higher' ? min : max;
      const candidate = {key:m.key, weight:weights[m.key], gap};
      if (value === best) strengths.push(candidate);
      else if (value === worst) burdens.push(candidate);
    }
    const pick = list => list.sort((a,b) => b.weight-a.weight || b.gap-a.gap)[0]?.key ?? null;
    return { strength:pick(strengths), burden:pick(burdens) };
  }
  // 언어별 막대 길이(0-1, 1이 좋음). 1위 값에 대한 비율이라 격차 크기가 그대로 보인다.
  // 1위 값이 0 이하이면 비율이 성립하지 않으므로 최솟값–최댓값 정규화로 대신한다.
  function bars(values, better) {
    const vals = Object.values(values).map(number).filter(v => v !== null);
    const min = Math.min(...vals), max = Math.max(...vals), best = better === 'higher' ? max : min;
    return Object.fromEntries(Object.entries(values).map(([k, raw]) => {
      const v = number(raw);
      if (v === null) return [k, null];
      if (max === min) return [k, 1];
      if (best > 0) return [k, better === 'higher' ? v / best : best / v];
      const t = (v - min) / (max - min);
      return [k, better === 'higher' ? t : 1 - t];
    }));
  }
  // 두 언어 대결: 중앙값이 나은 쪽이 승자이고, 배수는 큰 값÷작은 값이다. 값이 없거나 0 이하이면 배수를 구하지 않는다.
  // 3회 반복 범위가 서로 겹치면 overlap이다(겹침은 동등하다는 증명이 아니라 주의 표시다).
  function duel(a, b, better, rangeA, rangeB) {
    const x = number(a), y = number(b);
    if (x === null || y === null) return { winner:null, ratio:null, overlap:false, measured:false };
    const ratio = x > 0 && y > 0 ? Math.max(x, y) / Math.min(x, y) : null;
    const overlap = !!(rangeA && rangeB) && Math.max(rangeA[0], rangeB[0]) <= Math.min(rangeA[1], rangeB[1]);
    // 배수가 1.0배로 반올림되는 차이(1.05배 미만)는 동일로 본다. 0 대 0도 동일이다(측정된 값).
    const tie = x === y || (ratio !== null && ratio < 1.05);
    const winner = tie ? null : (better === 'higher' ? x > y : x < y) ? 'a' : 'b';
    return { winner, ratio, overlap, measured:true };
  }
  // 대결 결과의 종류: 'notMeasured'(한쪽이라도 빈 칸), 'same'(동일), 'win'(승자 있음).
  function duelKind(d) {
    return !d.measured ? 'notMeasured' : d.winner === null ? 'same' : 'win';
  }
  // 대결 막대 길이(0-1). 4배에서 가득 찬다.
  function duelBar(ratio) {
    return ratio === null || ratio === undefined ? 0 : Math.min(1, Math.log2(ratio) / 2);
  }
  const api = { summarize, scope, evidenceLinks, baselineExp, tradeoffs, bars, duel, duelKind, duelBar };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.Decision = api;
})(typeof window !== 'undefined' ? window : globalThis);
