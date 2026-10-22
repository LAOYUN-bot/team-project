/* =============================================================================
 * A02 图表库逻辑自检（零依赖）
 *
 * 背景：本机无法运行浏览器（沙箱禁止 Chrome 的命名管道 IPC），
 *       因此改为在 Node 中以 DOM 桩件执行 charts.js，对生成的 SVG 结构做断言：
 *         · 坐标/长度中不得出现 NaN 或 Infinity
 *         · 元素数量符合预期（网格环、轴线、顶点、端点…）
 *         · 边界数据（空数组、单点、全 0、全 100）不得抛异常
 *
 * 用法：node tools/charts-test.mjs
 * ========================================================================== */
import { readFileSync } from 'node:fs';

/* ---------------- 极简 DOM 桩件 ---------------- */
class El {
  constructor(name, ns) {
    this.nodeName = name;
    this.ns = ns;
    this.attrs = {};
    this.children = [];
    this.style = {};
    this._text = '';
    this._html = '';
    this.className = '';
  }
  setAttribute(k, v) { this.attrs[k] = v; }
  getAttribute(k) { return this.attrs[k]; }
  appendChild(c) { this.children.push(c); return c; }
  removeChild(c) { this.children = this.children.filter((x) => x !== c); }
  get firstChild() { return this.children[0]; }
  set textContent(v) { this._text = String(v); }
  get textContent() { return this._text; }
  set innerHTML(v) { this._html = String(v); }
  get innerHTML() { return this._html; }
  /** 递归收集所有后代 */
  all(out = []) {
    for (const c of this.children) { out.push(c); c.all && c.all(out); }
    return out;
  }
  get tag() { return this.nodeName; }
}

const fakeDocument = {
  createElementNS: (ns, name) => new El(name, ns),
  createElement: (name) => new El(name, 'http://www.w3.org/1999/xhtml'),
};

const source = readFileSync('src/main/resources/static/js/charts.js', 'utf8');
const Charts = new Function('document', `${source}\nreturn Charts;`)(fakeDocument);

/* ---------------- 断言工具 ---------------- */
let passed = 0;
const failures = [];

function assert(condition, label) {
  if (condition) { passed++; return; }
  failures.push(label);
}

function assertFiniteNumbers(text, label) {
  if (text === undefined || text === null) { assert(false, `${label}：缺少值`); return; }
  const s = String(text);
  assert(!/NaN|Infinity|undefined|null/.test(s), `${label}：含非法数值 → ${s.slice(0, 120)}`);
}

/** 校验一个元素上的所有数值属性都合法 */
function checkAttributes(el, label) {
  Object.entries(el.attrs).forEach(([k, v]) => {
    if (typeof v === 'number' || /^[\d\s.,+-]+$/.test(String(v))) assertFiniteNumbers(v, `${label}.${k}`);
  });
}

const host = () => new El('div', 'http://www.w3.org/1999/xhtml');

/* ---------------- ① 雷达图 ---------------- */
const dims = [
  { key: 'PRO_SKILL', label: '专业技能', score: 67.6 },
  { key: 'GEN_ABILITY', label: '通用能力', score: 82.8 },
  { key: 'INDUSTRY_KNOW', label: '行业认知', score: 51.9 },
  { key: 'PROJECT_EXP', label: '项目经验', score: 63.3 },
  { key: 'LEARNING_ABILITY', label: '学习力', score: 61.2 },
  { key: 'COMMUNICATION', label: '沟通表达', score: 64.1 },
  { key: 'TOOL_USAGE', label: '工具使用', score: 70.0 },
  { key: 'COLLABORATION', label: '协作意识', score: 64.8 },
];
{
  const h = host();
  Charts.radar(h, dims, { size: 380, previous: dims.map((d) => ({ score: d.score - 5 })) });
  const svg = h.children[0];
  assert(svg && svg.tag === 'svg', '雷达图：未生成 svg 根节点');
  const nodes = svg.all();
  const polys = nodes.filter((n) => n.tag === 'polygon');
  const lines = nodes.filter((n) => n.tag === 'line');
  const circles = nodes.filter((n) => n.tag === 'circle');
  const texts = nodes.filter((n) => n.tag === 'text');
  const tspans = nodes.filter((n) => n.tag === 'tspan');
  assert(polys.length === 6, `雷达图：多边形数量应为 6（4 网格环 + 上一版本 + 当前），实际 ${polys.length}`);
  assert(lines.length === 8, `雷达图：轴线数量应为 8，实际 ${lines.length}`);
  assert(circles.length === 8, `雷达图：顶点数量应为 8，实际 ${circles.length}`);
  // 维度标签是 8 个 <text>，分值以嵌套 <tspan> 渲染在同一行（避免标签与分值互相压字）
  assert(texts.length === 8, `雷达图：维度标签文本应为 8，实际 ${texts.length}`);
  assert(tspans.length === 8, `雷达图：分值 tspan 应为 8，实际 ${tspans.length}`);

  polys.forEach((p, i) => {
    assertFiniteNumbers(p.attrs.points, `雷达图多边形#${i}.points`);
    p.attrs.points.split(' ').forEach((pair) => {
      const [x, y] = pair.split(',').map(Number);
      assert(Number.isFinite(x) && Number.isFinite(y), `雷达图多边形#${i}：坐标非有限数 ${pair}`);
      assert(x >= 0 && x <= 380 && y >= 0 && y <= 380, `雷达图多边形#${i}：坐标越界 ${pair}`);
    });
  });
  nodes.forEach((n, i) => checkAttributes(n, `雷达图节点#${i}`));
  // 分值应被正确渲染（作为 label 内的 tspan）
  const scoreTexts = tspans.map((t) => t.textContent);
  assert(scoreTexts.includes('82.8'), '雷达图：未渲染出分值 82.8');
  assert(scoreTexts.includes('51.9'), '雷达图：未渲染出分值 51.9');
}

