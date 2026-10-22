# A02 AI 职业导航与终身学习伙伴系统

> 上游依据：`docs/02a-需求分析与实施规划（上游基线）.md` **v2.0**
> （已移除全部竞赛平台强制要求，项目为纯自包含课程工程）
> 本目录是可运行的 **Demo 实现**，架构见 `docs/04-架构与伦理设计.md`。

**一句话**：`MySQL/H2 是唯一业务真相源，AI 只经统一网关提供能力，前端只认后端契约；
RAG 自己建、成本自己管、限流自己扛。`

---

## 1. 30 秒跑起来

```powershell
cd team-project
.\run.ps1                     # 默认 http://localhost:8080
```

浏览器打开 <http://localhost:8080>，用下面任一账号登录：

| 账号 | 密码 | 用途 |
|---|---|---|
| `demo` | `demo1234` | 普通用户（已预置完整闭环数据） |
| `admin` | `admin1234` | 管理员（用于演示 RBAC 403） |

> 首次启动会自动播种演示数据（约 8 秒）：**3 个画像版本、5 阶段职业路径、30 个学习单元、
> 6 个学习任务、9 天打卡记录、1 次场景训练评分、1 轮 职业伙伴**。
> 换句话说，登录进去就能看到一条跑通的闭环，不用现场点半小时按钮。

常用参数：

```powershell
.\run.ps1 -Port 8081          # 换端口
.\run.ps1 -SkipBuild          # 跳过编译
.\run.ps1 -Profile mysql      # 使用本机 MySQL（需先建库，见 §6）
```

### 用 IntelliJ IDEA 打开？

见 **`docs/08-IDEA打开与测试指南.md`** —— 逐步操作说明（含本机 JDK 17、离线 Maven 仓库配置要点、
运行配置、跑测试、用 IDEA HTTP Client 调接口、H2 数据库查看、常见报错排查）。

IDE 内调接口可直接用 **`http/A02-接口测试.http`**（约 46 个请求，含权限与错误分支断言），
不用装 Postman。

---

## 2. 环境要求

| 项 | 要求 | 说明 |
|---|---|---|
| JDK | **17** | 脚本会自动查找 `A02_JAVA_HOME` / `JAVA_HOME` / `D:\Java17` |
| Maven | 3.9+ | 需能访问本地仓库（本机已配置离线仓库） |
| 数据库 | **无需安装** | 默认使用 H2 内存库，零配置启动 |
| Node.js | 18+ | 仅用于运行 `tools/` 下的自检脚本，**前端无需构建** |
| Python | 3.7+ | 仅 MCP Server 需要（纯标准库，无需 pip 安装） |

---

## 3. 已实现的功能（对照 v2.0 需求）

| v2.0 需求 | 实现位置 | 界面入口（`/` 左侧导航） |
|---|---|---|
| **FR-2.2 / FR-4.4** 大模型趋势分析 + 角色化对话 | `m2cognition` 趋势分析、`m4generation` AI 伙伴 | 职业路径 · 趋势分析 / **职业伙伴** |
| **FR-3.2 / FR-3.3** 3–5 年路径，可细化可调整 | `m3decision/CareerPathService` | **职业路径**（SSE 流式生成 + 阶段增删改） |
| **FR-5.2 / FR-5.3** ≥3 个职场场景模拟训练与评分 | `m5execution/SimulationService` | **情景练习**（远程协作 / AI 辅助办公 / 跨岗位沟通） |
| **FR-3.1 / FR-6.1** 雷达图 / 学习进度 / 匹配度可视化 | `static/js/charts.js`（纯 SVG 自绘） | **今日**（进度环 + 匹配度）/ **我的能力**（雷达 + 成长曲线 + 热力图） |
| **FR-7.1 统一模型网关**（v2.0 核心新增） | `ai/gateway`（LlmGateway + ModelRouter + OpenAI 兼容适配器） | **设置** · 模型与成本 |
| **FR-7.2 自建 RAG**（v2.0 升级为 P0） | `ai/rag`（切块 / TF-IDF 向量 / 检索 / 双判据拒答 / 评测） | **资料问答** |
| **NFR-8 成本与限流**（v2.0 重点） | `LlmRateLimiter` + `LlmCallLogService` + 二级缓存 | **设置** · 用量与额度 |
| **FR-7.4 / FR-7.5** 隐私保护 + AI 伦理 | `platform/privacy` + `ai/privacy` | **设置** · 隐私与数据（导出 / 删除 / 脱敏说明） |
| **（P2 可选）MCP 扩展** | `mcp-server/server.py` + `mcp/` | **设置** · 高级（工具服务） |

