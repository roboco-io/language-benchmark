// 대시보드 정규화·종합 점수. 브라우저에서는 window.Score, node에서는 module.exports.
(function (root) {
  // 0-1 정규화(1이 좋음). 값이 1개 이하이거나 전부 같으면 값 있는 칸은 1.
  function normalize(values, better) {
    const present = values.filter((v) => v !== null && v !== undefined);
    if (!present.length) return values.map(() => null);
    const min = Math.min(...present);
    const max = Math.max(...present);
    return values.map((v) => {
      if (v === null || v === undefined) return null;
      if (max === min) return 1;
      const t = (v - min) / (max - min);
      return better === "higher" ? t : 1 - t;
    });
  }

  // 언어별 종합 점수: 값 있는 지표의 정규화 값 가중평균. 가중치 합이 0이면 null.
  function composite(cellsByLang, metrics, weights) {
    const langs = Object.keys(cellsByLang);
    const sum = Object.fromEntries(langs.map((l) => [l, 0]));
    const wsum = Object.fromEntries(langs.map((l) => [l, 0]));
    for (const m of metrics) {
      const w = weights[m.key] || 0;
      if (!w) continue;
      const norm = normalize(langs.map((l) => cellsByLang[l][m.key]), m.better);
      langs.forEach((l, i) => {
        if (norm[i] === null) return;
        sum[l] += w * norm[i];
        wsum[l] += w;
      });
    }
    return Object.fromEntries(langs.map((l) => [l, wsum[l] ? sum[l] / wsum[l] : null]));
  }

  // 과정 지표 외 칸의 표본 수 "측정 run/전체 run". run이 없으면 빈 문자열.
  function sampleLabel(cell, metric) {
    if (metric.group === "process" || !cell.n) return "";
    return `${cell[metric.key + "_n"] || 0}/${cell.n}`;
  }

  // 기본 가중치: 모든 지표 1(균등). 초기화와 리셋 버튼이 같은 함수를 쓴다.
  function defaultWeights(metrics) {
    return Object.fromEntries(metrics.map((m) => [m.key, 1]));
  }

  // 목적별 가중치 프리셋. prereg는 EXP-001 사전 등록 기준(24개 지표 균등)이며 판정 재현용으로 남긴다.
  // balanced는 speed·perf·maintain 세 묶음이 각각 1/3씩 기여하도록 묶음 안 가중치를 합 1로 나눠 더한다.
  const GROUP_PRESETS = {
    speed: { wall_min: 3, cost_per_pass: 2, followup_min: 2, token_proxy: 1, followup_token_proxy: 1 },
    perf: { rps: 3, p95_ms: 2, peak_rss_mb: 2, p99_ms: 1, idle_rss_mb: 1, cold_start_ms: 1, image_mb: 1,
            soak_mem_growth_mb: 1, error_rate: 1, soak_error_rate: 1 },
    maintain: { vulns: 2, followup_pass: 2, deps: 1, loc: 1, followup_min: 1, build_sec: 1 },
  };
  function presetWeights(name, metrics) {
    const zero = Object.fromEntries(metrics.map((m) => [m.key, 0]));
    if (name === "prereg") return defaultWeights(metrics);
    if (name === "balanced") {
      for (const g of Object.values(GROUP_PRESETS)) {
        const total = Object.values(g).reduce((a, b) => a + b, 0);
        for (const [k, w] of Object.entries(g)) if (k in zero) zero[k] += w / total;
      }
      return zero;
    }
    return { ...zero, ...GROUP_PRESETS[name] };
  }

  // 모든 언어가 같은 값(또는 값 하나 이하)인 지표는 순위 정보가 없다. 표시에서 숨길 지표를 고른다.
  // cellSets는 비교할 셀 묶음의 배열이다(모델 비교 시 두 모델). 어느 묶음에서든 값이 갈리거나 묶음끼리 값이 다르면 남긴다.
  function constantMetrics(cellSets, metrics) {
    return metrics.filter((m) => {
      const all = cellSets.flatMap((cells) => Object.values(cells).map((c) => c[m.key]))
        .filter((v) => v !== null && v !== undefined);
      return all.length > 0 && new Set(all).size === 1;
    }).map((m) => m.key);
  }

  // 두 모델의 값 차이: b의 a 대비 변화율과 어느 쪽이 나은지(better 방향 기준). 값이 없거나 같으면 better는 null.
  function modelDiff(a, b, better) {
    if (a === null || a === undefined || b === null || b === undefined) return { pct: null, better: null };
    const pct = a === 0 ? null : (b - a) / Math.abs(a) * 100;
    if (a === b) return { pct, better: null };
    const bWins = better === "higher" ? b > a : b < a;
    return { pct, better: bWins ? "b" : "a" };
  }

  // 순위: 점수가 높은 순으로 1부터. null은 순위 없음.
  function ranks(score) {
    const order = Object.keys(score).filter((k) => score[k] !== null).sort((x, y) => score[y] - score[x]);
    return Object.fromEntries(Object.keys(score).map((k) => [k, score[k] === null ? null : order.indexOf(k) + 1]));
  }

  const api = { normalize, composite, sampleLabel, defaultWeights, presetWeights, constantMetrics, modelDiff, ranks, GROUP_PRESETS };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.Score = api;
})(typeof window !== "undefined" ? window : globalThis);
