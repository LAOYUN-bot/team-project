#!/usr/bin/env node
/**
 * 前端静态自检（无需浏览器）
 * 1. api.js 声明的接口 → 后端是否有对应映射（类级 @RequestMapping 前缀 + 方法路径）
 * 2. app.js / index.html 使用的 CSS 类 → app.css 是否有定义
 * 3. index.html 引用的静态资源 → 文件是否存在
 * 4. app.js 面向用户的文案 → 是否残留开发/实现说明用语
 * 5. 导航路由 → 是否有对应视图处理
 * 6. app.js 调用的 Charts.* 方法 → charts.js 是否存在
 */
import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(import.meta.dirname, '..');
const S = path.join(ROOT, 'src/main/resources/static');
const read = (p) => fs.readFileSync(p, 'utf8');

const app = read(path.join(S, 'js/app.js'));
const apiJs = read(path.join(S, 'js/api.js'));
const charts = read(path.join(S, 'js/charts.js'));
const css = read(path.join(S, 'css/app.css'));
const html = read(path.join(S, 'index.html'));

let fail = 0;
const ok = (m) => console.log(`  ✓ ${m}`);
const bad = (m) => { fail++; console.log(`  ✗ ${m}`); };
const warn = (m) => console.log(`  · ${m}`);

/* ---------- 1. 接口映射（类前缀 + 方法路径） ---------- */
const javaFiles = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (e.name.endsWith('.java')) javaFiles.push(p);
  }
})(path.join(ROOT, 'src/main/java'));

const MAPPING = /@(Get|Post|Put|Delete|Patch|Request)Mapping\s*\(\s*(?:value\s*=\s*|path\s*=\s*)?["']([^"']+)["']/g;
const fullPaths = [];
for (const file of javaFiles) {
  const src = read(file);
  // 类级前缀：第一个 @RequestMapping 且出现在 class 声明之前
  const classIdx = src.search(/\b(public\s+)?(final\s+)?class\s+\w+/);
  let prefix = '';
  if (classIdx > 0) {
    const head = src.slice(0, classIdx);
    const pm = /@RequestMapping\s*\(\s*(?:value\s*=\s*|path\s*=\s*)?["']([^"']+)["']/.exec(head);
    if (pm) prefix = pm[1].replace(/\/+$/, '');
  }
  for (const m of src.matchAll(MAPPING)) {
    const kind = m[1];
    const p = m[2];
    if (kind === 'Request' && p === prefix) continue;      // 纯类级前缀
    fullPaths.push(p.startsWith('/') ? prefix + p : prefix + '/' + p);
  }
}
const norm = (s) => s
  .replace(/\$\{[^}]*\}/g, '*')
  .replace(/\?.*$/, '')
  .replace(/\{[^}]*\}/g, '*')
  .replace(/\/+$/, '');