**界面信息架构**（8 个页面，导航即使用路径，无任何开发说明性文字）：

| 页面 | 使用者在这页做什么 |
|---|---|
| 今日 | 看到今天要做的 1–3 件事、连续学习天数、目标岗位匹配度 |
| 我的能力 | 8 维能力雷达（含历史版本对比）、成长曲线、学习热力图 |
| 职业路径 | 生成 3–5 年路线，按阶段增删改目标与技能 |
| 学习计划 | 平台拆好的每周学习单元与任务清单，勾选完成 / 打卡 |
| 情景练习 | 进入一个职场场景对话，结束后拿到分项反馈 |
| 职业伙伴 | 与自己岗位人设的 AI 对话，带上下文记忆 |
| 资料问答 | 基于内部资料的问答，回答附来源引用，超出范围会明确说明 |
| 设置 | 目标岗位、模型与用量、隐私与数据（导出 / 删除账号数据） |

> v2.0 移除了全部竞赛平台强制要求（百宝箱、多端小程序、企业专项材料），
> 工程重心转为**「AI 能力自建 + 业务闭环完整」**：模型网关、RAG 管线、成本与限流三件事全部自己实现。

**六步闭环落地**（包名即闭环，答辩时打开 IDE 就是物料）：

| 闭环 | 模块 | 关键类 |
|---|---|---|
| ① 感知 | `m1perception` | `PerceptionService`（建档 / 测评 → 8 维能力向量） |
| ② 认知 | `m2cognition` | `KnowledgeGraphService`（154 节点 / 143 关系）、`CognitionService`（差距 / 趋势） |
| ③ 决策 | `m3decision` | `RadarCalculator`（版本化雷达）、`CareerPathService`、`MatchService` |
| ④ 生成 | `m4generation` | `GenerationService`（周单元拆解）、`AgentChatService`（角色对话） |
| ⑤ 执行 | `m5execution` | `TaskService`（任务 / 打卡）、`SimulationService`（场景 + 评分） |
| ⑥ 反馈 | `m6feedback` | `GrowthService`（面板聚合 / 快照 / 偏离检测）、`CompanionService`（陪伴激励） |

**闭环接线**（`common/event/Events` + 各模块监听器，保证依赖方向单向）：

```
测评完成 ──► 生成雷达 v1 ──► 差距分析
任务状态变化 ──► 重算画像（TASK，1 小时节流）
训练评分完成 ──► 重算画像（SIMULATION）──► 成长快照 + 偏离检测
路径生成 ──► 自动拆解 30 个学习单元
```

---

## 4. 演示脚本（5 分钟讲完六步闭环）

| 步骤 | 在哪个页面点什么 | 讲什么 |
|---|---|---|
| 1 | 登录 `demo / demo1234` | 账号已建档：自评、画像 v3、目标岗位都在库里 |
| 2 | **今日**：点「完成今日打卡」 | 连续天数 +1，进度环变化；一次请求聚合了进度、雷达、激励、里程碑 |
| 3 | **我的能力**：点右上「重新评估」→ 再看「能力变化」 | **雷达版本化**：产生新版本，可在下拉框任选两次评估对比逐维增减 |
| 4 | **职业路径**：点「开始制定」（SSE 流式）→ 展开阶段 | 3–5 年路线按阶段拆解，含可验证产出物与工时约束 |
| 5 | **学习计划**：点「生成任务」→ 勾选完成 | 每周单元 → 可执行任务，完成率回写画像 |
| 6 | **情景练习**：选场景 → 开始练习 → 发 3 轮 → 「生成反馈」 | 逐维评分 → 回写画像 → 能力页出现新版本，可与上一版对比 |
| 7 | **资料问答**：问「Java 后端需要哪些数据库技能」→ 再问「2027 浙江高考分数线」→ 点「检查问答质量」 | **v2.0 重点**：带来源引用回答；跑题问题**拒答**（防幻觉）；52 条评测的量化指标当场可见 |
| 8 | **设置**：看「使用情况」→ 试「导出我的数据」 | **NFR-8**：近 7 天调用量、今日剩余额度、平均响应；数据可携带、可删除 |

