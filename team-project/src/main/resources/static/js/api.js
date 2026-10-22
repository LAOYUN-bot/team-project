/* =============================================================================
 * A02 API 客户端
 *
 * 设计要点：
 *  1. 令牌走 HttpOnly Cookie（后端下发），**前端不接触令牌**，防 XSS 窃取；
 *     因此这里不实现任何 token 读写逻辑，只保证同源请求自动携带 Cookie。
 *  2. SSE 流式采用 fetch + ReadableStream 手工解析：
 *     EventSource 不支持自定义 Header 与 POST，无法满足「统一入口 + 结构化事件」的需求。
 *  3. 统一响应体 { code, message, data, traceId } 在此集中解包，业务代码只拿 data。
 * ========================================================================== */
const Api = (() => {
  const BASE = '';

  class ApiError extends Error {
    constructor(code, message, traceId, status) {
      super(message || '请求失败');
      this.code = code;
      this.traceId = traceId;
      this.status = status;
    }
  }

  async function request(path, options = {}) {
    const opts = Object.assign({ credentials: 'same-origin', headers: {} }, options);
    if (opts.body !== undefined && typeof opts.body !== 'string') {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(opts.body);
    }
    const res = await fetch(BASE + path, opts);
    const text = await res.text();
    let payload = null;
    try { payload = text ? JSON.parse(text) : null; } catch (e) { payload = null; }

    if (!res.ok) {
      const code = payload && payload.code !== undefined ? payload.code : res.status;
      const msg = (payload && payload.message) || `HTTP ${res.status}`;
      throw new ApiError(code, msg, payload && payload.traceId, res.status);
    }
    if (payload && payload.code !== 0) {
      throw new ApiError(payload.code, payload.message, payload.traceId, res.status);
    }
    return payload ? payload.data : null;
  }

  const get = (path) => request(path);
  const post = (path, body) => request(path, { method: 'POST', body: body === undefined ? {} : body });
  const put = (path, body) => request(path, { method: 'PUT', body });
  const patch = (path, body) => request(path, { method: 'PATCH', body });
  const del = (path) => request(path, { method: 'DELETE' });

  /**
   * SSE 流式请求。
   * @param path    接口路径
   * @param body    请求体
   * @param handler { onEvent(name, dataObject) }
   * @returns Promise，流结束时 resolve
   */
  async function stream(path, body, handler) {
    const res = await fetch(BASE + path, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify(body === undefined ? {} : body),
    });

    if (!res.ok) {
      const text = await res.text();
      let payload = null;
      try { payload = JSON.parse(text); } catch (e) { /* 非 JSON 错误体 */ }
      throw new ApiError(payload ? payload.code : res.status,
        (payload && payload.message) || `HTTP ${res.status}`);
    }
    if (!res.body) throw new ApiError(-1, '当前浏览器不支持流式响应');

    const reader = res.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';

    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      // 事件之间以空行分隔
      let idx;
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const raw = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 2);
        let name = 'message';
        const dataLines = [];
        raw.split('\n').forEach((line) => {
          if (line.startsWith('event:')) name = line.slice(6).trim();
          else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
        });
        if (!dataLines.length) continue;
        let parsed = dataLines.join('\n');
        try { parsed = JSON.parse(parsed); } catch (e) { /* 保留原始字符串 */ }
        handler(name, parsed);
      }
    }
  }

  /* -------------------------------------------------------------------------
   * 接口封装（与 docs/04-架构设计.md §10.3 接口清单一一对应）
   * ---------------------------------------------------------------------- */
  const api = {
    // M1 感知
    register: (b) => post('/api/auth/register', b),
    login: (b) => post('/api/auth/login', b),
    logout: () => post('/api/auth/logout'),
    me: () => get('/api/users/me'),
    getProfile: () => get('/api/users/me/profile'),
    saveProfile: (b) => put('/api/users/me/profile', b),
    getConsent: () => get('/api/users/me/consent'),
    saveConsent: (b) => post('/api/users/me/consent', b),
    exportData: () => get('/api/users/me/data'),
    eraseData: () => del('/api/users/me/data'),
    dimensions: () => get('/api/profile/dimensions'),
    questions: () => get('/api/assessments/questions'),
    submitAssessment: (b) => post('/api/assessments', b),
    latestAssessment: () => get('/api/assessments/latest'),
    extractResume: (b) => post('/api/resume/extract', b),
    resumeTags: () => get('/api/resume/tags'),
    confirmResumeTags: (b) => put('/api/resume/tags', b),

    // M2 认知
    jobs: () => get('/api/jobs'),
    competency: (id) => get(`/api/jobs/${id}/competency`),
    skillCourses: (id) => get(`/api/skills/${id}/courses`),
    kgStats: () => get('/api/kg/stats'),
    migration: (from, to) => get(`/api/kg/migration?from=${from}&to=${to}`),
    certs: () => get('/api/certs'),
    gap: (jobId) => get('/api/gap' + (jobId ? `?jobId=${jobId}` : '')),
    latestGap: () => get('/api/gap/latest'),
    analyzeTrend: (b) => post('/api/trends/analyze', b || {}),
    trends: () => get('/api/trends'),

    // M3 决策
    radar: (version) => get('/api/profile/radar' + (version ? `?version=${version}` : '')),
    radarVersions: () => get('/api/profile/radar/versions'),
    radarCompare: (a, b) => get(`/api/profile/radar/compare?a=${a}&b=${b}`),
    radarFormula: () => get('/api/profile/radar/formula'),
    recalculateRadar: () => post('/api/profile/radar/recalculate'),
    path: () => get('/api/career/path'),
    pathHistory: () => get('/api/career/path/history'),
    updateStage: (id, b) => put(`/api/career/path/stages/${id}`, b),
    addStage: (pathId, b) => post(`/api/career/path/${pathId}/stages`, b),
    deleteStage: (id) => del(`/api/career/path/stages/${id}`),
    replan: () => post('/api/career/path/replan'),
    match: (jobId) => get('/api/career/match' + (jobId ? `?jobId=${jobId}` : '')),

    // M4 生成
    units: () => get('/api/learning/units'),
    generateUnits: (stageId) => post(`/api/learning/stages/${stageId}/units`),
    recommend: (skill) => get(`/api/resources/recommend?skill=${encodeURIComponent(skill)}`),
    kbDocs: () => get('/api/kb/docs'),
    agentSessions: () => get('/api/agent/sessions'),
    agentMessages: (id) => get(`/api/agent/sessions/${id}/messages`),
    agentContext: () => get('/api/agent/context'),

    // M5 执行
    tasks: (status) => get('/api/learning/tasks' + (status ? `?status=${status}` : '')),
    generateTasks: (limit) => post('/api/learning/tasks/generate', { limit }),
    updateTask: (id, status) => patch(`/api/learning/tasks/${id}`, { status }),
    checkin: (minutes, note) => post('/api/learning/checkin', { minutes, note }),
    learningStats: () => get('/api/learning/stats'),
    heatmap: (days) => get(`/api/learning/heatmap?days=${days || 84}`),
    scenarios: () => get('/api/simulation/scenarios'),
    startSimulation: (scenarioId) => post(`/api/simulation/${scenarioId}/sessions`),
    simulationSessions: () => get('/api/simulation/sessions'),
    simulationDetail: (id) => get(`/api/simulation/sessions/${id}`),
    evaluateSimulation: (id) => post(`/api/simulation/sessions/${id}/evaluate`),

    // M6 反馈
    growthPanel: () => get('/api/growth/panel'),
    growthCompare: (a, b) => get(`/api/growth/compare?a=${a}&b=${b}`),
    growthSnapshots: () => get('/api/growth/snapshots'),
    resolveAlert: (id) => post(`/api/growth/alerts/${id}/resolve`),
    growthFormula: () => get('/api/growth/formula'),
    companionState: () => get('/api/companion/state'),
    incentives: () => get('/api/companion/incentives'),
    emotion: (text) => post('/api/companion/emotion', { text }),

    // MCP（P2 可选）与平台治理
    mcpTools: () => get('/api/mcp/tools'),
    mcpCall: (tool, args) => post('/api/mcp/call', { tool, arguments: args }),
    mcpLogs: (limit) => get(`/api/mcp/logs?limit=${limit || 20}`),
    mcpStats: () => get('/api/mcp/stats'),

    // M7 集成层：自建 RAG（v2.0 新增，FR-7.2）
    kbStats: () => get('/api/kb/stats'),
    kbSearch: (query, topK) => post('/api/kb/search', { query, topK }),
    kbAsk: (question) => post('/api/kb/ask', { question }),
    kbEval: (k) => post('/api/kb/eval', { k: k || 5 }),
    kbIngest: () => post('/api/kb/ingest'),

    // NFR-8：模型调用统计与限流状态
    llmStats: (days) => get(`/api/platform/llm-stats?days=${days || 7}`),
    llmLimit: () => get('/api/platform/llm-limit'),

    aiStatus: () => get('/api/platform/ai-status'),
    aiLogs: (limit) => get(`/api/platform/ai-logs?limit=${limit || 30}`),
    prompts: () => get('/api/platform/prompts'),
    auditLogs: (limit) => get(`/api/platform/audit-logs?limit=${limit || 30}`),
    platformConfig: () => get('/api/platform/config'),
    privacy: () => get('/api/platform/privacy'),
    overview: () => get('/api/platform/overview'),

    // 流式
    generatePathStream: (b, handler) => stream('/api/career/path/generate', b, handler),
    agentChatStream: (b, handler) => stream('/api/agent/chat', b, handler),
    simulationMessageStream: (id, b, handler) => stream(`/api/simulation/sessions/${id}/messages`, b, handler),
  };

  return { api, request, stream, ApiError };
})();
