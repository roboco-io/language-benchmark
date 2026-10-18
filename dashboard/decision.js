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
  function evidenceLinks(run) {
    const folders = { 'EXP-001':'001-five-language-realworld' };
    const folder = folders[run.exp];
    if (!folder) return null;
    const base = 'https://github.com/roboco-io/language-benchmark';
    const path = `experiments/${folder}`;
    return { source:`${base}/tree/main/${path}/runs/apps/${encodeURIComponent(run.run)}`,
      report:`${base}/blob/main/${path}/report.md`, deviations:`${base}/blob/main/${path}/runs/deviations.md`,
      raw:`${base}/tree/main/${path}/runs/fargate/raw/${encodeURIComponent(run.run)}`,
      followup:`${base}/tree/main/${path}/runs/apps/${encodeURIComponent(run.run)}-fu`,
      csv:`${base}/blob/main/analysis/metrics/runs.csv` };
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
  const api = { summarize, scope, evidenceLinks, tradeoffs };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.Decision = api;
})(typeof window !== 'undefined' ? window : globalThis);
