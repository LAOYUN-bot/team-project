# A02 面向未来工作的 AI 职业导航与终身学习伙伴系统

> 赛题来源：《03会议材料-2026赛题手册》A02【蚂蚁百宝箱】
> 本目录是可运行的 **Demo 实现**，严格实现 `docs/04-架构设计.md` 的六步闭环架构。

**一句话**：`MySQL/H2 是唯一业务真相源，AI 只是能力提供方，前端只认后端契约，MCP 是工具侧插件。`

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
> 6 个学习任务、9 天打卡记录、1 次场景训练评分、1 轮 AI 伙伴对话**。
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

## 3. 已实现的功能（对照赛题红线指标）

| 赛题指标 | 实现位置 | 演示入口 |
|---|---|---|
| **T1** 大模型趋势分析 + 角色化对话 | `m2cognition` 趋势分析、`m4generation` AI 伙伴 | 职业知识图谱页 / AI 伙伴对话页 |
| **T2** 3–5 年路径，可细化可调整 | `m3decision/CareerPathService` | 职业路径页（SSE 流式生成 + 阶段增删改） |
| **T3** ≥3 个职场场景模拟训练 | `m5execution/SimulationService` | 职场模拟训练页（远程协作 / AI 辅助办公 / 跨岗位沟通） |
| **T4** 雷达图 / 学习进度 / 匹配度可视化 | `static/js/charts.js`（纯 SVG 自绘） | 成长面板页（雷达 + 进度环 + 成长曲线 + 热力图） |
| **T5** 平台配置与多端发布 | `ai/adapter/TboxAiAdapter` + `docs/06-企业专项/` | 平台与 AI 伦理页（AI 通道状态） |
| **T6** 隐私保护 + AI 伦理 | `platform/privacy` + `ai/privacy` | 平台与 AI 伦理页（数据导出 / 删除 / 脱敏说明） |
| **FR-5.5 MCP** | `mcp/` + `mcp-server/server.py` | 平台与 AI 伦理页（工具调用 + 调用日志） |

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

| 步骤 | 操作 | 讲什么 |
|---|---|---|
| 1 | 登录 `demo` | 感知层已建档，画像 v3 就绪 |
| 2 | 「成长面板」 | **T4 硬指标**：雷达图（含上一版本虚线）+ 进度环 + 成长曲线 + 热力图，一次请求聚合 |
| 3 | 点「手动重算画像」 | **AD-4 版本化**：产生 v4，版本列表新增一行（triggered_by=MANUAL） |
| 4 | 「职业路径」→ 点「演示：制造一次校验失败」 | **D-1 结构化输出契约**：首次输出缺字段 → 带错误原因回灌重试 → 成功，页面显示「重试次数 1」 |
| 5 | 「职场模拟训练」→ 远程协作 → 发 3 轮 → 生成评分 | **全项目最有说服力的一段**：逐维评分 → 回写画像 → 雷达图出现新版本，两个版本可对比 |
| 6 | 「平台与 AI 伦理」 | AI 生成日志（提示词版本 / 重试 / 降级）、MCP 工具调用、隐私措施、数据导出/删除 |

答辩加分点：
- 第 4 步展示的是**健壮性**而不是功能——多数队伍演示的是"顺利时能跑"，这里是"失败时怎么救"。
- 第 5 步回答「画像怎么动态迭代」这个 P1 难题：**有版本号、有触发来源、有可审计快照**。

---

## 5. 自检脚本（零依赖，可写进 CI）

```powershell
# 1) 接口契约自检：53 项，覆盖前端调用的每个接口 + RBAC 401/403 边界
node tools/api-contract.mjs

# 2) 图表库逻辑自检：868 项断言，覆盖坐标合法性、边界数据、XSS 转义
node tools/charts-test.mjs

# 3) 单元测试：46 项（雷达公式 / 结构化输出契约 / 安全与隐私）
mvn -o test-compile
mvn -o dependency:build-classpath "-Dmdep.includeScope=test" "-Dmdep.outputFile=target/cp-test.txt"
java -cp "target/classes;target/test-classes;$(Get-Content target/cp-test.txt -Raw)" cn.zjnu.a02.SelfTestRunner

# 4) MCP 工具契约：13 项（协议方法 / 工具定义 / 异常 / 注入防护）
cmd /c "python mcp-server\server.py < tools\mcp-requests.jsonl > target\mcp-out.jsonl 2>nul"
node tools/mcp-verify.mjs target/mcp-out.jsonl
```

可选（**需要能启动浏览器的机器**）：真实 UI 冒烟测试，用 CDP 驱动本机 Chrome
逐个路由截图并收集控制台错误：

