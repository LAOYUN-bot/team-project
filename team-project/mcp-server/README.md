# A02 自建 MCP Server

> 对应需求 **FR-5.5**、企业专项材料③《MCP 协议对接方案与实现说明》。

## 1. 它解决什么问题

赛题要求「完成主流学习/招聘平台的 API 对接」，并显式点名 MCP。
直接写死几个 HTTP 调用的问题是：**每新增一个数据源都要改智能体**。

用 MCP 把数据源变成**插件**：智能体只认识 `tools/list` 与 `tools/call`，
新增数据源只是多注册一个工具，智能体侧零改动。

```
百宝箱智能体 ──MCP(stdio/HTTP)──► a02-career-mcp ──只读内部 API──► Spring Boot ──► DB
```

## 2. 为什么取数要绕一层内部 API

MCP Server 不直连数据库，而是调用后端 `/internal/v1/**`（AD-8）：

| 好处 | 说明 |
|---|---|
| 单一数据逻辑 | 岗位/技能/课程的查询口径与业务模块完全一致，不会出现两套算法 |
| 权限可审计 | 独立 `X-Service-Token`，与用户 JWT 分离，权限最小化（只读） |
| 可观测 | 所有调用写 `mcp_tool_log`，支撑「有调用日志」的验收要求 |

## 3. 三个工具（超出赛题最低 2 个的要求）

| 工具 | 入参 | 出参 | 安全约束 |
|---|---|---|---|
| `query_job_market` | `{keyword(1-50), region?(1-20)}` | 岗位列表 + 热度分 + Top 技能 | 只读；空结果给明确提示 |
| `search_course` | `{skill(1-50), level?(枚举), limit?(1-20)}` | 课程列表，**每条带来源** | 只读；无来源不返回 |
| `verify_certificate` | `{cert_name(1-50)}` | 认证名称 + 颁发机构 + 有效期 | **绝不返回任何个人证书编号**（工具层再次剥离） |

## 4. 三类异常显式处理（专项材料③的核心内容）

| 异常 | 返回 | 处理位置 |
|---|---|---|
| 超时（默认 5s） | `{"error":"UPSTREAM_TIMEOUT","message":"数据源响应超时，请稍后重试"}` | `_get()` 捕获 `URLError(timeout)` |
| 非法入参 | `{"error":"INVALID_ARGUMENT","fields":[...]}` | `require_text()` 白名单校验（长度 + 字符集） |
| 上游不可用 | `{"error":"UPSTREAM_UNAVAILABLE","message":"后端服务暂不可用"}` | `_get()` 捕获 `HTTPError/URLError` |

**绝不把堆栈抛给模型**：所有异常在 `tools/call` 层被转换为
`content[].text`（`isError: true`），模型看到的是可读的错误说明。

## 5. 安全基线

1. **工具全只读**：没有写操作，不返回用户隐私字段。
2. **入参白名单**：`^[\u4e00-\u9fa5A-Za-z0-9 _\-+.+#/]{1,50}$`，
   拦截 `;`、引号、括号等注入常用字符（提示注入经工具外溢，难点 D-5）。
3. **双保险剥离**：`verify_certificate` 在返回前再删一次 `certNumber/holderName/idCard` 等字段。
4. **日志不落敏感数据**：`mcp_tool_log` 只存入参摘要（截断 280 字符）。
5. **UTF-8 强制**：Windows 上 Python 默认按本地代码页解码 stdin，
   会把 MCP Host 发来的 UTF-8 中文解成乱码，因此启动即 `reconfigure(encoding='utf-8')`。

## 6. 运行方式

### stdio（本地演示，推荐）

```powershell
# 平台/Host 以子进程方式启动；也可手工验证：
cmd /c "python mcp-server\server.py < mcp-in.jsonl"
```

### HTTP（远程部署）

```powershell
python mcp-server/server.py --http --port 8770
# POST http://127.0.0.1:8770/mcp   （JSON-RPC 2.0）
# GET  http://127.0.0.1:8770/health
```

### 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `A02_BACKEND_URL` | `http://127.0.0.1:8080` | 后端地址 |
| `A02_SERVICE_TOKEN` | `a02-internal-demo-token` | 内部接口令牌（生产必须改） |
| `A02_MCP_TIMEOUT` | `5` | 上游超时秒数 |

## 7. 验证记录（实测）

```powershell
cmd /c "python mcp-server\server.py < tools\mcp-requests.jsonl > target\mcp-out.jsonl 2>nul"
node tools/mcp-verify.mjs target/mcp-out.jsonl
```

`tools/mcp-verify.mjs` 会对 7 条请求的响应逐项断言（13 项全部通过）：

| # | 请求 | 断言点 |
|---|---|---|
| 1 | `initialize` | 返回协议版本 `2024-11-05` 与 serverInfo |
| 2 | `tools/list` | 3 个工具，名称一致，均含 `inputSchema` 与 `required` |
| 3 | `query_job_market{Java}` | 返回岗位与 Top 技能；热度分为 0–100 的公式计算结果；标注数据来源 |
| 4 | `search_course{MySQL}` | 每条课程都带 `source`（无来源不返回） |
| 5 | `verify_certificate{软件设计师}` | 返回颁发机构与有效期；`personalDataIncluded=false` + `privacyNote` |
| 6 | 未知工具 | JSON-RPC 错误码 `-32602` |
| 7 | `query_job_market{"Java'; DROP TABLE sys_user;--"}` | 白名单拦截 → `INVALID_ARGUMENT`，`isError=true` |

实测响应样例（第 5 条）：

```json
{"jsonrpc":"2.0","id":5,"result":{"content":[{"type":"text","text":
 "{\"privacyNote\": \"本工具绝不返回任何个人证书编号或持证人信息\",
   \"cert\": {\"name\": \"软件设计师（软考中级）\",
              \"detail\": \"{\\\"issuer\\\":\\\"工信部教育与考试中心\\\",\\\"validity\\\":\\\"长期有效\\\"}\",
              \"personalDataIncluded\": false}}"}],"isError":false}}
```

> 协议依赖为零：仅使用 Python 标准库（`json` / `urllib` / `http.server` / `sys`），
> 无需 `pip install mcp`。联网环境可平滑替换为官方 SDK，工具定义与异常约定不变。