答辩加分点（这些在界面上看不到，用接口与文档证明，不要假装有按钮）：
- 第 3、6 步回答「画像怎么动态迭代」：**有版本号、有触发来源、有可审计快照**；
- 第 7 步回答「怎么保证 AI 不乱说」：**双判据拒答 + 来源引用 + 52 条评测指标**；
- 模型网关与多供应商切换：`GET /api/platform/ai-status`，演示 `AI_PROVIDER=openai` 后同一套业务代码不改；
- 成本、限流与降级：`GET /api/platform/llm-stats`，令牌桶 + 二级缓存 + `degraded` 标记；
- MCP 工具服务（P2 可选）：`node tools/mcp-verify.mjs`，13 项工具契约；
- 接口越权：`http/A02-接口测试.http` 里 401/403 断言，前端不接触令牌。

---

## 5. 自检脚本（零依赖，可写进 CI）

```powershell
# 1) 接口契约自检：60 项，覆盖前端调用的每个接口 + RBAC 401/403 边界
node tools/api-contract.mjs

# 2) 图表库逻辑自检：862 项断言，覆盖坐标合法性、边界数据、XSS 转义
node tools/charts-test.mjs

# 3) 前端静态自检：6 项，校验页面调用的 81 个接口都有后端映射、
#    105 个 CSS 类都有定义、静态资源齐全、文案无开发用语、路由与视图连通
node tools/ui-static-check.mjs

# 4) 单元测试：46 项（雷达公式 / 结构化输出契约 / 安全与隐私）
mvn -o test-compile
mvn -o dependency:build-classpath "-Dmdep.includeScope=test" "-Dmdep.outputFile=target/cp-test.txt"
java -cp "target/classes;target/test-classes;$(Get-Content target/cp-test.txt -Raw)" cn.zjnu.a02.SelfTestRunner

# 5) RAG 效果评估：Recall@K / 关键词命中率 / 拒答行为 / 成本统计
node tools/rag-eval.mjs

# 6) MCP 工具契约：13 项（P2 可选扩展）
cmd /c "python mcp-server\server.py < tools\mcp-requests.jsonl > target\mcp-out.jsonl 2>nul"
node tools/mcp-verify.mjs target/mcp-out.jsonl
```

实测结果：**46 + 60 + 862 + 6 + 13 = 987 项检查全部通过**，RAG Recall@5 = 100%。

可选（**需要能启动无头浏览器的机器**）：真实渲染自检，用 CDP 驱动本机 Edge，
自动登录 → 逐页导航 → 截图 → 收集 JS 异常与 4xx/5xx：

```powershell
# 先起无头浏览器（另开窗口，不要关）
Start-Process "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" -ArgumentList @(
  '--headless=new','--disable-gpu','--remote-debugging-port=9223',
  "--user-data-dir=$env:TEMP\a02-edge",'--window-size=1440,1000','about:blank')

node tools/ui-render-check.mjs 9223 http://127.0.0.1:8080   # 产物在 target/ui-shots/
```

> **状态：该脚本在本环境未跑通，请不要把它当成已验证的测试。**
> 浏览器进程能起来、CDP 的 `/json/version` 能连上、`Target.createTarget` 也有回应，
> 但页面级命令（`Page.enable` / `Runtime.evaluate`）在沙箱下收不到返回，因此完整流程从未走完。
> 结论：图表逻辑由 `tools/charts-test.mjs` 以 DOM 桩件覆盖，页面结构、接口映射与文案由
> `tools/ui-static-check.mjs` 静态覆盖（见 `docs/03-测试文档.md` §四）。
> 界面是否好看、是否有排版问题，**只能靠人打开 `http://127.0.0.1:8080/`（`demo / demo1234`）自己看**。