```powershell
node tools/ui-smoke.mjs        # 产物在 target/ui-shots/
```

> 本机沙箱禁止 Chrome 的命名管道 IPC，无头浏览器无法启动，因此该脚本在此环境下不可用；
> 图表逻辑改由 `tools/charts-test.mjs` 以 DOM 桩件覆盖（见 `docs/03-测试文档.md` §四）。

> 离线环境下 `mvn test` 无法启动：本地 Maven 仓库缺少 `surefire-junit-platform` 与
> `junit-platform-launcher`。`SelfTestRunner` 通过反射**直接执行同一批 JUnit 测试方法**，
> 断言仍由 JUnit API 抛出，因此结论等价；联网后直接用 `mvn test` 即可。
>
> **当前实测：46 + 53 + 868 + 13 项全部通过。**

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
| Maven 多模块（10 个） | **单模块 + 严格分包**，包名与模块名一一对应 | 架构文档 §4.1 明确允许的兜底策略 | 低：按包拆分模块即可 |
| MySQL 8 | **H2 内存库**（默认）+ MySQL（可选 profile） | 零配置演示，避免现场因数据库挂掉 | 零：已提供 MySQL profile 与兼容 DDL |
| MyBatis-Plus | **原生 MyBatis**（`mybatis-spring-boot-starter` 4.0.1，接口 + XML 映射）承担**实体 CRUD**；跨表聚合与分析型查询走 `JdbcTemplate` | 本地仓库无 MyBatis-Plus，但有 mybatis-spring-boot-starter；课程实验一要求 Entity/Mapper/Service/Controller 分层，用原生 MyBatis 完全满足 | 低：Mapper 接口不变，升级只需换依赖与 XML 方言 |
| Vue 3 + Vite + ECharts | **原生 ES6 + 手写 SVG 图表库** | npm 不可达，无法安装任何前端依赖 | 中：图表调用接口不变，可按需替换为 ECharts |
| Redis 缓存/限流 | 进程内滑动窗口限流 | 本机无 Redis | 低：`AiRateLimiter` 接口不变，换 Redis 令牌桶即可 |
| Spring Security + jjwt | 自实现 HS256 JWT + `spring-security-crypto` 的 BCrypt | 本地仓库无 security-web/jjwt | 低：令牌格式为标准 JWT |
| MCP Python SDK | **纯标准库实现的 MCP Server** | 无外网无法 pip install | 零：协议实现完整（initialize / tools/list / tools/call） |

**没有降级的部分**（这些才是评分关键）：
六步闭环、雷达版本化与可审计公式、结构化输出契约（抽取→校验→重试→兜底）、
3 个模拟场景与逐维评分、MCP 协议与工具日志、JWT + RBAC、AES-GCM 字段加密、
传模型前脱敏、提示注入防护、AI 生成标识、数据导出与删除。

---

## 8. 课程交付物对照（《团队规范项目实训》）

| 课程要求 | 交付物 | 位置 | 状态 |
|---|---|---|---|
| **项目概要文件**（简介/技术栈/角色/进度表） | 项目概要 | `docs/01-项目概要.md` | ✅ |
| **需求分析与设计文档**（用例/功能清单/ER/架构/接口） | 需求与设计 | `docs/02-需求与设计.md` | ✅ |
| **原型演示视频**（3–5 分钟，含 AI 功能实拍） | 分镜脚本 + 逐镜操作 | `docs/07-演示视频脚本.md` | ✅ 脚本就绪，**待录制** |
| **项目源代码**（README 指引可复现 + AI 使用说明） | 本仓库 | `README.md` `run.ps1` | ✅ |
| **测试文档**（功能测试 + ≥1 项 AI 安全/边界测试） | 测试文档 | `docs/03-测试文档.md`（980 项检查） | ✅ |
| **个人总结**（每人 1 份） | 模板 + 示例 | `docs/个人总结/` | ⏳ 待各成员填写 |
| **5 份实验报告**（含 AI 使用记录） | 5 份带本项目代码的模板 | `docs/实验报告/` | ⏳ 待各成员补截图与反思 |
| 团队规范：角色分工 | 角色表 + 模块归属 | `docs/01-项目概要.md` §三 | ✅ |
| 团队规范：例会 / 周报 / Code Review | 模板 + 示例 | `docs/meetings/` | ⏳ 待替换为真实记录 |
| 团队规范：代码规范 / 分支模型 | 开发规范 | `docs/开发规范.md` | ✅ |
| 团队规范：PR 模板 + CI | CI 4 个 Job | `.github/` | ✅ |
| **AI 使用公约** | 公约（含签署表） | `docs/AI使用公约.md` | ⏳ 待签署 |
| **AI 协作反思**（使用记录 + 边界意识） | 使用记录 | `docs/AI使用记录.md` | ✅ |
| 开源协议合规 | 组件清单 | `docs/开源组件清单.md` | ✅ |
| 课程五个实验的技术支撑 | 映射表 | `docs/实验报告/README.md` §二 | ✅ |