const mappedSet = new Set(fullPaths.map(norm));
const refs = new Set();
for (const m of apiJs.matchAll(/["'`](\/api\/[A-Za-z0-9_/${}?=&.\-]*)/g)) {
  // 模板里的 encodeURIComponent(...) 之类的表达式会截断，这里补到 ${ 处即可
  refs.add(m[1].replace(/\$\{[^}]*$/, '').replace(/\$\{[^}]*\}/g, '*'));
}
const missing = [...refs].filter((p) => !mappedSet.has(norm(p)));
if (missing.length === 0) ok(`前端声明接口 ${refs.size} 个，后端均有映射（后端方法映射 ${fullPaths.length} 个）`);
else { bad(`有 ${missing.length} 个接口在后端找不到映射`); missing.forEach((p) => console.log(`      ${p}`)); }

/* ---------- 2. CSS 类 ---------- */
const used = new Set();
const addClasses = (s) => {
  if (!s || /\$\{/.test(s)) return;
  s.split(/\s+/).filter((c) => /^-?[A-Za-z_][A-Za-z0-9_-]*$/.test(c)).forEach((c) => used.add(c));
};
for (const m of (app + html).matchAll(/class=["'`]([^"'`]+)["'`]/g)) addClasses(m[1]);
for (const m of (app + charts).matchAll(/classList\.(?:add|remove|toggle)\(\s*["'`]([A-Za-z0-9_-]+)/g)) used.add(m[1]);
for (const m of (app + charts).matchAll(/className\s*=\s*["'`]([^"'`]+)["'`]/g)) addClasses(m[1]);
for (const m of app.matchAll(/['"`]([a-z][a-z0-9-]*(?:\s+[a-z][a-z0-9-]*)+)['"`]/g)) addClasses(m[1]);

const defined = new Set();
for (const m of css.matchAll(/\.(-?[A-Za-z_][A-Za-z0-9_-]*)/g)) defined.add(m[1]);
const cssMissing = [...used].filter((c) => !defined.has(c)).sort();
if (cssMissing.length === 0) ok(`使用 CSS 类 ${used.size} 个，全部有定义`);
else { bad(`有 ${cssMissing.length} 个类在 app.css 中未定义`); console.log('      ' + cssMissing.join('  ')); }

/* ---------- 3. 静态资源 ---------- */
const assets = [...html.matchAll(/(?:href|src)=["']([^"'#]+)["']/g)].map((m) => m[1]).filter((u) => u.startsWith('/'));
const lost = assets.filter((u) => !fs.existsSync(path.join(S, u)));
if (lost.length === 0) ok(`index.html 引用静态资源 ${assets.length} 个，全部存在`);
else bad(`缺失静态资源：${lost.join(', ')}`);

/* ---------- 4. 开发用语残留 ---------- */
const HARD = [
  ['FR-', /FR-\d/], ['NFR', /NFR/], ['AD-', /AD-\d/], ['M1~M7', /\bM[1-7]-/],
  ['答辩', /答辩/], ['演示', /演示/], ['验收', /验收/], ['测试用例', /测试用例/],
  ['单元测试', /单元测试/], ['覆盖率', /覆盖率/],
  ['mock', /mock/i], ['tier', /\btier\b/i], ['令牌桶', /令牌桶/], ['限流', /限流/],
  ['JWT', /\bJWT\b/], ['MCP', /\bMCP\b/], ['traceId', /traceId/], ['SSE', /\bSSE\b/],
  ['API', /\bAPI\b/], ['SDK', /\bSDK\b/], ['接口', /接口/], ['占位', /占位/],
  ['H2', /\bH2\b/], ['SQL', /\bSQL\b/], ['localhost', /localhost/i],
  ['端口', /:80\d\d|端口/], ['仓库', /仓库/], ['部署', /部署/], ['编译', /编译/],
  ['前端', /前端/], ['数据库表', /数据库表/], ['环境变量', /环境变量/],
];
const SOFT = ['后端', '数据库', 'demo', '示例数据', '测试'];

const visible = app
  .split('\n')
  .map((l, i) => ({ l, i: i + 1 }))
  .filter(({ l }) => !/^\s*(\/\/|\*|\/\*)/.test(l))
  .filter(({ l }) => !/(localStorage|sessionStorage|setItem|getItem|dataset\.|'TODO'|'DONE')/.test(l));

const hits = [];
for (const [label, re] of HARD) {
  const lines = visible.filter(({ l }) => re.test(l));
  if (lines.length) {
    hits.push(label);
    lines.forEach(({ l, i }) => console.log(`      ${label} @${i}: ${l.trim().slice(0, 110)}`));
  }
}
if (hits.length === 0) ok('界面文案无开发/实现说明用语');
else bad('发现开发用语：' + hits.join('，'));

const softHits = SOFT.filter((w) => app.includes(w));
if (softHits.length) warn(`出现领域词 ${softHits.join('、')}（自然语言例句可保留）`);

/* ---------- 5. 路由与导航 ---------- */
const views = [...app.matchAll(/^\s*(?:async\s+)?function\s+(view[A-Z]\w*)/gm)].map((m) => m[1]);
const routesBlock = /const ROUTES = \{([\s\S]*?)\};/.exec(app);
const routeKeys = routesBlock
  ? [...routesBlock[1].matchAll(/(\w+)\s*:\s*(view\w+)/g)].map((m) => [m[1], m[2]])
  : [];
const navBlock = /const NAV = \[([\s\S]*?)\];/.exec(app);
const navKeys = navBlock ? [...navBlock[1].matchAll(/key:\s*['"]([a-z-]+)['"]/g)].map((m) => m[1]) : [];
const routeMap = new Map(routeKeys);
const noView = navKeys.filter((k) => !routeMap.has(k));
const fnMissing = routeKeys.filter(([, fn]) => !views.includes(fn));
if (noView.length === 0 && fnMissing.length === 0 && routeKeys.length > 0) {
  ok(`导航 ${navKeys.length} 项 → ROUTES ${routeKeys.length} 条 → 视图函数 ${views.length} 个，全部连通`);
} else {
  if (noView.length) bad(`导航项在 ROUTES 中缺失：${noView.join(', ')}`);
  if (fnMissing.length) bad(`ROUTES 指向不存在的视图函数：${fnMissing.map(([k, f]) => `${k}→${f}`).join(', ')}`);
  if (routeKeys.length === 0) bad('未解析到 ROUTES 路由表');
}
const routedButNoNav = [...routeMap.keys()].filter((k) => !navKeys.includes(k));
if (routedButNoNav.length) warn(`非导航可达路由：${routedButNoNav.join('、')}`);
const orphanViews = views.filter((v) => !routeKeys.some(([, fn]) => fn === v));
if (orphanViews.length) warn(`定义了但未被 ROUTES 使用：${orphanViews.join('、')}`);


/* ---------- 6. Charts API ---------- */
const chartFns = new Set([...charts.matchAll(/^\s{2,}(?:async\s+)?function\s+(radar|bar|bars|line|heatmap|ring|donut|gauge|spark|progress)\w*/gm)].map((m) => m[0].trim()));
const exported = new Set([...chartFns].map((s) => s.replace(/^(?:async\s+)?function\s+/, '').replace(/\W.*$/, '')));
// 也接受对象返回式导出
for (const m of charts.matchAll(/^\s{4,}(radar|bar|bars|line|heatmap|ring|donut|gauge|spark|progress)\s*[,:]/gm)) exported.add(m[1]);
const calls = new Set([...app.matchAll(/Charts\.([A-Za-z_]\w*)\s*\(/g)].map((m) => m[1]));
const notDef = [...calls].filter((c) => !exported.has(c));
if (notDef.length === 0) ok(`Charts.* 调用 ${calls.size} 个（${[...calls].join(', ')}），charts.js 均已实现`);
else bad(`调用了 charts.js 未实现的方法：${notDef.join(', ')}`);

/* ---------- 7. 体积 ---------- */
const size = (p) => (fs.statSync(p).size / 1024).toFixed(1) + ' KB';
console.log(`\n  体积：app.js ${size(path.join(S, 'js/app.js'))} · app.css ${size(path.join(S, 'css/app.css'))} · charts.js ${size(path.join(S, 'js/charts.js'))} · api.js ${size(path.join(S, 'js/api.js'))}`);
console.log(`  行数：app.js ${app.split('\n').length} 行 · app.css ${css.split('\n').length} 行`);

console.log(fail === 0 ? '\n全部通过 ✅' : `\n失败 ${fail} 项 ❌`);
process.exit(fail === 0 ? 0 : 1);
