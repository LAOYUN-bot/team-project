# 实验二：前后端分离与 RESTful API

- 姓名：＿＿＿＿＿　学号：＿＿＿＿＿　日期：＿＿＿＿＿

---

## 一、实验目标（课程原文）

> 后端提供 RESTful JSON 接口，前端 Vue 调用，Ajax / Axios 交互，解决跨域。

课程步骤要点：`@RestController` 暴露接口；Vue 组件 + Axios；Spring 配置 CORS；分别构建部署并联调。

---

## 二、实验环境

JDK 17 / Maven 3.9 / Spring Boot 4.0.8 / 浏览器 / Postman 或 curl。
前端：**开发机离线、npm 源不可达**，因此本项目用原生 ES6 + fetch 实现，
并通过 `Api` 客户端封装达到与 Axios 相同的职责（见 §四 问题 1）。

---

## 三、实验步骤

### 3.1 后端：RESTful 接口设计

统一响应体（所有接口一致，前端只写一次解包逻辑）：

```json
{ "code": 0, "message": "success", "data": { }, "traceId": "b7f3c1e2" }
```

HTTP 方法与语义的对应（节选，完整 81 个端点见 `docs/02-需求与设计.md` §6.2）：

| 方法 | 路径 | 语义 |
|---|---|---|
| GET | `/api/career/path` | 查询资源 |
| POST | `/api/career/path/generate` | 创建资源（生成路径） |
| PUT | `/api/career/path/stages/{id}` | 整体替换阶段字段 |
| PATCH | `/api/learning/tasks/{id}` | 局部更新（只改 status） |
| DELETE | `/api/career/path/stages/{id}` | 删除资源 |

```java
@RestController
@RequestMapping("/api")
public class DecisionController {

    @GetMapping("/career/path")
    public ApiResponse<Map<String, Object>> getPath() {
        return ApiResponse.ok(careerPathService.getPath(AuthContext.userId()));
    }

    @PutMapping("/career/path/stages/{stageId}")
    public ApiResponse<Map<String, Object>> updateStage(@PathVariable long stageId,
                                                        @RequestBody CareerPathService.StageUpdate update) {
        return ApiResponse.ok(careerPathService.updateStage(AuthContext.userId(), stageId, update));
    }
}
```

📷 **截图 1**：`DecisionController` 中的 RESTful 注解