// 边界：空维度
{
  const h = host();
  Charts.radar(h, [], {});
  assert(h.innerHTML.includes('暂无'), '雷达图：空数据应给出「暂无」提示');
}
// 边界：全 0 分
{
  const h = host();
  Charts.radar(h, dims.map((d) => ({ label: d.label, score: 0 })), { size: 300 });
  const svg = h.children[0];
  assert(svg.all().filter((n) => n.tag === 'polygon').length === 5, '雷达图：全 0 分时结构应完整');
}

/* ---------------- ② 进度环 ---------------- */
[0, 33.3, 100, 66.7].forEach((pct) => {
  const h = host();
  Charts.ring(h, pct, { label: '任务完成率' });
  const svg = h.children[0];
  const circles = svg.all().filter((n) => n.tag === 'circle');
  assert(circles.length === 2, `进度环 ${pct}%：应为 2 个圆（底环+进度弧）`);
  const arc = circles[1];
  assertFiniteNumbers(arc.attrs['stroke-dasharray'], `进度环 ${pct}%：dasharray`);
  const texts = svg.all().filter((n) => n.tag === 'text').map((t) => t.textContent);
  assert(texts.some((t) => String(t).includes(String(pct))), `进度环 ${pct}%：未渲染百分比文本`);
});

/* ---------------- ③ 折线图 ---------------- */
const points = [
  { label: 'W9/1', value: 60.0 }, { label: 'W9/8', value: 57.5 },
  { label: 'W9/15', value: 62.1 }, { label: 'W9/22', value: 65.7 },
];
{
  const h = host();
  Charts.line(h, points, {});
  const svg = h.children[0];
  const paths = svg.all().filter((n) => n.tag === 'path');
  assert(paths.length === 2, '折线图：应为「面积 + 折线」两条 path');
  paths.forEach((p, i) => assertFiniteNumbers(p.attrs.d, `折线图 path#${i}.d`));
  const circles = svg.all().filter((n) => n.tag === 'circle');
  assert(circles.length === points.length, '折线图：数据点数量应与输入一致');
}
// 边界：单点 / 空
{
  const h = host();
  Charts.line(h, [{ label: 'W1', value: 50 }], {});
  assertFiniteNumbers(h.children[0].all().find((n) => n.tag === 'path').attrs.d, '折线图单点：路径');
  const h2 = host();
  Charts.line(h2, [], {});
  assert(h2.innerHTML.includes('暂无'), '折线图：空数据应给出提示');
}

/* ---------------- ④ 热力图 ---------------- */
const cells = [];
{
  const start = new Date('2026-06-29T00:00:00'); // 周一
  for (let i = 0; i < 84; i++) {
    const d = new Date(start.getTime() + i * 86400000);
    const value = i % 5 === 0 ? 0 : (i % 4) * 30;
    cells.push({
      date: d.toISOString().slice(0, 10), value,
      level: value === 0 ? 0 : value < 30 ? 1 : value < 60 ? 2 : value < 120 ? 3 : 4,
    });
  }
  const h = host();
  Charts.heatmap(h, cells, {});
  const svg = h.children[0];
  const rects = svg.all().filter((n) => n.tag === 'rect');
  assert(rects.length === cells.length, `热力图：格子数应为 ${cells.length}，实际 ${rects.length}`);
  rects.forEach((r, i) => {
    checkAttributes(r, `热力图格子#${i}`);
    assert(Number(r.attrs.x) >= 0 && Number(r.attrs.y) >= 0, `热力图格子#${i}：坐标为负`);
    assert(r.attrs.fill && r.attrs.fill !== 'undefined', `热力图格子#${i}：缺少填充色`);
  });
  // 图例（DOM 元素）
  assert(h.children.some((c) => c.className === 'legend'), '热力图：缺少图例');
}
{
  const h = host();
  Charts.heatmap(h, [], {});
  assert(h.innerHTML.includes('暂无'), '热力图：空数据应给出提示');
}

/* ---------------- ⑤ 条形图 ---------------- */
{
  const h = host();
  Charts.bars(h, [
    { label: '专业技能', value: 12.4, display: '+12.4' },
    { label: '行业认知', value: 3.1, display: '-3.1', color: '#ff6b81' },
  ]);
  const wrap = h.children[0];
  assert(wrap.className === 'bars', '条形图：缺少容器');
  assert(wrap.children.length === 2, '条形图：行数应为 2');
  wrap.children.forEach((row, i) => {
    const html = row.innerHTML;
    assert(!/NaN|undefined/.test(html), `条形图第 ${i} 行：HTML 含非法值`);
    assert(/width:\s*[\d.]+%/.test(html), `条形图第 ${i} 行：缺少宽度`);
  });
}

/* ---------------- ⑥ 文本转义（防 XSS） ---------------- */
{
  const escaped = Charts.esc('<img src=x onerror=alert(1)>');
  assert(!escaped.includes('<img'), 'esc：未转义尖括号');
  assert(escaped.includes('&lt;img'), 'esc：转义结果不符合预期');
}

/* ---------------- 输出 ---------------- */
console.log(`图表库自检：通过 ${passed} 项断言，失败 ${failures.length} 项。`);
if (failures.length) {
  console.log('\n失败明细：');
  failures.forEach((f) => console.log('  ✗ ' + f));
  process.exit(1);
}
console.log('✅ 雷达图 / 进度环 / 成长曲线 / 热力图 / 条形图 结构、坐标与边界数据均正常。');
