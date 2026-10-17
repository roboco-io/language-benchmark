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

  const api = { normalize, composite, sampleLabel, defaultWeights };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.Score = api;
})(typeof window !== "undefined" ? window : globalThis);
