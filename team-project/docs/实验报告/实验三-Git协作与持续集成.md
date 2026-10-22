# 实验三：Git 协作开发与持续集成

- 姓名：＿＿＿＿＿　学号：＿＿＿＿＿　日期：＿＿＿＿＿

---

## 一、实验目标（课程原文）

> 分支管理、Pull Request、Code Review、基础 CI。

课程步骤要点：`git init` / 分支 `feature/*` / `git merge` / 解决冲突 / 推送远程；
引入 PR 模板与基础 CI（如 GitHub Actions 跑构建与测试）。

---

## 二、实验环境

Git 2.50 / GitHub（或 Gitee）/ GitHub Actions / 本项目的 `team-project` 目录。

---

## 三、实验步骤

### 3.1 初始化仓库与分支模型

```bash
cd team-project
git init -b main
git add .
git commit -m "chore: 初始化 A02 职业导航与终身学习伙伴系统

- Spring Boot 4.0.8 + MyBatis + H2 骨架
- 六步闭环六模块包结构（m1perception ... m6feedback）
- 45 张表 DDL 与职业知识图谱种子数据
- 零依赖自检脚本与 CI"
```

分支模型（详见 `docs/开发规范.md` §一）：

```
main       ← 可交付（打 tag）
 └ develop ← 集成
    ├ feature/m3-radar-versioning
    ├ feature/web-svg-charts
    └ fix/sse-auth-context-leak
```

```bash
git checkout -b develop
git checkout -b feature/m3-radar-versioning
# …开发…
git add . && git commit -m "feat(m3): 雷达版本化与衰减因子"
git checkout develop && git merge --no-ff feature/m3-radar-versioning
```

**为什么用 `--no-ff`**：保留分支存在的痕迹，答辩时 `git log --graph` 能直观展示协作过程。

📷 **截图 1**：`git log --graph --oneline --all` 的分支图
📷 **截图 2**：`git branch -a` 的分支列表

### 3.2 提交信息规范（Conventional Commits）

```
<type>(<scope>): <描述>
```

| type | 示例 |
|---|---|
| feat | `feat(m5): 3 个职场场景模拟与逐维评分` |
| fix | `fix(plat): 修复异步请求导致的鉴权上下文泄漏` |
| test | `test(m3): 补雷达缺失来源重归一化用例` |
| docs | `docs: 补充 MCP 工具契约说明` |

`scope` 用模块缩写（`m1`–`m6` / `ai` / `web` / `plat` / `mcp`），
便于按模块统计个人贡献：

```bash
git log --oneline --grep="m3" --author="<姓名>"
```

📷 **截图 3**：一段 `git log --oneline` 输出（体现规范与 scope）

### 3.3 冲突的产生与解决

构造一次真实冲突（两人改同一文件同一区域）：

```bash
git checkout develop
git checkout -b feature/a
# 修改 application.yml 的 a02.ai.provider 行
git commit -am "feat(ai): 调整默认提供方"
git checkout develop
git checkout -b feature/b
# 修改 application.yml 的同一行
git commit -am "feat(ai): 调整默认提供方为 mock"

git checkout feature/a
git merge feature/b
# → CONFLICT (content): Merge conflict in src/main/resources/application.yml
```

冲突标记：

```yaml
<<<<<<< HEAD
    provider: openai
=======
    provider: mock
>>>>>>> feature/b
```

**解决步骤**（关键是要问清楚「哪个值是团队共识」，而不是随手选一边）：

```bash
# 1) 与对方确认取值：离线开发阶段统一用 mock
# 2) 手工编辑去掉标记，保留 provider: mock
git add src/main/resources/application.yml
git commit -m "merge(ai): 解决默认提供方冲突，统一为 mock"
```

📷 **截图 4**：冲突标记（IDE 或编辑器中的 <<<<<<< 界面）
📷 **截图 5**：解决后的提交与 `git log --graph`

**减少冲突的团队约定**：
- 每天开工前 `git pull --rebase origin develop`；
- 配置文件按人分段维护，避免多人改同一行；
- 一人一模块（`feature/m3-*` 只动 `m3decision` 包），把冲突面收敛到最小。

### 3.4 PR 模板

`.github/PULL_REQUEST_TEMPLATE.md` 要求填写：
变更说明 / **关联需求编号** / 变更类型 / **自测方式** / **AI 使用说明** / 自检清单 / 风险与回滚。

其中「自测方式」是 Reviewer 能照着复现的步骤，例如：

```
1. .\run.ps1 启动
2. 登录 demo/demo1234
3. 进入「情景练习」→ 远程协作 → 发言 3 轮 → 点「生成评分并回写画像」
4. 预期：出现评分卡片，且「我的能力」画像版本 +1
```

📷 **截图 6**：PR 页面（显示模板字段已填写）

### 3.5 Code Review

评审检查清单（`docs/开发规范.md` §4.1）：

| 检查项 | 说明 |
|---|---|
| 需求对齐 | PR 是否关联需求编号（FR-x.x） |
| 分层合规 | 有没有把业务逻辑写进 Controller、跨模块直接访问对方 Mapper |
| 安全 | 有无明文密钥、越权风险、`${}` 拼接 SQL、敏感字段出库 |
| 测试 | 核心算法改动是否补了单测；CI 是否通过 |

