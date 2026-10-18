/* Purpose, tradeoffs, pairwise comparison and evidence. No changes to registered scoring. */
const DecisionUI = (() => {
  const copy = {
    en: {
      expNotice:'A ranking that includes languages run at a different time is exploratory. See the experiment README for the Claude Code and model versions.',
      repeat:'Show ranges and individual observations', runCost:'Run cost (API-price equivalent)', followupSource:'Follow-up source', purpose:'What matters for your backend?', overview:'Key observations for your purpose', pair:'Compare two languages',
      first:'First language', second:'Second language', scope:'Exploratory results', task:'task', experiments:'experiments', passed:'passed',
      limits:'One RealWorld backend task; 3 repetitions per language and model. These observations do not establish a general success rate or a universal winner.',
      overviewNote:'Rows are ordered by the score for the selected purpose. Each bar shows the value relative to the best language in that column (longer is better). Values are medians of passing runs; select a value to inspect its runs.',
      reasons:'Relative strengths and burdens', reasonNote:'A strength is a weighted metric where the language is best among these {n} languages; a burden is one where it is worst. Values shared by most languages and middle positions are not listed. These are observed tradeoffs, not causal claims.',
      strength:'Strength', burden:'Burden', noStrength:'Not best on any metric', noBurden:'Not worst on any metric',
      evidence:'Run evidence', run:'Run', measured:'measured', range:'min–max', observations:'observations',
      costNote:'All observed costs, including failures, divided by passing runs. API-price equivalent, not billed spend.',
      proxy:'LOC, dependency count and vulnerability count are proxies, not direct measures of maintainability or security. Vulnerabilities include OS packages; build speed and follow-up outcomes are shown separately.',
      scoreNote:'Scores depend on weights and the languages compared. The pre-registered rule is preserved; hidden constant metrics change displayed scores, not the current ranking. Report scores are the reference for EXP-001.',
      issue:'Known deviation: one Sonnet C++ follow-up run failed after copied CMake build files caused a path mismatch. The recorded failure remains included; it cannot be attributed solely to language maintainability.',
      report:'Experiment report', source:'App source', raw:'Raw records', csv:'Measurement CSV', deviations:'Deviations',
      evidenceNote:'Recorded pass/fail and missing values are preserved. Run values below are observations, not aggregate scores.',
      noRuns:'No runs for this selection.', pass:'Pass', fail:'Fail', status:'Main task', followup:'Follow-up task',
      empty:'No measurements yet.', comparisonNote:'Same metrics, separate models. Read the observed ranges before choosing; overlap is not proof of equivalence.',
      notMeasured:'Not measured', followupCost:'Follow-up time is conditional on recorded successful runs.',
    },
    ko: {
      expNotice:'다른 시점에 실행한 언어가 포함된 순위는 탐색적이다. Claude Code·모델 버전은 실험 README를 참조한다.',
      repeat:'범위·반복값 표시', runCost:'run 비용(API 정가 환산)', followupSource:'후속 과제 소스', purpose:'백엔드에서 무엇이 중요한가요?', overview:'목적에 따른 핵심 관측', pair:'두 언어 비교',
      first:'첫 번째 언어', second:'두 번째 언어', scope:'탐색 결과', task:'개 과제', experiments:'개 실험', passed:'합격',
      limits:'RealWorld 백엔드 과제 1개, 언어·모델별 3회 반복이다. 일반적인 성공률이나 보편적인 최상위 언어를 확정하는 근거는 아니다.',
      overviewNote:'행은 선택한 목적의 종합 점수 순서다. 막대는 그 열의 1위 언어 대비 값이며 길수록 좋다. 수치는 합격 run의 중앙값이고, 선택하면 해당 run을 확인할 수 있다.',
      reasons:'상대적인 강점과 부담', reasonNote:'강점은 현재 가중치가 있는 지표 중 {n}개 언어에서 최고인 지표, 부담은 최저인 지표다. 여러 언어가 같은 값이거나 중간 순위인 지표는 표시하지 않는다. 원인이나 보편적인 우열을 뜻하지 않는다.',
      strength:'강점', burden:'부담', noStrength:'최고인 지표 없음', noBurden:'최저인 지표 없음',
      evidence:'run 증거', run:'run', measured:'측정', range:'최소–최대', observations:'관측값',
      costNote:'실패를 포함한 전체 관측 비용 ÷ 합격 수. API 정가 환산값이며 실제 청구액은 아니다.',
      proxy:'코드량·의존성 수·취약점 수는 대리지표이며 유지보수성·보안의 직접 평가가 아니다. 취약점에는 OS 패키지가 포함된다. 기능 추가 성공·시간과 구분해 판단한다.',
      scoreNote:'종합 점수는 가중치와 비교 대상 언어에 따라 달라진다. 사전 등록 규칙은 보존하며, 상수 지표를 숨겨 표시 점수는 보고서와 다를 수 있다. EXP-001 판정은 보고서가 기준이다.',
      issue:'알려진 이탈: Sonnet C++ 후속 run 1건은 복사된 CMake 빌드 파일의 경로 불일치로 실패했다. 기록된 실패는 그대로 포함하며, 이를 언어의 유지보수성만으로 해석할 수 없다.',
      report:'실험 보고서', source:'앱 소스', raw:'원자료', csv:'측정 CSV', deviations:'설계 이탈',
      evidenceNote:'기록된 합격·실패·미측정을 유지한다. 아래 값은 개별 run 관측이며 종합 점수가 아니다.',
      noRuns:'선택한 조건의 run이 없다.', pass:'합격', fail:'실패', status:'본 과제', followup:'후속 과제',
      empty:'아직 측정값이 없다.', comparisonNote:'같은 지표를 모델별로 분리해 비교한다. 관측 범위를 함께 확인한다. 범위가 겹친다고 동등성이 입증되는 것은 아니다.',
      notMeasured:'미측정', followupCost:'기능 추가 시간은 성공 기록이 있는 run의 조건부 값이다.',
    },
    ja: {
      expNotice:'別の時点で実行した言語を含む順位は探索的なものである。Claude Code とモデルのバージョンは実験 README を参照すること。',
      repeat:'範囲と個々の観測値を表示', runCost:'run コスト(API価格換算)', followupSource:'後続課題のソース', purpose:'バックエンドで何を重視しますか?', overview:'目的別の主要な観測', pair:'2言語を比較', first:'第1言語', second:'第2言語',
      scope:'探索的結果', task:'課題', experiments:'実験', passed:'合格',
      limits:'RealWorld バックエンド1課題、言語・モデルごとに3回。一般的な成功率や普遍的な最優秀言語を確定するものではない。',
      overviewNote:'行は選択した目的の総合スコア順。バーはその列の1位言語に対する値で、長いほど良い。数値は合格 run の中央値で、選ぶと run を確認できる。',
      reasons:'相対的な強みと負担', reasonNote:'強みは現在の重み付き指標のうち{n}言語で最良の指標、負担は最下位の指標。多くの言語が同じ値の指標や中間順位は表示しない。因果関係や普遍的な優劣を示すものではない。',
      strength:'強み', burden:'負担', noStrength:'最良の指標なし', noBurden:'最下位の指標なし', evidence:'run の根拠', run:'run', measured:'測定', range:'最小–最大', observations:'観測値',
      costNote:'失敗を含む全観測コスト ÷ 合格数。API価格換算であり実際の請求額ではない。',
      proxy:'コード量・依存数・脆弱性数は代理指標であり、保守性・安全性の直接評価ではない。脆弱性にはOSパッケージも含まれる。機能追加の結果と分けて判断する。',
      scoreNote:'スコアは重みと比較言語に依存する。事前登録ルールを維持するが、定数指標を除く表示値は報告書と異なる場合がある。EXP-001の判定は報告書が基準。',
      issue:'既知の逸脱: Sonnet C++ の後続 run 1件はコピーされた CMake ビルドファイルのパス不一致で失敗した。失敗記録は維持し、言語の保守性だけに帰属させない。',
      report:'実験報告', source:'アプリソース', raw:'生データ', csv:'測定CSV', deviations:'設計逸脱', evidenceNote:'合否と未測定をそのまま表示。以下は個々の観測値であり総合スコアではない。',
      noRuns:'この条件の run はない。', pass:'合格', fail:'不合格', status:'本課題', followup:'後続課題', empty:'測定値なし。',
      comparisonNote:'同じ指標をモデル別に比較する。観測範囲の重なりは同等性の証明ではない。', notMeasured:'未測定', followupCost:'機能追加時間は成功記録のある run に限る。',
    },
    zh: {
      expNotice:'包含在不同时间运行的语言的排名属于探索性结果。Claude Code 与模型版本请参阅实验 README。',
      repeat:'显示范围和各次观测值', runCost:'run 成本(API价格换算)', followupSource:'后续任务源码', purpose:'你的后端最重视什么？', overview:'按目的查看主要观测', pair:'比较两种语言', first:'第一种语言', second:'第二种语言',
      scope:'探索性结果', task:'个任务', experiments:'个实验', passed:'通过',
      limits:'一个 RealWorld 后端任务，每种语言、每个模型重复3次。这不能确定一般成功率或普遍最佳语言。',
      overviewNote:'行按所选目的的综合得分排序。条形表示相对于该列第一名语言的值，越长越好。数值为通过 run 的中位数，选择数值可查看 run。',
      reasons:'相对优势与代价', reasonNote:'优势是当前有权重的指标中在{n}种语言里最好的指标，代价是最差的指标。多数语言数值相同或处于中间名次的指标不显示。不代表因果关系或普遍优劣。',
      strength:'优势', burden:'代价', noStrength:'没有最好的指标', noBurden:'没有最差的指标', evidence:'run 证据', run:'run', measured:'已测量', range:'最小–最大', observations:'观测值',
      costNote:'包含失败的全部观测成本 ÷ 通过数。按API价格换算，并非实际账单。',
      proxy:'代码量、依赖数和漏洞数是代理指标，不是可维护性或安全性的直接评价。漏洞包括操作系统包；应与功能追加结果分开判断。',
      scoreNote:'分数取决于权重和比较语言。保留预注册规则；隐藏常量指标后，显示分数可能与报告不同。EXP-001的判定以报告为准。',
      issue:'已知偏差：Sonnet C++ 的一次后续 run 因复制的 CMake 构建文件路径不匹配而失败。保留失败记录，不能仅归因于语言的可维护性。',
      report:'实验报告', source:'应用源码', raw:'原始记录', csv:'测量CSV', deviations:'设计偏差', evidenceNote:'保留通过、失败和未测量记录。以下是单次观测，不是综合分数。',
      noRuns:'所选条件没有 run。', pass:'通过', fail:'失败', status:'主任务', followup:'后续任务', empty:'暂无测量值。',
      comparisonNote:'按模型分别比较相同指标。观测范围重叠不代表已证明等效。', notMeasured:'未测量', followupCost:'功能追加时间仅包含有成功记录的 run。',
    },
  };
  const keys = {
    prereg:['wall_min','cost_per_pass','rps','p95_ms','peak_rss_mb','followup_pass'],
    balanced:['wall_min','cost_per_pass','rps','p95_ms','peak_rss_mb','followup_pass'],
    speed:['wall_min','cost_per_pass','followup_min','token_proxy','followup_token_proxy'],
    perf:['rps','p95_ms','peak_rss_mb','cold_start_ms','image_mb','soak_mem_growth_mb'],
    maintain:['followup_pass','followup_min','vulns','deps','loc','build_sec'],
  };
  const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  let env, selectedRun, showRepeats=false, selectedLanguage = 'go', pair = ['go','rust'], overviewModel;
  const models = () => env.state.model === 'compare' ? env.B.models : env.B.models.filter(m => m.key === env.state.model);
  const activeKeys = () => env.state.preset === 'custom' ? Object.entries(env.weights()).filter(([,w]) => w>0).sort((a,b) => b[1]-a[1]).slice(0,6).map(([key]) => key) : keys[env.state.preset] || keys.balanced;
  const metric = key => env.B.metrics.find(m => m.key === key);
  const name = key => env.B.languages.find(l => l.key === key)?.name || key;
  // 기준선 실험과 다른 시점에 실행한 언어에는 "<exp> · <date>" 배지를 붙인다.
  const baseline = () => Decision.baselineExp(env.B.languages, env.B.experiments);
  const badge = key => {
    const l = env.B.languages.find(x => x.key === key), base = baseline();
    return l && base && l.exp !== base ? ` <span class="exp-badge">${esc(l.exp)} · ${esc(env.B.experiments[l.exp]?.date ?? '')}</span>` : '';
  };
  const text = () => (copy[env.state.ui] || copy.en);
  const link = (href,label) => `<a href="${esc(href)}" target="_blank" rel="noopener">${esc(label)}</a>`;
  const issue = () => env.B.runs.some(r => r.exp === 'EXP-001' && r.run === 'sonnet55-cpp-3');
  function statCell(language, model, key, bar) {
    const m = metric(key), s = Decision.summarize(env.B.runs, language, model, key), t = text();
    const aggregate = ['pass_rate','cost_per_pass','followup_pass'].includes(key);
    return `<td class="metric-cell"><button class="metric-value" aria-label="${esc(name(language))} · ${esc(model)} · ${esc(env.label(m))}: ${esc(env.fmt(m,s.median))}" data-evidence-language="${language}" data-evidence-model="${model}" data-metric="${key}">${esc(env.fmt(m,s.median))}${key === 'followup_pass' && language === 'cpp' && model === 'sonnet55' && issue() ? ' †' : ''}</button>${bar === null || bar === undefined ? '' : `<span class="value-bar" aria-hidden="true"><i style="width:${Math.max(2, Math.round(bar*100))}%"></i></span>`}` +
      (aggregate ? `<span class="observation">${key === 'cost_per_pass' ? `${s.passed}/${s.total} ${t.passed}` : `${key === 'pass_rate' ? s.passed : s.values.filter(v => v === 1).length}/${key === 'pass_rate' ? s.total : s.measured} ${t.passed}`}</span>` :
        `<span class="observation">${esc(t.range)}: ${esc(env.fmt(m,s.min))}–${esc(env.fmt(m,s.max))}<br>${s.measured}/${s.total} ${esc(t.measured)}</span><span class="observations" aria-hidden="true">${s.values.map(() => `<i class="observation-dot ${model}"></i>`).join('')}</span><span class="observation">${esc(t.observations)}: ${s.values.length ? s.values.map(v => esc(env.fmt(m,v))).join(' · ') : '—'}</span>`) + '</td>';
  }
  function table(languages) {
    const ms = activeKeys().map(metric).filter(Boolean);
    return `<div class="scroll"><table class="decision-table show-repeats"><thead><tr><th>${esc(env.t.lang)}</th>${ms.map(m => `<th>${esc(env.label(m))} ${m.better === 'higher' ? '↑' : '↓'}</th>`).join('')}</tr></thead><tbody>` +
      languages.flatMap(l => models().map(md => `<tr><th scope="row">${esc(l.name)}${badge(l.key)}<span class="model-label">${esc(md.name)}</span></th>${ms.map(m => statCell(l.key,md.key,m.key)).join('')}</tr>`)).join('') + '</tbody></table></div>';
  }
  // 목적별 관측 표: 종합 점수 순으로 정렬하고, 칸마다 1위 대비 막대를 붙인다. 한 번에 한 모델만 보여 준다.
  function overviewTable(model) {
    const ms=activeKeys().map(metric).filter(Boolean);
    const cells=Object.fromEntries(env.B.languages.map(l => [l.key,env.B.cells[l.key][model]]));
    const score=Score.composite(cells,env.B.metrics,env.weights()), rank=Score.ranks(score);
    const order=[...env.B.languages].sort((a,b) => (rank[a.key] ?? Infinity)-(rank[b.key] ?? Infinity));
    const widths=Object.fromEntries(ms.map(m => [m.key, Decision.bars(Object.fromEntries(order.map(l =>
      [l.key, Decision.summarize(env.B.runs,l.key,model,m.key).median])), m.better)]));
    return `<div class="scroll"><table class="decision-table compact-table overview-table${showRepeats ? ' show-repeats' : ''}"><thead><tr><th>${esc(env.t.lang)}</th><th>${esc(env.t.score)}</th>${ms.map(m => `<th>${esc(env.label(m))} ${m.better === 'higher' ? '↑' : '↓'}</th>`).join('')}</tr></thead><tbody>` +
      order.map(l => `<tr><th scope="row"><span class="rank">${rank[l.key] ?? '—'}</span>${esc(l.name)}${badge(l.key)}</th><td class="score-cell">${score[l.key] === null ? '—' : `<strong>${Math.round(score[l.key]*100)}</strong><span class="value-bar score-bar" aria-hidden="true"><i style="width:${Math.round(score[l.key]*100)}%"></i></span>`}</td>${ms.map(m => statCell(l.key,model,m.key,widths[m.key][l.key])).join('')}</tr>`).join('') + '</tbody></table></div>';
  }
  function bindValues(panel) {
    panel.querySelectorAll('[data-evidence-language]').forEach(button => button.onclick = () => {
      open({ language:button.dataset.evidenceLanguage, model:button.dataset.evidenceModel, metric:button.dataset.metric });
    });
  }
  function renderScope() {
    const t=text(), s=Decision.scope(env.B.runs), links=env.B.runs.length ? Decision.evidenceLinks(env.B.runs[0], env.B.experiments) : null;
    document.getElementById('scopePanel').innerHTML = `<p class="scope-facts">${esc(t.scope)} · ${s.experiments} ${esc(t.experiments)} · ${s.passed}/${s.total} ${esc(t.passed)}</p><p class="note">${esc(t.limits)}</p>` +
      (links ? `<p class="note">${link(links.report,t.report)} · ${link(links.deviations,t.deviations)} · ${link(links.csv,t.csv)}</p>` : `<p>${esc(t.empty)}</p>`);
    document.querySelector('[data-t="weightsTitle"]').textContent=t.purpose;
    document.getElementById('proxyNote').textContent=t.proxy;
    document.getElementById('scoreCaution').textContent=t.scoreNote + (issue() && models().some(m => m.key === 'sonnet55') ? ' † '+t.issue : '');
  }
  function renderOverview() {
    const t=text(), panel=document.getElementById('overviewPanel');
    if (!models().some(m => m.key === overviewModel)) overviewModel=models()[0]?.key;
    const model=overviewModel;
    panel.innerHTML = `<h2>${esc(t.overview)}</h2><p class="note">${esc(t.overviewNote)}</p>${models().length > 1 ? '<div class="seg overview-model" id="overviewModelSeg"></div>' : ''}<label class="repeat-toggle"><input type="checkbox" id="showRepeats" ${showRepeats ? 'checked' : ''}> ${esc(t.repeat)}</label>${overviewTable(model)}<p class="note">${esc(t.costNote)} ${esc(t.followupCost)}</p>` +
      (env.B.languages.some(l => badge(l.key)) ? `<p class="note exp-notice">${esc(t.expNotice)}</p>` : '') +
      (issue() && activeKeys().some(k => k.startsWith('followup')) ? `<p class="issue-note">† ${esc(t.issue)}</p>` : '') +
      `<h2 style="margin-top:24px">${esc(t.reasons)}</h2><p class="note">${esc(t.reasonNote.replaceAll('{n}', env.B.languages.length))}</p><div class="reason-grid">` + env.B.languages.map(l =>
        `<article class="reason"><h3>${esc(l.name)}${badge(l.key)}</h3><div>${models().map(md => {
          const cells=Object.fromEntries(env.B.languages.map(lang => [lang.key,env.B.cells[lang.key][md.key]]));
          const tr=Decision.tradeoffs(cells,l.key,activeKeys().map(metric).filter(Boolean),env.weights());
          const statement=(key,prefix,none) => key ? `${esc(prefix)}: ${esc(env.label(metric(key)))} ${esc(env.fmt(metric(key),cells[l.key][key]))}` : `${esc(prefix)}: ${esc(none)}`;
          return `<p><strong>${esc(md.name)}</strong><br>${statement(tr.strength,t.strength,t.noStrength)}<br>${statement(tr.burden,t.burden,t.noBurden)}</p>`;
        }).join('')}</div></article>`).join('')+'</div>';
    bindValues(panel);
    const segEl=document.getElementById('overviewModelSeg');
    if (segEl) seg(segEl, models().map(m => [m.key,m.name]), model, k => { overviewModel=k; renderOverview(); });
    document.getElementById('showRepeats').onchange=e => { showRepeats=e.target.checked; renderOverview(); document.getElementById('showRepeats').focus({preventScroll:true}); };
  }
  function renderPair() {
    const t=text(), panel=document.getElementById('pairPanel');
    const options=selected => env.B.languages.map(l => `<option value="${l.key}"${l.key === selected ? ' selected' : ''}>${esc(l.name)}</option>`).join('');
    panel.innerHTML=`<h2>${esc(t.pair)}</h2><div class="pair-controls"><label>${esc(t.first)}<select id="pairA">${options(pair[0])}</select></label><label>${esc(t.second)}<select id="pairB">${options(pair[1])}</select></label></div><p class="note">${esc(t.comparisonNote)}</p>${table(pair.map(key => ({key,name:name(key)})))}`;
    ['pairA','pairB'].forEach((id,i) => document.getElementById(id).onchange = e => {
      const other=pair[i]; pair[i]=e.target.value;
      if (pair[0] === pair[1]) pair[1-i]=other;
      renderPair();
      document.getElementById(id).focus({preventScroll:true});
    });
    bindValues(panel);
  }
  function renderEvidence() {
    const t=text(), panel=document.getElementById('evidencePanel');
    const rows=env.B.runs.filter(r => models().some(m => m.key === r.model));
    if (!rows.some(r => r.run === selectedRun)) selectedRun=(rows.find(r => r.language === selectedLanguage) || rows[0])?.run;
    const run=rows.find(r => r.run === selectedRun);
    if (!run) { panel.innerHTML=`<h2>${esc(t.evidence)}</h2><p>${esc(t.noRuns)}</p>`; return; }
    const links=Decision.evidenceLinks(run, env.B.experiments);
    const result = value => value === '' || value === null || value === undefined ? t.notMeasured : +value === 1 ? t.pass : t.fail;
    panel.innerHTML=`<h2>${esc(t.evidence)}${badge(run.language)}</h2><label>${esc(t.run)} <select id="evidenceRun">${rows.map(r => `<option value="${esc(r.run)}"${r.run === selectedRun ? ' selected' : ''}>${esc(r.run)} · ${esc(result(r.pass))}</option>`).join('')}</select></label>` +
      `<p class="note">${esc(t.evidenceNote)}</p><p>${esc(t.status)}: <strong>${esc(result(run.pass))}</strong> · ${esc(t.followup)}: <strong>${esc(result(run.followup_pass))}</strong></p>` +
      (run.run === 'sonnet55-cpp-3' && run.exp === 'EXP-001' ? `<p class="issue-note">† ${esc(t.issue)}</p>` : '') +
      (links ? `<nav class="evidence-links" aria-label="${esc(t.evidence)}">${['source','followup','raw','csv','report','deviations'].filter(k => k !== 'followup' || run.followup_pass !== '').map(k => link(links[k],k === 'followup' ? t.followupSource : t[k])).join('')}</nav>` : '') +
      `<dl class="run-values"><div><dt>${esc(t.runCost)}</dt><dd>${esc(env.fmt({unit:'USD'},run.cost_usd === '' || run.cost_usd == null ? null : +run.cost_usd))}</dd></div>${env.B.metrics.filter(m => !['pass_rate','cost_per_pass'].includes(m.key)).map(m => {
        const raw=run[m.key], value=raw === '' || raw === null || raw === undefined ? null : +raw;
        return `<div><dt>${esc(env.label(m))}</dt><dd>${esc(env.fmt(m,value))}</dd></div>`;
      }).join('')}</dl>`;
    document.getElementById('evidenceRun').onchange=e => { selectedRun=e.target.value; selectedLanguage=rows.find(r => r.run === selectedRun).language; renderEvidence(); document.getElementById('evidenceRun').focus({preventScroll:true}); };
  }
  function open(selection) {
    selectedLanguage=selection.language || selectedLanguage;
    const matching=env.B.runs.filter(r => r.language === selectedLanguage && r.model === (selection.model || models()[0]?.key));
    selectedRun=selection.run || (selection.metric === 'followup_pass' && matching.find(r => String(r.followup_pass) === '0') || matching[0])?.run;
    if (selection.metric) env.selectMetric(selection.metric);
    renderEvidence();
    const panel=document.getElementById('evidencePanel');
    panel.scrollIntoView({block:'start'});
    document.getElementById('evidenceRun')?.focus({preventScroll:true});
  }
  function render(context) { env=context; renderScope(); renderOverview(); renderPair(); renderEvidence(); }
  return { render, open };
})();