> 离线环境下 `mvn test` 无法启动：本地 Maven 仓库缺少 `surefire-junit-platform` 与
> `junit-platform-launcher`。`SelfTestRunner` 通过反射**直接执行同一批 JUnit 测试方法**，
> 断言仍由 JUnit API 抛出，因此结论等价；联网后直接用 `mvn test` 即可。

---

## 6. 可选：切换到 MySQL

```powershell
# 1) 建库（本机已装 MySQL 8）
mysql -uroot -p -e "CREATE DATABASE a02 DEFAULT CHARSET utf8mb4;"

# 2) 带 profile 启动
$env:MYSQL_USER="root"; $env:MYSQL_PASSWORD="你的密码"
.\run.ps1 -Profile mysql
```

表结构在 `src/main/resources/db/schema.sql`（35 张表），种子数据在 `db/data.sql`。

---

## 7. 技术选型偏差说明（**必读，答辩要能解释**）

本机是**完全离线**环境：npm 与 Maven Central 均不可达，本地仓库只含一个受限的依赖子集。
为了让 Demo **真的能跑起来**（而不是只交一堆跑不动的代码），做了如下受控替换，
每一条都在架构文档允许的范围内，并保留了可迁移路径：

| 文档原计划 | Demo 实际 | 原因 | 迁移成本 |
|---|---|---|---|
| Spring Boot 3.2 | **Spring Boot 4.0.8**（Java 17） | 本地仓库仅有 4.0.x / 2.7.18 | 几乎为零：同为 Jakarta 命名空间，写法一致 |
| Maven 多模块（10 个） | **单模块 + 严格分包**，包名与模块名一一对应 | 架构文档允许的兜底策略 | 低：按包拆分模块即可 |
| MySQL 8 | **H2 内存库**（默认）+ MySQL（可选 profile） | 零配置演示，避免现场因数据库挂掉 | 零：已提供 MySQL profile 与兼容 DDL |
| MyBatis-Plus | **原生 MyBatis**（接口 + XML）承担实体 CRUD；跨表聚合走 `JdbcTemplate` | 本地仓库无 MyBatis-Plus；课程实验一要求四层分层，原生 MyBatis 完全满足 | 低：Mapper 接口不变 |
| Vue 3 + Vite + ECharts | **原生 ES6 + 手写 SVG 图表库** | npm 不可达，无法安装任何前端依赖 | 中：图表调用接口不变，可替换为 ECharts |
| Redis 限流/缓存 | 进程内**令牌桶 + 二级缓存** | 本机无 Redis | 低：`LlmRateLimiter` 接口不变，换 Redis 令牌桶即可 |
| Spring Security + jjwt | 自实现 HS256 JWT + BCrypt | 本地仓库无 security-web/jjwt | 低：令牌格式为标准 JWT |
| Milvus / Redis Stack 向量库 | **`JdbcVectorStore`**（向量存 `kb_chunk.embedding_json`，内存余弦） | 无 Docker；知识库仅数十片段 | 零：业务只依赖 `VectorStore` 接口，加实现类即可 |
| 云端 embedding（bge-m3） | **本地确定性 hashing 向量**（离线可用） | 无外网、无 GPU | 低：`a02.ai.embedding.provider=openai` 即切云端 |
| DeepSeek / Ollama | **统一网关已实现**，默认 `provider=mock` 离线跑 | 无 API Key | 零：配 `DEEPSEEK_API_KEY` 并置 `provider=openai` 即切换 |
| MCP Python SDK | 纯标准库实现的 MCP Server（**v2.0 已降为 P2 可选**） | 无外网无法 pip install | 零：协议实现完整 |

**没有降级的部分**（这些才是评分关键）：
六步闭环、雷达版本化与可审计公式、结构化输出契约（抽取→校验→重试→兜底）、
3 个模拟场景与逐维评分、**自建 RAG（切块/向量/检索/双判据拒答/52 条评测）**、
**统一模型网关与多模型分层**、**NFR-8 限流与成本记账**、JWT + RBAC、AES-GCM 字段加密、
传模型前脱敏、提示注入防护、AI 生成标识、数据导出与删除。

---

## 8. 课程交付物对照（《团队规范项目实训》）