**本项目的真实评审案例**（见 `docs/meetings/Sprint1-例会记录.md`）：

| PR | 结论 | 主要意见 |
|---|---|---|
| #8 | **打回重做** | 趋势分析初版没有返回 `sources`，违反「无来源不外答」要求；补上并加断言后通过 |
| #7 | 已合并 | 差距分析 SQL 建议加 `user_id` 索引 |
| #9 | 已合并 | 表单校验提示建议明确到字段级 |

📷 **截图 7**：一次 Review 的评论（最好包含一条「要求修改」的意见）

### 3.6 持续集成（GitHub Actions）

`.github/workflows/ci.yml` 共 4 个 Job：

| Job | 内容 |
|---|---|
| `backend` | JDK 17 + `mvn compile` + `mvn test`，上传测试报告 |
| `integration` | 后台启动服务 → 等 `/api/health` 就绪 → 跑 **53 项接口契约自检** → 失败时归档日志 |
| `frontend` | `node --check` 语法校验 + **868 项图表断言** |
| `mcp` | 启动后端 → 回放 MCP 请求 → **13 项工具契约校验** |

```yaml
- name: 接口契约自检（53 项）
  run: node tools/api-contract.mjs http://127.0.0.1:8080
```

**分支保护**：`main` 与 `develop` 需 **CI 全绿 + ≥1 人 approve** 才能合并。

📷 **截图 8**：GitHub Actions 页面（4 个 Job 全绿）
📷 **截图 9**：分支保护规则设置页

---

## 四、遇到的问题与解决

| # | 问题 | 解决 |
|---|------|------|
| 1 | CI 里启动服务后立刻跑接口测试，偶发失败 | 加「轮询 `/api/health` 直到就绪，最多 60 次 × 2s」的就绪等待 |
| 2 | 离线环境下 `mvn test` 无法运行（缺 surefire provider） | CI 在联网环境正常跑 `mvn test`；本地提供 `SelfTestRunner` 离线执行同一批测试 |
| 3 | 构建产物被误提交 | 补 `.gitignore`（`target/`、`.env`、IDE 配置、密钥文件） |
| 4 | 合并时把别人的未完成改动一起带进来 | 约定分支只从 `develop` 拉出，合并前先 `rebase develop` |

---

## 五、验证与结果

```bash
git log --graph --oneline --all        # 分支与合并痕迹
git log --oneline --grep="m3"          # 按模块统计贡献
git shortlog -sn                       # 按人统计提交数
```

| 验证项 | 结果 |
|---|---|
| 分支模型 | `main` / `develop` / `feature/*` / `fix/*` 均已建立并使用 |
| PR 模板 | 已配置，字段含关联需求与 AI 使用说明 |
| CI | 4 个 Job 覆盖后端 / 接口 / 前端 / MCP |
| 冲突解决 | 完成一次真实冲突的产生、定位、沟通、解决全流程 |
| 评审 | 有「打回重做」与「已合并」两类真实结论 |

---

## 六、总结与反思

> （请填写自己的内容）

**要点参考**
- 分支模型的意义不是「好看」，而是**让冲突面可预测**：一人一模块，冲突只可能出现在
  配置文件与公共类里，评审也只需要看一个包；
- `--no-ff` 合并虽然图好看，但代价是历史里有大量 merge commit，要配合 `--graph` 才读得懂；
- CI 最大的价值是**把「我以为没问题」变成「机器确认没问题」**：
  本项目的 4 个缺陷里有 1 个安全漏洞是靠 CI 里那条「未登录必须 401」的断言发现的。

---

## 七、AI 工具使用记录

### 7.1 使用的工具与环节

| 环节 | 用途 |
|---|---|
| 工作流编写 | 让 AI 起草 GitHub Actions YAML（含 job 依赖与 artifact 上传） |
| 冲突排查 | 把 `git status` 与冲突文件片段给 AI，确认解决步骤 |
| PR 描述 | 让 AI 把 diff 归纳成结构化的变更说明（人工核对是否准确） |

### 7.2 提示词要点

- `「GitHub Actions：先编译并后台启动 Spring Boot，等 /api/health 就绪后跑 node 脚本，失败时上传日志。给出完整 yaml」`
- **要求验证而非相信**：`「这段 yaml 里有哪些地方在 ubuntu-latest 上可能失败？逐条说明」`

### 7.3 产出如何验证

| AI 产出 | 验证手段 |
|---|---|
| CI YAML | 在本地用相同命令手动跑一遍（`mvn -o compile`、`node tools/api-contract.mjs`），确认命令本身正确 |
| 冲突解决步骤 | 按 AI 步骤实际执行，确认 `git status` 干净且代码能编译 |
| PR 描述 | 与 `git diff` 逐条比对，删掉 AI 编造的、未实际改动的条目 |

### 7.4 AI 出的问题与我的处理

1. AI 生成的 CI 里用了 `mvn spring-boot:run &` 后台启动，但没做就绪等待，
   测试会因连接被拒绝而随机失败 → 补轮询 `/api/health`；
2. AI 编造了一条「已优化数据库索引」的 PR 描述，而 diff 里并没有 → **人工删除**。
   这条教训很直白：**PR 描述必须对着 diff 写，不能交给 AI 自由发挥**。
