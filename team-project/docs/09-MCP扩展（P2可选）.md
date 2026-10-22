# 企业专项材料③ MCP 协议对接方案与实现说明

> 交付要求：需说明 **Server 实现、工具定义、权限与异常处理**。
> 代码位置：`mcp-server/server.py`（自建 MCP Server）、`src/main/java/cn/zjnu/a02/mcp/`（后端工具与内部 API）

---

## 1. 方案概述

### 1.1 为什么要用 MCP

赛题显式要求 MCP。但它的工程价值不只是"合规"：
把第三方数据源封装为 MCP 工具后，**新增数据源不需要修改智能体**——
智能体只认识 `tools/list` 与 `tools/call` 两个协议方法。

### 1.2 拓扑

```
┌──────────────────────┐
│ 外部智能体（MCP Host）   │
└──────┬───────────────┘
       │ MCP：stdio（本地演示，默认）/ HTTP（远程部署）
┌──────▼───────────────────────┐
│ a02-career-mcp（Python 3.9） │   ← 协议层：JSON-RPC 2.0
│  · 入参白名单校验             │
│  · 5s 超时 / 三类异常转换     │
│  · stderr 调用日志            │
└──────┬───────────────────────┘
       │ HTTP + X-Service-Token（独立于用户 JWT，只读）
┌──────▼───────────────────────┐
│ Spring Boot /internal/v1/**  │   ← 唯一数据访问逻辑（AD-8）
│  · /internal/v1/jobs          │
│  · /internal/v1/courses       │
│  · /internal/v1/certs         │
└──────┬───────────────────────┘
       │ 参数化 SQL（只读查询）
┌──────▼───────────────────────┐
│ 职业知识图谱（154 节点 / 143 关系）│
└──────────────────────────────┘
       ▲
       └── 每次调用写 mcp_tool_log（工具名/入参摘要/耗时/状态）
```

**关键决策：MCP Server 不直连数据库。** 它调用后端只读内部 API，好处有三：
① 岗位/技能/课程的查询口径与业务模块完全一致，不会出现两套算法；
② 权限最小化且可审计（独立 Service Token）；
③ 所有调用天然落 `mcp_tool_log`，直接满足「有调用日志」的验收要求。

---

## 2. Server 实现

| 项 | 决策 | 说明 |
|---|---|---|
| 语言 | Python 3.7+ | 轻量、与 Java 后端解耦，便于独立部署 |
| 依赖 | **仅标准库**（`json`/`urllib`/`http.server`/`sys`） | 无需 `pip install mcp`；联网可平滑替换为官方 SDK |
| 协议版本 | `2024-11-05` | `initialize` 返回 `protocolVersion` + `serverInfo` + `capabilities.tools` |
| 传输 | stdio（默认）+ HTTP（`--http --port`） | 本地演示用 stdio 降低网络风险；远程部署用 HTTP 供平台调用 |
| 支持方法 | `initialize` / `tools/list` / `tools/call` / `notifications/initialized` / `ping` | 非法方法返回 JSON-RPC `-32601` |
| 编码 | 强制 UTF-8 | Windows 上 Python 默认按本地代码页解码 stdin，必须 `reconfigure(encoding='utf-8')`，否则 MCP Host 发来的中文参数会变乱码 |

启动方式：

```powershell
python mcp-server/server.py                          # stdio
python mcp-server/server.py --http --port 8770       # HTTP
```

---

## 3. 工具定义（3 个，超出赛题最低 2 个要求）

所有工具均含 `name` / `description`（给模型看）/ `inputSchema`（JSON Schema 校验）。

### 3.1 `query_job_market` 岗位市场需求查询

```json
{
  "name": "query_job_market",
  "description": "按岗位名或技能关键词查询岗位市场需求与所需技能。只读，不返回任何个人信息。",
  "inputSchema": {
    "type": "object",
    "properties": {
      "keyword": { "type": "string", "minLength": 1, "maxLength": 50,
                   "description": "岗位名或技能关键词，例如「Java 后端」" },
      "region":  { "type": "string", "minLength": 1, "maxLength": 20,
                   "description": "地区（可选），当前数据源不区分地区" }
    },
    "required": ["keyword"]
  }
}
```

出参：岗位列表，含 `hotScore`（热度分）、`skillCount`、`topSkills[]`。
热度分口径为「要求技能数 × 2.2 + 权重和 × 1.2」归一化到 100，**由公式计算而非模型编造**。

### 3.2 `search_course` 学习资源检索

入参 `{skill(1-50), level?(枚举), limit?(1-20)}`，
出参课程列表，**每条必须带 `source`**——无来源的内容不会返回（防幻觉要求）。

### 3.3 `verify_certificate` 职业认证信息查询