| 课程要求 | 交付物 | 位置 | 状态 |
|---|---|---|---|
| **项目概要文件**（简介/技术栈/角色/进度表） | 项目概要 | `docs/01-项目概要.md` | ✅ |
| **需求分析与设计文档**（用例/功能清单/ER/架构/接口） | 需求与设计 | `docs/02-需求与设计.md` | ✅ |
| **技术架构与 AI 伦理设计说明**（v2.0 §5.6） | 架构与伦理 | `docs/04-架构与伦理设计.md` | ✅ |
| **原型演示视频**（3–5 分钟，含 AI 功能实拍） | 分镜脚本 + 逐镜操作 | `docs/07-演示视频脚本.md` | ✅ 脚本就绪，**待录制** |
| **项目源代码**（README 指引可复现 + AI 使用说明） | 本仓库 | `README.md` `run.ps1` | ✅ |
| **测试文档**（功能测试 + ≥1 项 AI 安全/边界测试） | 测试文档 | `docs/03-测试文档.md`（994 项检查 + RAG 评测） | ✅ |
| **用户测试反馈与迭代记录** | 测试与迭代 | `docs/05-测试与迭代记录.md` | ⚠ 用户测试待实机执行 |
| **个人总结**（每人 1 份） | 模板 + 示例 | `docs/个人总结/` | ⏳ 待各成员填写 |
| **5 份实验报告**（含 AI 使用记录） | 5 份带本项目代码的模板 | `docs/实验报告/` | ⏳ 待各成员补截图与反思 |
| 团队规范：角色分工 | 角色表 + 模块归属 | `docs/01-项目概要.md` §三 | ✅ |
| 团队规范：例会 / 周报 / Code Review | 模板 + 示例 | `docs/meetings/` | ⏳ 待替换为真实记录 |
| 团队规范：代码规范 / 分支模型 | 开发规范 | `docs/开发规范.md` | ✅ |
| 团队规范：PR 模板 + CI | CI 4 个 Job | `.github/` | ✅ |
| **AI 使用公约** | 公约（含签署表） | `docs/AI使用公约.md` | ⏳ 待签署 |
| **AI 协作反思**（使用记录 + 边界意识） | 使用记录 | `docs/AI使用记录.md` | ✅ |
| 开源协议合规 | 组件清单 | `docs/开源组件清单.md` | ✅ |
| （P2 可选）MCP 扩展说明 | P2 扩展 | `docs/09-MCP扩展（P2可选）.md` | ✅ 可选，不计入主线 |

> **v2.0 变化**：原「企业专项材料 ①–④」「多端发布（小程序）」已随竞赛平台要求一并移除，
> MCP 降级为 P2 可选扩展；新增「技术架构与 AI 伦理设计」「RAG 效果评估」两项。

### 五个课程实验与本项目的对应

| 实验 | 课程要点 | 本项目实现 | 对应报告 |
|---|---|---|---|
| 一 | Spring Boot + 数据源 + **用户表 CRUD** + 分层 | `m1perception`（User/UserProfile 实体 + MyBatis Mapper + XML）、`m5execution`（LearningTask） | `实验一` |
| 二 | 前后端分离 + RESTful + Axios + 跨域 | 81 个 REST 端点 + `Api` 客户端封装 + `WebConfig` CORS + SSE 流式 | `实验二` |
| 三 | Git 分支 + PR + Code Review + CI | `开发规范.md` + PR 模板 + `.github/workflows/ci.yml`（4 Job） | `实验三` |
| 四 | 登录 / RBAC / JWT / BCrypt + **AI 安全** | `platform/security` + `platform/privacy` + `ai/privacy` + 提示注入三层闸门 | `实验四` |
| 五 | LLM API + 提示工程 + RAG + 原型 | `ai/gateway`（统一模型网关）+ `ai/rag`（自建 RAG 管线与评测）+ 6 类 AI 场景 | `实验五` |

> 实验二与实验五的说明：
> - 实验二要求「Vue + Axios」，当前用**原生 ES6 + fetch 封装**（离线无 npm 源），
>   职责划分与 Axios 拦截器一致，接口契约不变，联网后可平滑替换；
> - 实验五**不另做一套 Demo**，直接以本项目的 AI 能力层交付（一份代码两处交付）。

---

## 9. 目录结构

