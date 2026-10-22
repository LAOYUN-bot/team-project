/* =============================================================================
 * A02 RAG 效果验证与评估（零依赖）
 *
 * 作用：
 *   ① 跑评测集，输出 Recall@K / 关键词命中率 / 拒答率（v2.0 §3.3.2 要求的效果评估）
 *   ② 抽样验证「带来源回答」与「上下文不足时拒答」两个关键行为
 *
 * 用法：node tools/rag-eval.mjs [baseUrl]
 * 退出码：实测 Recall@5 ≥ 80% 且拒答行为正常 → 0，否则 1
 * ========================================================================== */
const BASE = (process.argv[2] || 'http://127.0.0.1:8080').replace(/\/$/, '');

let cookie = '';
async function call(path, method = 'GET', body) {
  const headers = { 'Content-Type': 'application/json' };
  if (cookie) headers.Cookie = cookie;
  const res = await fetch(BASE + path, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
  });
  const sc = res.headers.getSetCookie ? res.headers.getSetCookie() : [];
  if (sc.length) cookie = sc.map((c) => c.split(';')[0]).join('; ');
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* 非 JSON */ }
  return { status: res.status, json, text };
}

const login = await call('/api/auth/login', 'POST', { username: 'demo', password: 'demo1234' });
if (login.status !== 200 || login.json?.code !== 0) {
  console.error('登录失败：', login.status, login.text.slice(0, 200));
  process.exit(2);
}

console.log(`RAG 验证 ← ${BASE}\n`);

/* ---------------- ① 知识库运行态 ---------------- */
const stats = (await call('/api/kb/stats')).json.data;
console.log('【知识库】');
console.log(`  文档 ${stats.documents} 份 · 片段 ${stats.chunks} 个 · topK=${stats.topK} · 拒答阈值=${stats.minScore}`);
console.log(`  向量方案：${stats.vectorStore}`);
console.log(`  向量化  ：${stats.embedding}`);
console.log(`  检索次数 ${stats.retrievals} · 拒答 ${stats.refusals}`);
console.log('  文档明细：');
(stats.docs || []).forEach((d) => {
  console.log(`    - [${d.category}] ${d.title}（${d.chunkCount} 块）`);
});

/* ---------------- ② 带来源问答 ---------------- */
console.log('\n【带来源问答】');
const answerable = 'Java 后端岗位需要掌握哪些数据库技能？';
const ask1 = (await call('/api/kb/ask', 'POST', { question: answerable })).json.data;
console.log(`  Q: ${answerable}`);
console.log(`  A: ${ask1.answer}`);
console.log(`  拒答=${ask1.refused} · 生成方式=${ask1.answerer} · 相关度=${ask1.topScore} · 覆盖率=${ask1.coverage} · 引用 ${ask1.citations.length} 条`);
ask1.citations.slice(0, 3).forEach((c, i) => {
  console.log(`    [${i + 1}] ${c.title}（${c.score}）：${c.excerpt.slice(0, 60)}…`);
});

/* ---------------- ③ 拒答行为（防幻觉） ---------------- */
console.log('\n【拒答行为】');
const unanswerable = '请告诉我 2027 年浙江省高考录取分数线是多少？';
const ask2 = (await call('/api/kb/ask', 'POST', { question: unanswerable })).json.data;
console.log(`  Q: ${unanswerable}`);
console.log(`  A: ${ask2.answer}`);
console.log(`  拒答=${ask2.refused} · 相关度=${ask2.topScore} · 覆盖率=${ask2.coverage} ← 应因覆盖率不足而拒答`);

/* ---------------- ④ 评测集 Recall@K ---------------- */
console.log('\n【Recall@K 评估】');
const report = (await call('/api/kb/eval', 'POST', { k: 5 })).json.data;
console.log(`  评测用例 ${report.total} 条 · K=${report.k}`);
console.log(`  Recall@${report.k}      = ${report.recallAtK}%   （v2.0 目标 ≥80%）`);
console.log(`  Recall@1      = ${report.recallAt1}%   ·  MRR = ${report.mrr}   （更严格的指标）`);
console.log(`  关键词命中率  = ${report.keywordHitRate}%`);
console.log(`  拒答率        = ${report.refusalRate}%`);
console.log(`  平均最高相关度 = ${report.avgTopScore}`);
console.log(`  未完全命中 ${report.failures} 条`);
console.log(`  结论：${report.conclusion}`);

if (report.failures > 0 && report.failureDetails?.length) {
  console.log('\n  未命中明细（前 8 条）：');
  report.failureDetails.slice(0, 8).forEach((f) => {
    console.log(`    - ${f.question}`);
    console.log(`      期望文档=${f.expectedDoc} 文档命中=${f.docHit} 关键词命中=${f.keywordHit} 实际召回=[${f.retrievedDocs.join(', ')}]`);
  });
}

/* ---------------- ⑤ 成本与限流状态（NFR-8） ---------------- */
console.log('\n【模型调用与成本（NFR-8）】');
const llm = (await call('/api/platform/llm-stats?days=7')).json.data;
const ov = llm.overview || {};
console.log(`  调用 ${ov.calls} 次 · 失败率 ${ov.failureRate}% · 缓存命中率 ${ov.cacheHitRate}%`);
console.log(`  token 合计 ${ov.tokens} · 估算成本 ${ov.cost} 元 · 平均耗时 ${ov.avgLatency ?? '—'} ms`);
console.log(`  今日调用 ${llm.limitState?.todayCalls}/${llm.limitState?.dailyLimit} · 全局令牌桶 ${llm.limitState?.globalTokensLeft}/${llm.limitState?.globalRpm} 剩余`);
console.log(`  网关：provider=${llm.gateway?.provider} · 已配置真实模型=${llm.gateway?.remoteConfigured}`);

/* ---------------- 结论 ---------------- */
const pass = report.recallAtK >= 80 && ask1.refused === false && ask2.refused === true;
console.log('\n' + '='.repeat(70));
console.log(pass
  ? `✅ RAG 验证通过：Recall@5=${report.recallAtK}%，带来源回答正常，上下文不足时正确拒答。`
  : `⚠ RAG 验证未达标：Recall@5=${report.recallAtK}%（目标≥80%），`
    + `可回答题拒答=${ask1.refused}（应为 false），不可回答题拒答=${ask2.refused}（应为 true）。`);
process.exit(pass ? 0 : 1);