### 3.2 解决跨域（CORS）

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)     // 允许携带 Cookie（HttpOnly 令牌）
                .maxAge(3600);
    }
}
```

**两个关键点**：
1. 用 `allowedOriginPatterns` 而不是 `allowedOrigins("*")` —— 后者与 `allowCredentials(true)` 冲突（浏览器会拒绝）；
2. `allowCredentials(true)` 是必需的，因为本项目的认证令牌走 HttpOnly Cookie。

📷 **截图 2**：`WebConfig` 的 CORS 配置
📷 **截图 3**：浏览器 F12 → Network，成功跨域请求的响应头（含 `Access-Control-Allow-Origin`）

> 本项目前端与后端**同源**（静态资源由 Spring Boot 一起托管），
> 因此生产路径不触发 CORS；上面的配置是为「前端独立跑在 :5173 的 Vite dev server」这类
> 分离部署场景准备的，可在实验报告中通过 Postman 携带 `Origin` 头验证。

### 3.3 前端：请求封装（等价于 Axios 拦截器）

`src/main/resources/static/js/api.js`：

```javascript
async function request(path, options = {}) {
  const opts = Object.assign({ credentials: 'same-origin', headers: {} }, options);
  if (opts.body !== undefined && typeof opts.body !== 'string') {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(opts.body);
  }
  const res = await fetch(path, opts);
  const payload = await res.json();

  if (!res.ok) throw new ApiError(payload.code ?? res.status, payload.message, payload.traceId, res.status);
  if (payload.code !== 0) throw new ApiError(payload.code, payload.message, payload.traceId, res.status);
  return payload.data;          // 业务代码只拿 data
}
```

**设计说明**：把「统一响应体解包 + 错误码转异常 + traceId 透出」收敛在一个函数里，
业务代码只写 `const path = await api.path()`，与 Axios 拦截器的职责划分完全一致。

📷 **截图 4**：`api.js` 的 request 封装

### 3.4 前端：调用与渲染

```javascript
async function viewPath() {
  const [path, match] = await Promise.all([
    api.path().catch(() => ({ exists: false })),
    api.match().catch(() => null),
  ]);
  // 渲染阶段时间轴、匹配度条形图 …
}
```

### 3.5 流式接口（本项目额外实现）

路径生成与 AI 对话使用 **SSE**。因为 `EventSource` 不能自定义请求头、也不支持 POST，
前端改用 `fetch + ReadableStream` 手工解析 SSE：

```javascript
async function stream(path, body, handler) {
  const res = await fetch(path, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify(body ?? {}),
  });
  const reader = res.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let idx;
    while ((idx = buffer.indexOf('\n\n')) >= 0) {          // 事件以空行分隔
      const raw = buffer.slice(0, idx); buffer = buffer.slice(idx + 2);
      let name = 'message'; const dataLines = [];
      raw.split('\n').forEach((line) => {
        if (line.startsWith('event:')) name = line.slice(6).trim();
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
      });
      if (dataLines.length) handler(name, JSON.parse(dataLines.join('\n')));
    }
  }
}
```

📷 **截图 5**：点击「生成路径」时 F12 → Network 中 `generate` 请求的 EventStream 面板

### 3.6 联调验证

**方式一：接口自检脚本（推荐，可复现）**

```powershell
node tools/api-contract.mjs
# 共 53 项，失败 0 项
```

**方式二：浏览器实机联调**

1. 启动 `.\run.ps1`，打开 http://localhost:8080
2. 登录 `demo / demo1234`
3. 依次进入「建档与测评 → 成长面板 → 职业路径 → 学习计划 → 职场模拟训练 → AI 伙伴对话」
4. F12 Console 应无红色报错；Network 中每个接口返回 `code=0`

📷 **截图 6**：F12 Network 面板（多个 `/api/**` 请求 200）
📷 **截图 7**：成长面板页面（4 类图表渲染成功）

---

## 四、遇到的问题与解决

| # | 问题 | 解决 |
|---|------|------|
| 1 | 开发机离线，无法 `npm install vue` / `axios` / `echarts` | 前端改为**零依赖原生 ES6 + 手写 SVG 图表**；把请求封装成 `Api` 客户端（职责等同 Axios 拦截器），图表调用接口保持与 ECharts 版本一致，联网后可平滑替换 |
| 2 | `allowedOrigins("*")` 与 `allowCredentials(true)` 同时配置时浏览器拒绝 | 改用 `allowedOriginPatterns(...)` |
| 3 | `EventSource` 无法带 Authorization 头 | 改用 `fetch + ReadableStream` 手工解析 SSE，令牌走 HttpOnly Cookie 自动携带 |
| 4 | SSE 请求是异步的，`afterCompletion` 不触发导致登录态线程变量泄漏 | 在 `preHandle` 里**先清空**再写入；并补了「未登录必须 401」的自动化断言防止回归 |
| 5 | 中文字符在响应中乱码的疑虑 | 用十六进制核对原始字节确认是 UTF-8 正确编码，属控制台显示问题 |

---

## 五、验证与结果

| 验证项 | 命令 / 操作 | 结果 |
|---|---|---|
| 接口契约 53 项 | `node tools/api-contract.mjs` | 全部通过 |
| 未登录拦截 | 无 Cookie 请求 `/api/growth/panel` | 401（业务码 20001） |
| 越权拦截 | demo 账号请求 `/api/platform/audit-logs` | 403（业务码 20003） |
| 前端无控制台错误 | 浏览器逐页走查 F12 | 无红色报错 |
| 图表渲染 | 成长面板 4 类图表 | 雷达/进度环/成长曲线/热力图均有真实数据 |

---

## 六、总结与反思

> （请填写自己的内容）

**要点参考**
- RESTful 的价值在于「用 HTTP 语义表达意图」：`PATCH` 只改状态、`PUT` 整体替换，
  看接口名就知道影响范围；
- 统一响应体让前端只需写一次解包逻辑，`traceId` 让排障时能把前端报错和后端日志对上；
- **跨域不是配一个 `*` 就完事** —— 只要涉及 Cookie 凭证，就必须用 origin 模式匹配。

---

## 七、AI 工具使用记录

### 7.1 使用的工具与环节

| 环节 | 用途 |
|---|---|
| 接口设计 | 让 AI 检查「哪些操作该用 PATCH 而不是 PUT」 |
| 前端代码 | 生成表格/卡片渲染的样板结构 |
| 排障 | CORS 报错信息 → 定位到 `allowedOrigins` 与 `allowCredentials` 冲突 |

### 7.2 提示词要点

- 带上约束与上下文：`「Spring Boot 4 + 前端同源 fetch + HttpOnly Cookie，CORS 该怎么配才能既不报错又允许凭证？」`
- 要求给多个方案并说取舍：`「给出 3 种解决 EventSource 无法带鉴权头的方案，说明各自代价」`

### 7.3 产出如何验证

| AI 产出 | 验证手段 |
|---|---|
| CORS 配置 | Postman 携带 `Origin: http://localhost:5173` 发请求，检查响应头 |
| SSE 解析代码 | 实机点击「生成路径」，观察页面进度条按事件推进（ANALYZE→PROMPT→…→PERSIST） |
| 前端渲染 | 逐页 F12 检查控制台错误 |

### 7.4 AI 出的问题与我的处理

1. AI 给出的 SSE 解析没有处理「一个网络分片包含多个事件」和「事件被切断跨分片」两种情况 ——
   人工改为按 `\n\n` 循环切分并保留残余 buffer；
2. AI 建议前端把 token 存 `localStorage` —— **未采纳**：改为服务端下发 HttpOnly Cookie，
   前端完全不接触令牌（对应实验四的安全要求）。
