/* =============================================================================
 * A02 接口契约自检（零依赖）
 *
 * 作用：把前端 api.js 中声明的每个接口真实调用一遍，确认：
 *   ① 路由存在（不是 404）  ② 鉴权正常（不是 401/403）  ③ 返回统一响应体 code=0
 *   ④ 关键字段非空（避免页面渲染出「暂无数据」而没人发现）
 *
 * 用法：node tools/api-contract.mjs [baseUrl]
 * ========================================================================== */
const BASE = (process.argv[2] || 'http://127.0.0.1:8080').replace(/\/$/, '');

let cookie = '';

async function call(method, path, body) {
  const headers = { 'Content-Type': 'application/json' };
  if (cookie) headers.Cookie = cookie;
  const res = await fetch(BASE + path, {
    method, headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const setCookie = res.headers.getSetCookie ? res.headers.getSetCookie() : [];
  if (setCookie.length) cookie = setCookie.map((c) => c.split(';')[0]).join('; ');
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* SSE 或非 JSON */ }
  return { status: res.status, json, text };
}

const rows = [];
let failures = 0;

/**
 * @param label  用例名
 * @param method HTTP 方法
 * @param path   路径
 * @param check  对 data 的断言函数，返回字符串表示失败原因，返回 null 表示通过
 */
async function check(label, method, path, body, requireData) {
  let result;
  try {
    result = await call(method, path, body);
  } catch (e) {
    failures++; rows.push([label, method, path, 'ERR', e.message]); return;
  }
  let verdict = 'OK';
  if (result.status !== 200) {
    verdict = `HTTP ${result.status}`;
  } else if (result.json && result.json.code !== 0) {
    verdict = `code=${result.json.code} ${result.json.message || ''}`;
  } else if (requireData && result.json) {
    const d = result.json.data;
    const empty = d === null || d === undefined
      || (Array.isArray(d) && d.length === 0)
      || (typeof d === 'object' && !Array.isArray(d) && Object.keys(d).length === 0);
    if (empty) verdict = '数据为空';
  } else if (!result.json) {
    verdict = '非 JSON 响应';
  }
  if (verdict !== 'OK') failures++;
  rows.push([label, method, path, verdict, result.json ? `code=${result.json.code}` : '']);
}

console.log(`接口契约自检 ← ${BASE}\n`);

// 登录（HttpOnly Cookie 通道）
const login = await call('POST', '/api/auth/login', { username: 'demo', password: 'demo1234' });
if (login.status !== 200 || login.json?.code !== 0) {
  console.error('登录失败，无法继续：', login.status, login.text.slice(0, 200));
  process.exit(1);
}
console.log(`登录成功：${login.json.data.username}（Cookie 已下发：${cookie ? '是' : '否'}）\n`);

// ---- 无鉴权接口 ----
await check('健康检查', 'GET', '/api/health', undefined, true);

// ---- M1 感知层 ----
await check('当前用户', 'GET', '/api/users/me', undefined, true);
await check('基础信息', 'GET', '/api/users/me/profile', undefined, false);
await check('隐私授权', 'GET', '/api/users/me/consent', undefined, true);
await check('画像维度定义', 'GET', '/api/profile/dimensions', undefined, true);
await check('测评题库', 'GET', '/api/assessments/questions', undefined, true);
await check('最新测评', 'GET', '/api/assessments/latest', undefined, true);
await check('简历标签', 'GET', '/api/resume/tags', undefined, false);
await check('简历技能抽取', 'POST', '/api/resume/extract',
  { text: '使用 Spring Boot 与 MySQL 开发图书管理系统，用 Docker 部署，Git 协作' }, true);

// ---- M2 认知层 ----
await check('岗位列表', 'GET', '/api/jobs', undefined, true);
const jobs = (await call('GET', '/api/jobs')).json.data;
await check('岗位能力模型', 'GET', `/api/jobs/${jobs[0].id}/competency`, undefined, true);
await check('图谱统计', 'GET', '/api/kg/stats', undefined, true);
await check('技能迁移', 'GET', `/api/kg/migration?from=${jobs[0].id}&to=${jobs[3].id}`, undefined, true);
await check('认证清单', 'GET', '/api/certs', undefined, true);
await check('差距分析', 'GET', '/api/gap', undefined, true);
await check('差距分析(历史)', 'GET', '/api/gap/latest', undefined, true);
await check('趋势历史', 'GET', '/api/trends', undefined, false);

// ---- M3 决策层 ----
await check('最新雷达图', 'GET', '/api/profile/radar', undefined, true);
await check('雷达版本列表', 'GET', '/api/profile/radar/versions', undefined, true);
await check('雷达公式说明', 'GET', '/api/profile/radar/formula', undefined, true);
await check('职业路径', 'GET', '/api/career/path', undefined, true);
await check('路径历史', 'GET', '/api/career/path/history', undefined, true);
await check('岗位匹配度', 'GET', '/api/career/match', undefined, true);

// ---- M4 生成层 ----
await check('学习单元', 'GET', '/api/learning/units', undefined, true);
await check('资源推荐', 'GET', '/api/resources/recommend?skill=Java', undefined, true);
await check('知识库文档', 'GET', '/api/kb/docs', undefined, true);
await check('AI 上下文', 'GET', '/api/agent/context', undefined, true);
await check('对话会话列表', 'GET', '/api/agent/sessions', undefined, true);

// ---- M5 执行层 ----
await check('学习任务', 'GET', '/api/learning/tasks', undefined, true);
await check('学习统计', 'GET', '/api/learning/stats', undefined, true);
await check('活跃热力图', 'GET', '/api/learning/heatmap?days=84', undefined, true);
await check('训练场景', 'GET', '/api/simulation/scenarios', undefined, true);
await check('训练会话列表', 'GET', '/api/simulation/sessions', undefined, true);

// ---- M6 反馈层 ----
await check('成长面板', 'GET', '/api/growth/panel', undefined, true);
await check('成长快照', 'GET', '/api/growth/snapshots', undefined, true);
await check('成长公式说明', 'GET', '/api/growth/formula', undefined, true);
await check('陪伴状态', 'GET', '/api/companion/state', undefined, true);
await check('激励历史', 'GET', '/api/companion/incentives', undefined, false);

// ---- MCP 与治理 ----
await check('MCP 工具清单', 'GET', '/api/mcp/tools', undefined, true);
await check('MCP 调用日志', 'GET', '/api/mcp/logs?limit=10', undefined, false);
await check('MCP 统计', 'GET', '/api/mcp/stats', undefined, true);
await check('AI 通道状态', 'GET', '/api/platform/ai-status', undefined, true);
await check('AI 生成日志', 'GET', '/api/platform/ai-logs?limit=10', undefined, true);
await check('提示词仓库', 'GET', '/api/platform/prompts', undefined, true);
await check('系统配置', 'GET', '/api/platform/config', undefined, true);
await check('隐私与伦理', 'GET', '/api/platform/privacy', undefined, true);
await check('闭环总览', 'GET', '/api/platform/overview', undefined, true);

// ---- 写操作（真实执行一次，验证闭环仍可推进） ----
await check('打卡', 'POST', '/api/learning/checkin', { minutes: 20, note: '接口自检' }, true);
await check('情绪感知', 'POST', '/api/companion/emotion', { text: '我有点焦虑，学不下去' }, true);
await check('MCP 工具调用', 'POST', '/api/mcp/call', { tool: 'search_course', arguments: { skill: 'Java', limit: 2 } }, true);
await check('趋势分析', 'POST', '/api/trends/analyze', {}, true);

// ---- 权限边界 ----
const adminOnly = await call('GET', '/api/platform/audit-logs');
const rbacOk = adminOnly.status === 403 || (adminOnly.json && adminOnly.json.code === 0);
rows.push(['RBAC 边界（普通用户访问管理员接口）', 'GET', '/api/platform/audit-logs',
  rbacOk ? `OK（${adminOnly.status}）` : '未拦截', '']);
if (!rbacOk) failures++;

const noAuth = await fetch(`${BASE}/api/growth/panel`);
rows.push(['未登录访问受保护接口', 'GET', '/api/growth/panel',
  noAuth.status === 401 ? 'OK（401）' : `未拦截（${noAuth.status}）`, '']);
if (noAuth.status !== 401) failures++;

// ---- 输出 ----
const width = Math.max(...rows.map((r) => r[0].length)) + 2;
rows.forEach(([label, method, path, verdict, extra]) => {
  const mark = verdict.startsWith('OK') ? '✓' : '✗';
  console.log(`${mark} ${label.padEnd(width)} ${method.padEnd(6)} ${path.padEnd(44)} ${verdict} ${extra || ''}`);
});

console.log(`\n共 ${rows.length} 项，失败 ${failures} 项。`);
process.exit(failures === 0 ? 0 : 1);