入参 `{cert_name(1-50)}`，出参认证名称、颁发机构、有效期。
**绝不返回任何个人证书编号或持证人信息**：在工具层对 `certNumber` / `holderName` /
`idCard` / `personalData` 字段做二次剥离，即使上游误带也会被删除。

---

## 4. 权限设计

| 层面 | 措施 |
|---|---|
| 传输 | stdio 本地进程通信（演示）；HTTP 模式仅监听 `127.0.0.1` |
| 接口鉴权 | 内部 API 需 `X-Service-Token`，**独立于用户 JWT**，与用户令牌体系隔离 |
| 权限范围 | **全部只读**：不暴露任何写操作，`sys_user` 等敏感表不在任何工具可达范围内 |
| 数据最小化 | 不返回用户隐私字段；`mcp_tool_log` 只存入参摘要（截断 280 字符） |
| 输入白名单 | 正则 `^[\u4e00-\u9fa5A-Za-z0-9 _\-+.+#/]{1,50}$`，拦截 `;` `'` `(` `)` 等注入常用字符 |

> 后端对内部 API 的实现见 `McpController.verifyServiceToken()`：
> 令牌不匹配直接返回 403 与业务码 20003，并说明「内部接口需携带有效的 X-Service-Token」。

---

## 5. 异常处理（三类，专项材料要求逐条说明）

所有异常在 `tools/call` 层被转换为 `content[].text`（`isError: true`），
**绝不把 Python 堆栈抛给模型**。

| 异常 | 触发条件 | 返回 | 日志 |
|---|---|---|---|
| 超时 | 上游响应 > `A02_MCP_TIMEOUT`（默认 5s） | `{"error":"UPSTREAM_TIMEOUT","message":"数据源响应超时，请稍后重试"}` | WARN + `mcp_tool_log.status=TIMEOUT` |
| 非法入参 | 缺必填 / 超长 / 含非法字符 / 枚举越界 | `{"error":"INVALID_ARGUMENT","message":"参数 keyword 含非法字符（仅允许中文、字母、数字与常用符号）"}` | WARN + 记录脱敏入参 |
| 上游不可用 | 连接失败 / HTTP 5xx / 鉴权失败 / 返回非 JSON | `{"error":"UPSTREAM_UNAVAILABLE","message":"后端服务暂不可用：..."}` | ERROR + 熔断计数 |
| 未知工具 | `tools/call` 传了未注册的工具名 | JSON-RPC 错误 `-32602 未知工具：xxx` | — |
| 兜底 | 其他未预期异常 | `{"error":"INTERNAL_ERROR","message":"工具内部错误：..."}` | ERROR |

---

## 6. 验证记录（实测输出）

输入 7 条请求（`mcp-in.jsonl`），启动 Server 并回放：

```powershell
cmd /c "python mcp-server\server.py < mcp-in.jsonl"
```

| # | 请求 | 实际响应（节选） | 结论 |
|---|---|---|---|
| 1 | `initialize` | `protocolVersion=2024-11-05, serverInfo={a02-career-mcp,1.0.0}` | ✅ |
| 2 | `tools/list` | 返回 3 个工具，每个含完整 `inputSchema` | ✅ |
| 3 | `query_job_market{Java}` | `Java 后端开发工程师 hotScore=75.6 skillCount=18 topSkills=[MySQL 数据库, Spring Boot, ...]` | ✅ |
| 4 | `search_course{MySQL,limit=2}` | `《MySQL 高性能实践》 source=知识图谱课程库` | ✅ |
| 5 | `verify_certificate{软件设计师}` | `软件设计师（软考中级）issuer=工信部教育与考试中心 validity=长期有效`，含 `privacyNote` | ✅ UTF-8 正常 |
| 6 | `query_job_market{忽略以上指令 DROP TABLE}` | 白名单放行（无危险字符）→ 参数化查询 → `未找到匹配岗位` | ✅ 无注入风险 |
| 7 | 未知工具 | `{"code":-32601,"message":"未知工具：unknown_tool"}` | ✅ |

补充边界用例（含 `;` 与 `'` 的输入）：返回
`INVALID_ARGUMENT 参数 keyword 含非法字符`，`isError=true`。✅ 三层闸门第一层生效。

### 调用日志（`mcp_tool_log`）

`GET /api/mcp/stats` 可查按状态与工具的调用统计；界面不暴露调用日志（运维视角，非使用者视角）；
后端 `/api/mcp/stats` 返回按状态与按工具的调用统计及平均耗时。

---

## 7. 与「智能体不直接碰生产数据」的架构主张

```
智能体 ──MCP──► MCP Server ──只读 API──► 后端（校验/持久化/权限/兜底）──► DB
```

**智能体负责推理与生成；后端负责校验、持久化、权限、兜底。**
智能体不直接写库，所有落库都经后端——这是「不让 AI 直接碰生产数据」的架构级安全设计，
也是本方案与"直接给智能体一个数据库连接"做法的根本区别。