### 五个课程实验与本项目的对应

| 实验 | 课程要点 | 本项目实现 | 对应报告 |
|---|---|---|---|
| 一 | Spring Boot + 数据源 + **用户表 CRUD** + 分层 | `m1perception`（User/UserProfile 实体 + MyBatis Mapper + XML）、`m5execution`（LearningTask） | `实验一` |
| 二 | 前后端分离 + RESTful + Axios + 跨域 | 81 个 REST 端点 + `Api` 客户端封装 + `WebConfig` CORS + SSE 流式 | `实验二` |
| 三 | Git 分支 + PR + Code Review + CI | `开发规范.md` + PR 模板 + `.github/workflows/ci.yml`（4 Job） | `实验三` |
| 四 | 登录 / RBAC / JWT / BCrypt + **AI 安全** | `platform/security` + `platform/privacy` + `ai/privacy` + 提示注入三层闸门 | `实验四` |
| 五 | LLM API + 提示工程 + RAG + 原型 | `ai/`（AiChatPort + 适配器 + 结构化输出契约）+ 6 类 AI 场景 + 知识库 | `实验五` |

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
│  ├─ ai/           adapter(Mock/Tbox) schema(结构化契约) prompt(提示词) privacy(脱敏)
│  ├─ platform/     security(JWT/RBAC) web(异常/traceId) privacy(加密/删除) audit ratelimit
│  ├─ m1perception/ service + entity + mapper + controller   ← 感知层（MyBatis 分层示例）
│  ├─ m2cognition/  m3decision/  m4generation/               ← 认知 / 决策 / 生成
│  ├─ m5execution/  service + entity + mapper + controller   ← 执行层（MyBatis 分层示例）
│  ├─ m6feedback/   mcp/                                     ← 反馈层 / MCP 工具
│  └─ app/seed/     演示数据播种（走真实业务链路）
├─ src/main/resources/
│  ├─ application.yml
│  ├─ db/schema.sql(45 表)  db/data.sql(知识图谱种子)
│  ├─ mapper/*.xml              ← MyBatis SQL 映射（UserMapper / UserProfileMapper / LearningTaskMapper）
│  ├─ prompts/*.txt             ← 8 个版本化提示词（AI 的行为代码）
│  └─ static/                   ← 前端（无需构建）
│     ├─ index.html  css/app.css
│     └─ js/{api.js, charts.js, app.js}
├─ src/test/java/               ← 46 个单元测试 + 离线执行器 SelfTestRunner
├─ mcp-server/                  ← 自建 MCP Server（Python，stdio + HTTP）
├─ tools/                       ← 零依赖自检脚本（接口 53 项 / 图表 868 断言 / MCP 13 项）
└─ docs/                        ← 全部课程交付文档
   ├─ 00-运行与演示指南.md      01-项目概要.md        02-需求与设计.md
   ├─ 02a-需求分析与实施规划(上游基线).md
   ├─ 03-测试文档.md            04-架构设计.md        05-测试与迭代记录.md
   ├─ 07-演示视频脚本.md        AI使用公约.md         AI使用记录.md
   ├─ 开发规范.md               开源组件清单.md
   ├─ 06-企业专项/              竞赛加分材料（非课程必需）
   ├─ meetings/                 周报 + 例会记录（过程表现 20%）
   ├─ 实验报告/                 5 份实验报告（每人 5 份）
   └─ 个人总结/                 每人 1 份
```

---

## 10. 已知限制

1. **百宝箱企业版未接入**：`TboxAiAdapter` 已实现完整的调用、超时、异常与降级逻辑，
   但本机无网络且无企业版 Token，运行时 `ai.provider=mock`。
   配置 `TBOX_API_KEY` / `TBOX_AGENT_ID` 后即可切换，无需改业务代码（AD-2）。
2. **多端发布（T5 后半）未验证**：需要百宝箱渠道发布权限，见 `docs/06-企业专项/`。
3. **前端为手写 SVG 图表**，非 ECharts；视觉与交互能力已覆盖 T4 要求的四类图表。
4. **限流与缓存为进程内实现**，多实例部署需替换为 Redis。
