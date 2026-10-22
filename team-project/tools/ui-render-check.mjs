#!/usr/bin/env node
/**
 * 真实浏览器渲染自检（零依赖 · CDP over WebSocket）
 *
 * 前置：headless Edge 已用 --remote-debugging-port=9223 启动，应用跑在 8080。
 * 检查：登录 → 逐页导航 → 截图 + 抓取可见文本 + 收集 JS 报错 / 失败请求。
 *
 * 用法：node tools/ui-render-check.mjs [port] [appBase]
 */
import fs from 'node:fs';
import path from 'node:path';

const CDP_PORT = Number(process.argv[2] || 9223);
const APP = process.argv[3] || 'http://127.0.0.1:8080';
const OUT = path.resolve(import.meta.dirname, '../target/ui-shots');
fs.mkdirSync(OUT, { recursive: true });

/* ------------------------------------------------------------------ CDP */
async function cdp() {
  const list = await (await fetch(`http://127.0.0.1:${CDP_PORT}/json/version`)).json();
  const ws = new WebSocket(list.webSocketDebuggerUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

  let id = 0;
  const waiters = new Map();
  const listeners = [];
  const events = [];

  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id && waiters.has(msg.id)) {
      const { res, rej } = waiters.get(msg.id);
      waiters.delete(msg.id);
      if (msg.error) rej(new Error(`${msg.error.message} (${JSON.stringify(msg.error.data ?? '')})`));
      else res(msg.result);
      return;
    }
    if (msg.method) {
      events.push(msg);
      listeners.forEach((fn) => fn(msg));
    }
  };

  const send = (method, params = {}, sessionId) => new Promise((res, rej) => {
    const n = ++id;
    waiters.set(n, { res, rej });
    ws.send(JSON.stringify({ id: n, method, params, ...(sessionId ? { sessionId } : {}) }));
    setTimeout(() => {
      if (waiters.has(n)) { waiters.delete(n); rej(new Error(`timeout: ${method}`)); }
    }, 45000);
  });

  return { ws, send, events, on: (fn) => listeners.push(fn) };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ---------------------------------------------------------------- 主流程 */
const { ws, send, on } = await cdp();
const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
const S = (m, p) => send(m, p, sessionId);

await S('Page.enable');
await S('Runtime.enable');
await S('Log.enable');
await S('Network.enable');
await S('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });

const jsErrors = [];
const badRequests = [];
on((msg) => {
  if (msg.method === 'Runtime.exceptionThrown') {
    const d = msg.params.exceptionDetails;
    jsErrors.push(`${d.exception?.description || d.text}`);
  }
  if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') {
    jsErrors.push(msg.params.args.map((a) => a.value ?? a.description ?? a.type).join(' '));
  }
  if (msg.method === 'Log.entryAdded' && msg.params.entry.level === 'error') {
    jsErrors.push(msg.params.entry.text);
  }
  if (msg.method === 'Network.responseReceived') {
    const r = msg.params.response;
    if (r.status >= 400 && !r.url.includes('favicon')) badRequests.push(`${r.status} ${r.url}`);
  }
  if (msg.method === 'Network.loadingFailed' && !msg.params.errorText.includes('ERR_ABORTED')) {
    badRequests.push(`FAILED ${msg.params.errorText}`);
  }
});