```
team-project/
├─ README.md                    ← 本文件
├─ run.ps1                      ← 一键启动
├─ pom.xml  .gitignore
├─ .github/
│  ├─ workflows/ci.yml          ← CI：后端 / 接口契约 / 前端 / MCP 四个 Job
│  └─ PULL_REQUEST_TEMPLATE.md  ← PR 模板（含关联需求 + AI 使用说明）
├─ src/main/java/cn/zjnu/a02/
│  ├─ A02Application.java       ← 启动类（@MapperScan 扫描 Mapper）
│  ├─ common/       api(统一响应) db(聚合查询 DAO) event(领域事件) port(端口) util
│  ├─ ai/           gateway(统一网关) adapter(Mock/OpenAI) rag(自建RAG) cost schema prompt privacy
│  ├─ platform/     security(JWT/RBAC) web(异常/traceId) privacy(加密/删除) audit ratelimit
│  ├─ m1perception/ service + entity + mapper + controller   ← 感知层（MyBatis 分层示例）
│  ├─ m2cognition/  m3decision/  m4generation/               ← 认知 / 决策 / 生成
│  ├─ m5execution/  service + entity + mapper + controller   ← 执行层（MyBatis 分层示例）
│  ├─ m6feedback/   mcp/                                     ← 反馈层 / MCP 工具
│  └─ app/seed/     演示数据播种（走真实业务链路）
├─ src/main/resources/
│  ├─ application.yml
│  ├─ db/schema.sql(49 表)  db/data.sql(知识图谱种子)
│  ├─ mapper/*.xml              ← MyBatis SQL 映射（UserMapper / UserProfileMapper / LearningTaskMapper）
│  ├─ prompts/*.txt             ← 9 个版本化提示词（含 rag.txt，AI 的行为代码）
│  ├─ kb/*.md                   ← RAG 语料（6 份，公开资料改写）
│  └─ static/                   ← 前端（无需构建，唯一入口 index.html）
│     ├─ index.html  css/app.css
│     └─ js/{api.js, charts.js, app.js}
├─ src/test/java/               ← 46 个单元测试 + 离线执行器 SelfTestRunner
├─ mcp-server/                  ← 自建 MCP Server（P2 可选，Python stdio + HTTP）
├─ tools/                       ← 零依赖自检脚本（接口 60 项 / 图表 862 断言 / 前端静态 6 项 / MCP 13 项 / RAG 评测）
│  └─ ui-render-check.mjs       ← 需真实浏览器，CDP 逐页截图 + 抓 JS 异常（本机沙箱不可用）
└─ docs/                        ← 全部课程交付文档
   ├─ 00-运行与演示指南.md      01-项目概要.md        02-需求与设计.md
   ├─ 02a-需求分析与实施规划(v2.0 上游基线).md
   ├─ 03-测试文档.md            04-架构与伦理设计.md  05-测试与迭代记录.md
   ├─ 07-演示视频脚本.md        08-IDEA打开与测试指南.md
   ├─ 09-MCP扩展（P2可选）.md
   ├─ AI使用公约.md             AI使用记录.md        开发规范.md  开源组件清单.md
   ├─ meetings/                 周报 + 例会记录（过程表现 20%）
   ├─ 实验报告/                 5 份实验报告（每人 5 份）
   └─ 个人总结/                 每人 1 份
```

---

## 10. 已知限制

1. **未接入真实大模型**：`OpenAiCompatibleGateway` 已实现完整的调用、超时、429 限流识别、
   异常分类与降级逻辑，但开发机无网络与 API Key，默认 `provider=mock`。
   配 `DEEPSEEK_API_KEY` 并把 `a02.ai.provider` 改为 `openai` 即可切换（改配置不改代码）。
2. **向量化默认是本地 hashing 向量**（离线可用，捕捉字面相似度）：可切云端 embedding；
   RAG 评测已给出 Recall@5=100%、Recall@1=63.5%、MRR=0.783 的量化结果。
3. **前端为手写 SVG 图表**，非 ECharts；已覆盖雷达/进度环/成长曲线/热力图四类图表。
4. **限流与缓存为进程内实现**，多实例部署需替换为 Redis 令牌桶。
5. **未做正式压测**：`llm_call_log` 已记录每次调用耗时，可作压测基线；文档不宣称达标。
