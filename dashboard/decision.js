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
  function tradeoffs(cells, language, metrics, weights) {
    const candidates = [];
    for (const m of metrics) {
      if (!(weights[m.key] > 0)) continue;
      const vals = Object.values(cells).map(c => number(c[m.key])).filter(v => v !== null);
      const value = number(cells[language]?.[m.key]);
      if (value === null || vals.length < 2 || Math.max(...vals) === Math.min(...vals)) continue;
      const normalized = (value - Math.min(...vals)) / (Math.max(...vals)-Math.min(...vals));
      candidates.push({key:m.key, score:m.better === 'higher' ? normalized : 1-normalized, weight:weights[m.key]});
    }
    candidates.sort((a,b) => b.score-a.score || b.weight-a.weight);
    return { strength:candidates[0]?.score > 0.5 ? candidates[0].key : null,
      burden:candidates.at(-1)?.score < 0.5 ? candidates.at(-1).key : null };
  }
  const api = { summarize, scope, evidenceLinks, tradeoffs };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.Decision = api;
})(typeof window !== 'undefined' ? window : globalThis);
