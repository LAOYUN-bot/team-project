/* =============================================================================
 * A02 界面冒烟测试（零依赖）
 *
 * 原理：以 CDP（Chrome DevTools Protocol）驱动本机 Chrome/Edge 的无头模式，
 *       真实登录 → 逐个路由截图 → 收集控制台错误。
 *       只用 Node 内置能力（child_process / fetch / WebSocket），不需要 puppeteer。
 *
 * 用法：
 *   node tools/ui-smoke.mjs                       # 默认 http://127.0.0.1:8080
 *   node tools/ui-smoke.mjs http://127.0.0.1:8081
 *
 * 产物：target/ui-shots/*.png 与 target/ui-shots/console-errors.txt
 * ========================================================================== */
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync, rmSync, existsSync } from 'node:fs';
import { setTimeout as sleep } from 'node:timers/promises';

const BASE = (process.argv[2] || 'http://127.0.0.1:8080').replace(/\/$/, '');
const OUT = 'target/ui-shots';
const PROFILE = 'target/.chrome-profile';
const PORT = 9333;

const CHROME_CANDIDATES = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
];

const chromePath = CHROME_CANDIDATES.find((p) => existsSync(p));
if (!chromePath) {
  console.error('未找到 Chrome / Edge，可执行文件候选：\n' + CHROME_CANDIDATES.join('\n'));
  process.exit(2);
}

rmSync(OUT, { recursive: true, force: true });
mkdirSync(OUT, { recursive: true });
rmSync(PROFILE, { recursive: true, force: true });

const ROUTES = [
  ['onboarding', '① 建档与测评'],
  ['dashboard', '② 成长面板'],
  ['path', '③ 职业路径'],
  ['learning', '④ 学习计划'],
  ['simulation', '⑤ 职场模拟训练'],
  ['chat', '⑥ AI 伙伴对话'],
  ['graph', '⑦ 职业知识图谱'],
  ['platform', '⑧ 平台与 AI 伦理'],
];

const chrome = spawn(chromePath, [
  '--headless=new',
  `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${PROFILE}`,
  '--no-first-run', '--no-default-browser-check', '--disable-gpu',
  '--hide-scrollbars', '--window-size=1600,1200', '--force-device-scale-factor=1',
  'about:blank',
], { stdio: 'ignore' });

const cleanup = () => { try { chrome.kill(); } catch { /* 已退出 */ } };
process.on('exit', cleanup);
process.on('SIGINT', () => { cleanup(); process.exit(130); });

async function findTarget() {
  for (let i = 0; i < 80; i++) {
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/json/list`);
      const list = await res.json();
      const page = list.find((t) => t.type === 'page');
      if (page && page.webSocketDebuggerUrl) return page;
    } catch { /* 还没起来 */ }
    await sleep(400);
  }
  throw new Error('Chrome DevTools 端口未就绪');
}

const target = await findTarget();
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  ws.addEventListener('open', resolve, { once: true });
  ws.addEventListener('error', reject, { once: true });
});

let seq = 0;
const pending = new Map();
const problems = [];

ws.addEventListener('message', (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id);
    pending.delete(msg.id);
    if (msg.error) reject(new Error(JSON.stringify(msg.error)));
    else resolve(msg.result);
    return;
  }
  if (msg.method === 'Runtime.consoleAPICalled' && ['error', 'warning'].includes(msg.params.type)) {
    const text = (msg.params.args || []).map((a) => a.value ?? a.description ?? '').join(' ');
    if (text.trim()) problems.push(`[console.${msg.params.type}] ${text}`);
  }
  if (msg.method === 'Runtime.exceptionThrown') {
    const d = msg.params.exceptionDetails || {};
    problems.push(`[exception] ${d.text || ''} ${d.exception?.description || ''}`.trim());
  }
  if (msg.method === 'Log.entryAdded' && msg.params.entry.level === 'error') {
    const e = msg.params.entry;
    if (!/favicon/i.test(e.text || '')) problems.push(`[log] ${e.text} ${e.url || ''}`);
  }
});

const send = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++seq;
  pending.set(id, { resolve, reject });
  ws.send(JSON.stringify({ id, method, params }));
});

await send('Page.enable');
await send('Runtime.enable');
await send('Log.enable');

async function evaluate(expression) {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.text || '页面脚本异常');
  return r.result?.value;
}

/** 截图前等待 SPA 完成渲染（等待「加载中」提示消失） */
async function waitRendered(timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const busy = await evaluate(`!!document.querySelector('#page .empty')`);
    const hasContent = await evaluate(`!!document.querySelector('#page .page-title')`);
    if (!busy && hasContent) return true;
    await sleep(300);
  }
  return false;
}

console.log(`服务地址 : ${BASE}`);
console.log(`浏览器   : ${chromePath}`);
console.log(`输出目录 : ${OUT}\n`);

async function shot(name, label) {
  const rendered = await waitRendered();
  const full = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
  writeFileSync(`${OUT}/${name}.png`, Buffer.from(full.data, 'base64'));
  const title = await evaluate(`document.querySelector('.page-title')?.textContent || ''`);
  console.log(`${rendered ? 'OK ' : '?? '} ${label.padEnd(18)} → ${name}.png  (页面标题：${title})`);
}

// 1) 登录页
await send('Page.navigate', { url: `${BASE}/` });
await sleep(2600);
const loginProblemBefore = problems.length;
await shot('00-login', '登录页');
if (problems.length > loginProblemBefore) {
  console.log('   ⚠ 登录页出现控制台错误，详见 console-errors.txt');
}

// 2) 使用真实接口登录（同源 fetch，服务端下发 HttpOnly Cookie）
const loginResult = await evaluate(`(async () => {
  const r = await fetch('/api/auth/login', {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'demo', password: 'demo1234' })
  });
  return r.status + ' ' + (await r.text()).slice(0, 80);
})()`);
console.log(`\n登录接口 : ${loginResult}\n`);

// 3) 逐个路由截图
await send('Page.navigate', { url: `${BASE}/#/dashboard` });
await sleep(3000);
for (const [key, label] of ROUTES) {
  if (key !== 'dashboard') {
    await evaluate(`location.hash = '#/${key}'`);
    await sleep(2200);
  }
  await shot(key === 'onboarding' ? '01-onboarding' : key, label);
}

// 4) 额外验证：路径生成的 SSE 进度面板（触发一次真实生成）
await evaluate(`location.hash = '#/path'`);
await sleep(2200);
await evaluate(`document.querySelector('#btn-chaos')?.click()`);
await sleep(6000);
const genText = await evaluate(`document.querySelector('#gen-progress')?.innerText || ''`);
writeFileSync(`${OUT}/path-generation.txt`, genText);
const genFull = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
writeFileSync(`${OUT}/09-path-generation.png`, Buffer.from(genFull.data, 'base64'));
console.log(`\n路径生成（含校验重试演示）输出：\n${genText}\n`);

// 5) 汇总控制台问题
const unique = [...new Set(problems)];
writeFileSync(`${OUT}/console-errors.txt`, unique.join('\n'), 'utf8');
console.log(unique.length === 0
  ? '✅ 全流程无控制台错误'
  : `⚠ 捕获 ${unique.length} 条控制台问题，已写入 ${OUT}/console-errors.txt\n` + unique.slice(0, 12).join('\n'));

cleanup();
ws.close();
process.exit(0);