const evalJs = async (expression, awaitPromise = true) => {
  const r = await S('Runtime.evaluate', { expression, awaitPromise, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
  return r.result.value;
};

const waitIdle = async (ms = 900) => {
  await evalJs('document.readyState');
  await sleep(ms);
};

let fail = 0;
const ok = (m) => console.log(`  ✓ ${m}`);
const bad = (m) => { fail++; console.log(`  ✗ ${m}`); };

/* 1. 打开首页（未登录 → 登录页） */
await S('Page.navigate', { url: APP + '/' });
await waitIdle(1400);

const title = await evalJs('document.title');
const hasAuth = await evalJs("!!document.querySelector('#auth-submit')");
const authText = await evalJs("document.body.innerText.slice(0, 300)");
if (title.includes('灯塔')) ok(`页面标题：${title}`);
else bad(`标题异常：${title}`);
if (hasAuth) ok('未登录时显示登录页');
else bad('未登录时没有渲染登录表单');

const shot1 = await S('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
fs.writeFileSync(path.join(OUT, '00-login.png'), Buffer.from(shot1.data, 'base64'));
console.log('    登录页文本：' + authText.replace(/\n+/g, ' / ').slice(0, 160));

/* 2. 登录 */
const loginResult = await evalJs(`(async () => {
  const r = await fetch('/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, credentials: 'include',
    body: JSON.stringify({ username: 'demo', password: 'demo1234' })
  });
  const b = await r.json().catch(() => ({}));
  return { status: r.status, code: b.code ?? null, hasUser: !!(b.data && b.data.user) };
})()`);
if (loginResult.status === 200) ok(`登录成功 demo/demo1234（HTTP ${loginResult.status}）`);
else bad(`登录失败：${JSON.stringify(loginResult)}`);

/* 3. 逐页导航 */
const ROUTES = [
  ['home', '今日'],
  ['ability', '我的能力'],
  ['path', '职业路径'],
  ['plan', '学习计划'],
  ['practice', '情景练习'],
  ['partner', '职业伙伴'],
  ['library', '资料问答'],
  ['settings', '设置'],
];

await S('Page.navigate', { url: APP + '/' });
await waitIdle(2200);

const pages = [];
for (let i = 0; i < ROUTES.length; i++) {
  const [key, label] = ROUTES[i];
  const errBefore = jsErrors.length;

  const clicked = await evalJs(`(() => {
    const el = document.querySelector('.nav-item[data-route="${key}"]');
    if (!el) return false;
    el.click();
    return true;
  })()`);
  if (!clicked) { bad(`找不到导航项「${label}」(${key})`); continue; }
  await waitIdle(1600);

  const info = await evalJs(`(() => {
    const page = document.querySelector('#page');
    const txt = page ? page.innerText.trim() : '';
    return {
      chars: txt.length,
      head: txt.slice(0, 120).replace(/\\n+/g, ' / '),
      cards: page ? page.querySelectorAll('.card').length : 0,
      svgs: page ? page.querySelectorAll('svg').length : 0,
      errorPanel: !!(page && page.querySelector('.callout.warn')),
      emptyish: /^(出了点问题|正在加载)/.test(txt),
      activeNav: (document.querySelector('.nav-item.active')?.textContent || '').trim(),
    };
  })()`);

  const shot = await S('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
  fs.writeFileSync(path.join(OUT, `${String(i + 1).padStart(2, '0')}-${key}.png`), Buffer.from(shot.data, 'base64'));

  const newErr = jsErrors.length - errBefore;
  pages.push({ key, label, ...info, newErr });

  const line = `${label.padEnd(5)} 文本 ${String(info.chars).padStart(5)} 字 · 卡片 ${info.cards} · 图表 ${info.svgs} · 报错 +${newErr}`;
  if (info.chars < 40 || info.emptyish || info.errorPanel || newErr > 0) bad(line + `  ← ${info.head}`);
  else ok(line + `  ← ${info.head.slice(0, 60)}`);
}

/* 4. 汇总 */
if (jsErrors.length === 0) ok('全程无 JS 异常 / console.error');
else {
  bad(`捕获 ${jsErrors.length} 条 JS 报错`);
  [...new Set(jsErrors.map((e) => e.split('\n')[0].slice(0, 160)))].slice(0, 8).forEach((e) => console.log(`      ${e}`));
}
if (badRequests.length === 0) ok('全程无 4xx/5xx 响应');
else {
  const uniq = [...new Set(badRequests)];
  // 401 出现在登录前属正常
  const real = uniq.filter((u) => !/^401 .*\/api\/users\/me/.test(u));
  if (real.length === 0) ok(`只有登录前的预期 401（${uniq.length} 条）`);
  else { bad(`异常响应 ${real.length} 条`); real.slice(0, 8).forEach((u) => console.log(`      ${u}`)); }
}

console.log(`\n  截图 ${fs.readdirSync(OUT).length} 张 → ${path.relative(process.cwd(), OUT)}`);
console.log(fail === 0 ? '渲染检查全部通过 ✅' : `渲染检查失败 ${fail} 项 ❌`);

ws.close();
await send('Target.closeTarget', { targetId }).catch(() => {});
process.exit(fail === 0 ? 0 : 1);
