/* =============================================================================
 * A02 MCP 工具契约校验（零依赖）
 *
 * 为什么分两步：
 *   本机沙箱禁止 Node 以管道方式捕获子进程输出（命名管道受限），
 *   因此由 shell 负责「喂输入、收输出」，本脚本只负责断言。
 *
 * 用法：
 *   cmd /c "python mcp-server\server.py < tools\mcp-requests.jsonl > target\mcp-out.jsonl 2> target\mcp-err.log"
 *   node tools/mcp-verify.mjs target/mcp-out.jsonl
 * ========================================================================== */
import { readFileSync } from 'node:fs';

const file = process.argv[2] || 'target/mcp-out.jsonl';

let lines;
try {
  lines = readFileSync(file, 'utf8').split('\n').filter((l) => l.trim());
} catch (e) {
  console.error(`无法读取 ${file}：${e.message}`);
  console.error('请先执行：cmd /c "python mcp-server\\server.py < tools\\mcp-requests.jsonl > target\\mcp-out.jsonl"');
  process.exit(2);
}

const responses = lines.map((l) => JSON.parse(l));
const byId = new Map(responses.map((r) => [r.id, r]));

let passed = 0;
const failures = [];

function check(label, condition, detail = '') {
  if (condition) { passed++; console.log(`  ✓ ${label}`); return; }
  failures.push(`${label}${detail ? ' → ' + detail : ''}`);
  console.log(`  ✗ ${label}${detail ? ' → ' + detail : ''}`);
}

/** 取出 tools/call 的 text 内容并解析为对象 */
function payloadOf(id) {
  const r = byId.get(id);
  if (!r || !r.result || !r.result.content) return null;
  try { return JSON.parse(r.result.content[0].text); } catch { return null; }
}

console.log(`MCP 工具契约校验 ← ${file}（${responses.length} 条响应）\n`);

// 1) initialize
const init = byId.get(1);
check('initialize 返回协议版本与服务器信息',
  init?.result?.protocolVersion === '2024-11-05'
  && init?.result?.serverInfo?.name === 'a02-career-mcp',
  JSON.stringify(init?.result?.serverInfo));

// 2) tools/list
const tools = byId.get(2)?.result?.tools || [];
check('tools/list 返回 3 个工具（超出赛题最低 2 个要求）', tools.length === 3, `实际 ${tools.length}`);
const names = tools.map((t) => t.name).sort().join(',');
check('工具名称与设计一致',
  names === 'query_job_market,search_course,verify_certificate', names);
check('每个工具都带 inputSchema（供模型理解入参）',
  tools.every((t) => t.inputSchema && t.inputSchema.type === 'object'));
check('每个工具都声明 required 字段', tools.every((t) => Array.isArray(t.inputSchema.required)));

// 3) query_job_market
const jobs = payloadOf(3);
check('query_job_market 返回岗位与所需技能',
  Array.isArray(jobs?.jobs) && jobs.jobs.length > 0 && jobs.jobs[0].topSkills.length > 0,
  `job=${jobs?.jobs?.[0]?.name} hotScore=${jobs?.jobs?.[0]?.hotScore}`);
check('岗位热度分由公式计算（0–100 之间的数值）',
  typeof jobs?.jobs?.[0]?.hotScore === 'number'
  && jobs.jobs[0].hotScore >= 0 && jobs.jobs[0].hotScore <= 100);
check('工具结果标注数据来源', typeof jobs?.source === 'string' && jobs.source.length > 0);

// 4) search_course
const courses = payloadOf(4);
check('search_course 返回课程，且每条都带来源（无来源不返回）',
  Array.isArray(courses?.courses) && courses.courses.length > 0
  && courses.courses.every((c) => typeof c.source === 'string' && c.source.length > 0),
  courses?.courses?.[0]?.title);

// 5) verify_certificate
const cert = payloadOf(5);
check('verify_certificate 返回认证信息（含颁发机构与有效期）',
  cert?.cert?.name && cert.cert.detail, cert?.cert?.name);
check('认证工具声明不含个人数据（privacyNote）',
  cert?.cert?.personalDataIncluded === false && typeof cert?.privacyNote === 'string');

// 6) 未知工具
const unknown = byId.get(6);
check('未知工具返回 JSON-RPC 错误码 -32602',
  unknown?.error?.code === -32602, JSON.stringify(unknown?.error));

// 7) 注入尝试
const injection = byId.get(7);
check('注入字符被入参白名单拦截（返回 INVALID_ARGUMENT）',
  injection?.result?.isError === true && payloadOf(7)?.error === 'INVALID_ARGUMENT',
  payloadOf(7)?.message);

console.log(`\n共 ${passed + failures.length} 项，失败 ${failures.length} 项。`);
if (failures.length) {
  console.log('\n失败明细：');
  failures.forEach((f) => console.log('  ✗ ' + f));
  process.exit(1);
}
console.log('✅ MCP 协议与工具契约全部通过（含异常处理与注入防护）。');
