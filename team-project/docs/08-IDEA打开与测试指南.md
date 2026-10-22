# 08 用 IntelliJ IDEA 打开并测试本项目（逐步操作指南）

> 面向：第一次用 IDEA 打开本项目的同学。
> 目标：**打开不报红 → 能启动 → 能跑测试 → 能调接口**。
>
> ⚠️ 本项目开发机是**完全离线**环境，依赖全部来自本机的一个离线 Maven 仓库。
> 因此**第 3 步（配置 Maven）是成败关键**，跳过它一定会满屏红线。

---

## 〇、先记住这三条环境事实

| 项 | 值 | 为什么重要 |
|---|---|---|
| **JDK** | `D:\Java17` | 本机默认 `java` 是 **JDK 25**，Spring Boot 4.0.8 需要 **17**，必须显式指定 |
| **Maven** | `D:\maven-3.9.12\apache-maven-3.9.12` | 它的 `conf/settings.xml` 里把本地仓库指到了 `D:\...\repo`（**唯一有依赖的地方**） |
| **默认 `~/.m2/repository`** | 只有 11 个 jar | 如果 IDEA 用了这个仓库，导入项目必然失败 |

---

## 一、打开项目

1. 启动 IDEA（Ultimate 或 Community 都可以，本项目不依赖 Ultimate 专属功能）。
2. 菜单 **File → Open…**（**不要**用 `Import Project`，也不要用 `New → Project from Existing Sources`）。
3. 在文件选择框中定位到：

   ```
   C:\Users\Lenovo\Desktop\团队项目实训\team-project
   ```

   ⚠️ **选 `team-project` 这一层**（里面才有 `pom.xml`），
   **不要**选它的上一层 `团队项目实训`（那层只有文档和 PDF）。

4. 点 **OK**。IDEA 会问「是否信任此项目」→ 点 **Trust Project**。
5. 如果弹出 **"Maven build script found"** 提示，选择 **Open as Project**。

> 打开后**先不要管满屏红线**，那是 Maven 仓库没配对导致的，下一步解决。

---

## 二、设置项目 SDK 为 JDK 17

1. 菜单 **File → Project Structure…**（快捷键 `Ctrl+Alt+Shift+S`）。
2. 左侧选 **Project**，设置：
   - **SDK**：点下拉 → 如果没有 17，选 **Add SDK → JDK…** → 选目录 `D:\Java17`
   - **Language level**：选 **17 - Sealed types, always-strict floating-point semantics**
3. 左侧选 **Modules → a02-career-navigator → Sources** 页签，
   确认 **Language level** 也是 **17**（或 "Project default"）。
4. 点 **Apply → OK**。

---

## 三、配置 Maven（**最关键的一步**）

1. 菜单 **File → Settings…**（快捷键 `Ctrl+Alt+S`）。
2. 展开 **Build, Execution, Deployment → Build Tools → Maven**。
3. 在右侧主界面设置：

   | 字段 | 要填的值 |
   |---|---|
   | **Maven home path** | `D:\maven-3.9.12\apache-maven-3.9.12` |
   | **User settings file** | 勾上右侧 **Override**，填 `D:\maven-3.9.12\apache-maven-3.9.12\conf\settings.xml` |
   | **Local repository** | 应自动变为 `D:\maven-3.9.12\apache-maven-3.9.12\repo`；**若显示的是 `C:\Users\Lenovo\.m2\repository`，请手动改成上面这个路径** |
   | **Work offline** | ✅ **勾上**（本机无外网，勾上可避免长时间等待下载超时） |

4. 展开 **Build Tools → Maven → Runner**，把 **JRE** 设为 **17**（Project SDK 或显式 `D:\Java17`）。
5. 展开 **Build Tools → Maven → Importing**（部分版本叫 **Maven → Importing**），
   把 **JDK for importer** 设为 **17**。
6. 点 **Apply → OK**。

### 重新导入依赖

1. 打开右侧边栏的 **Maven** 工具窗（若无：**View → Tool Windows → Maven**）。
2. 点左上角的 **🔄 Reload All Maven Projects**（循环箭头图标）。
3. 等待底部进度条走完。**正常情况下红线会全部消失**。

> ✅ **成功的标志**：`src/main/java` 下的类不再飘红，
> 外部库（External Libraries）里能看到 `spring-boot-4.0.8.jar`、`mybatis-3.5.19.jar` 等。

---

## 四、创建一个运行配置（启动后端）

### 4.1 这里要区分两件事（很容易混淆）

| 做法 | 能不能用 | 原因 |
|---|---|---|
| 命令行 `mvn spring-boot:run` | ❌ 不能 | 本机离线仓库缺 `spring-boot-maven-plugin` 的完整依赖闭包 |
| IDEA 里的 **Spring Boot 类型运行配置** | ✅ **能用** | IDEA 的 Spring Boot 运行配置是**直接运行主类 + 用模块类路径**，不走那个 Maven 插件 |
| IDEA 里的 **Application 类型运行配置** | ✅ 能用 | 最朴素的方式，只填「主类」 |

**结论**：在 IDEA 里点绿三角让它自动建配置就行，无论建出来是
**Spring Boot 类型**（有「有效配置文件」那一项）还是 **Application 类型**，都能正常启动。

### 4.2 创建运行配置

1. 在项目树里打开 `src/main/java/cn/zjnu/a02/A02Application.java`。
2. 点击 `public class A02Application` 左侧的**绿色 ▶**，选 **修改运行配置 / Modify Run Configuration…**。
3. 会弹出「创建运行配置: 'A02Application'」对话框。

> ⚠️ **这里是最容易困惑的地方**：IDEA 会**自动填好**该填的两项，而
> **VM options / 工作目录 / 缩短命令行等字段默认是隐藏的**，
> 需要点对话框里「构建并运行」标题右侧的 **「修改选项(M)」**（`Alt+M`）才会出现。
> **所以：看不到 VM options 是正常的，不是你的 IDEA 有问题。**

#### 你打开后应该看到的样子（逐项对照）

| 对话框上的项 | 应显示为 | 需要动手吗 |
|---|---|---|
| **名称(N)** | `A02Application` | 不用改 |
| **运行于** | `本地机器` | 不用改 |
| 构建并运行 · 左侧下拉 | 形如 `java 17 'a02-career-navigator'` | **确认是 java 17**；若是 `java 25` 才需要改 |
| 构建并运行 · 右侧输入框 | `cn.zjnu.a02.A02Application` | 不用改（IDEA 已自动填） |
| **有效配置文件** | 空 | **留空**（这是 Spring Profile，本项目用 H2 内存库，不需要） |
| 「将带有 'provided' 作用域的依赖项添加到类路径」 | 勾选 | 不用改，保持默认即可 |
| **代码覆盖率** 下的 `cn.zjnu.a02.*` | IDEA 自动加的 | 不影响运行，留着或删掉都行 |

**结论：如果上面这些都对，直接点「确定」就能跑，不需要填任何额外字段。**

#### 可选：如果要加 VM options / 工作目录

1. 点「构建并运行」右侧的 **修改选项(M)**（`Alt+M`）。
2. 在弹出菜单里勾选：
   - **添加 VM 选项** → 列表里出现「VM 选项」输入框，填 `-Dfile.encoding=UTF-8`
   - **工作目录** → 填 `C:\Users\Lenovo\Desktop\团队项目实训\team-project`
3. 点 **确定**。

> 💡 这两项在本项目里**都不是必需的**：
> - **VM options**：IDEA 2022 以后默认就会给运行配置加 `-Dfile.encoding=UTF-8`，
>   即使不加也能正常启动（本项目读文件全部走 classpath，不依赖系统编码）。
>   加上只是更保险。
> - **工作目录**：默认就是项目根目录；本项目不读取任何相对路径文件
>   （`schema.sql` / `data.sql` / `prompts/*.txt` / 静态资源全部从 classpath 加载），
>   所以工作目录填错也不会影响运行。

#### 可选：如果启动报「命令行太长」

同样点 **修改选项(M)** → 勾选 **缩短命令行 / Shorten command line** →
值选 **JAR manifest**。Windows 上依赖较多时可能需要（本项目一般是 `classpath file` 就够）。

4. 点 **确定**。

> 📌 **IDEA Ultimate 建出来的可能是「Spring Boot」类型的配置**（对话框里有
> 「有效配置文件」这一项就是标志）。这同样可以正常启动 —— IDEA 的 Spring Boot 运行配置
> 是直接运行主类、用模块自己的类路径，**不依赖 `spring-boot-maven-plugin`**。
> 如果它启动失败，退回一步用 **Run → Edit Configurations → + → Application（纯 Application 类型）**
> 重新建一个，只填「主类」即可。

---

## 五、启动并验证

1. 选中刚建好的 `A02Application` 配置，点 **▶ Run**（或 `Shift+F10`）。
2. 观察 **Run 控制台**，依次应出现这些关键行：

```
Starting A02Application using Java 17.0.12 with PID xxxxx
Tomcat initialized with port 8080 (http)
HikariPool-1 - Added connection conn0: url=jdbc:h2:mem:a02 user=SA
Started A02Application in 4.3 seconds
知识图谱闭包表重算完成，写入 62 行，最大跳数 3
测评完成 → 已生成初始画像与差距分析 userId=2
路径生成 → 已拆解学习单元 userId=2 pathId=1
训练评分完成 → 已生成新画像版本 userId=2 score=88.3
演示数据播种完成：demo/demo1234（userId=2），admin/admin1234
```

3. 控制台出现 **Started A02Application** 后，**再等约 10 秒**（等演示数据播种完成）。
4. 浏览器打开 **http://localhost:8080** → 用 `demo / demo1234` 登录。

> 💡 **播种那几行日志是"闭环跑通"的证据**：测评 → 画像 v1 → 路径 → 30 个学习单元 → 训练评分 → 画像新版本。
> 如果这几行没出现，说明播种失败（看是否有 `演示数据播种失败` 的 WARN）。

---

## 六、运行单元测试（46 项）

### 方式 A：用 IDEA 的测试运行器（有绿/红条，推荐）

1. 打开 `src/test/java/cn/zjnu/a02/m3decision/RadarCalculatorTest.java`。
2. 点类名左侧的**绿色 ▶** → **Run 'RadarCalculatorTest'**。
3. 三个测试类都可以这样跑：
   - `cn.zjnu.a02.m3decision.RadarCalculatorTest`（雷达公式，10 项）
   - `cn.zjnu.a02.ai.StructuredOutputContractTest`（结构化输出契约，19 项）
   - `cn.zjnu.a02.platform.SecurityAndPrivacyTest`（JWT/加密/脱敏，14 项）

> ⚠️ **不要在 IDEA 里点 Maven 面板的 `test` 生命周期**——那走的是 Surefire，
> 而本机离线仓库缺少 `surefire-junit-platform` provider，会失败。
> IDEA 自己的测试运行器用的是 `junit-platform-launcher`（**已经给你装好了**），可以直接跑。

### 方式 B：运行离线执行器（一次跑全部 46 项）

1. 打开 `src/test/java/cn/zjnu/a02/SelfTestRunner.java`。
2. 点 `public static void main` 左侧的 **▶ Run 'SelfTestRunner.main()'**。
3. 预期输出：

```
通过 46 项，失败 0 项
✅ 雷达公式 / 结构化输出契约 / 安全与隐私 —— 全部断言通过。
```

---

## 七、运行四个自检脚本（IDEA 内置终端）

1. 打开终端：**View → Tool Windows → Terminal**（或 `Alt+F12`）。
2. 确认终端当前目录是 `team-project`（不是它的上一层）。
3. 依次执行：

```powershell
# ① 接口契约自检 60 项（需服务已启动）
node tools/api-contract.mjs

# ② 图表库逻辑自检 862 项断言（不需要服务）
node tools/charts-test.mjs

# ③ 前端静态自检 6 项（不需要服务；校验接口映射 / CSS 类 / 文案 / 路由）
node tools/ui-static-check.mjs

# ④ MCP 工具契约 13 项（需服务已启动）
cmd /c "python mcp-server\server.py < tools\mcp-requests.jsonl > target\mcp-out.jsonl 2>nul"
node tools/mcp-verify.mjs target/mcp-out.jsonl

# ⑤ 前端 JS 语法检查（最快的兜底）
node --check src/main/resources/static/js/app.js
node --check src/main/resources/static/js/api.js
node --check src/main/resources/static/js/charts.js
```

预期结果：60 / 862 / 6 / 13 项全部通过。

> 另有 `tools/ui-render-check.mjs`（CDP 驱动无头浏览器逐页截图 + 抓 JS 异常），
> **该脚本从未跑通**（沙箱下页面级 CDP 命令无返回），别拿它当验收依据。
> 界面效果请直接在浏览器打开 `http://127.0.0.1:8080/`，用 `demo / demo1234` 登录自己看。

---

## 八、在 IDEA 里测接口（HTTP Client，不用装 Postman）

1. 打开项目里的 `http/A02-接口测试.http`。
2. IDEA 会在每个请求左侧显示**绿色 ▶**，点它即可发送。
3. **顺序要求**：先点 **`② 登录 demo`**（它会把 token 存进全局变量），
   后面带 `Authorization: Bearer {{token}}` 的请求才能通过。
4. 想一次跑完：把光标放到文件里，点顶部的 **Run All**（注意串行执行，顺序见文件内编号）。

文件内已内置断言，会直接给绿色对勾或红色叉：

| 请求 | 断言 |
|---|---|
| ② 登录 | HTTP 200 + `code=0` + 返回 JWT |
| 【权限测试 A】demo 访问管理员接口 | **403 + 业务码 20003** |
| 【权限测试 B】admin 访问管理员接口 | 200 + `code=0` |
| 【权限测试 C】不带令牌 | **401** |
| 【错误分支】错误口令 | 401 + 业务码 20002 |

**几个值得一看的请求**：

| 请求 | 看点 |
|---|---|
| ㉓ 生成路径（`chaos: true`） | SSE 流式事件，且能看到「首次校验失败 → 重试成功」 |
| ㉟ 生成评分 | 返回逐维得分，并触发画像新版本 |
| ㊴ AI 对话 | 逐字流式返回 |
| ㊵ 提示注入测试 | 应被礼貌拒绝 |
| ㊹ MCP 注入字符 | 返回 `INVALID_ARGUMENT` |
| ㊿ 六步闭环统计 | 答辩开场用：每步的数据量 |

---

## 九、查看数据库（两种方式）

### 方式 A：H2 控制台（浏览器）

1. 保持服务运行，浏览器打开 **http://localhost:8080/h2-console**
2. 填写：

   | 字段 | 值 |
   |---|---|
   | Driver Class | `org.h2.Driver` |
   | **JDBC URL** | `jdbc:h2:mem:a02` |
   | User Name | `sa` |
   | Password | （留空） |

3. 点 **Connect**，然后可以执行例如：

```sql
SELECT username, role, LENGTH(password_hash) AS hash_len FROM sys_user;   -- 密码是 BCrypt 密文
SELECT version, triggered_by, formula_version FROM competency_radar;      -- 画像版本迭代
SELECT COUNT(*) FROM kg_entity;                                           -- 图谱节点数
SELECT scene, prompt_version, retry_count, degraded FROM ai_generation_log ORDER BY id DESC LIMIT 10;
```

### 方式 B：IDEA 自带 Database 工具（Ultimate 才有）

**View → Tool Windows → Database → + → Data Source → H2**，
URL 填 `jdbc:h2:mem:a02`，用户名 `sa`，密码留空。

> ⚠️ H2 是**内存库**：服务一停，数据全没。想持久化请用 MySQL profile（见 README §6）。

---

## 十、常见问题排查

| 现象 | 原因 | 解决 |
|---|---|---|
| 打开后**满屏红线**，`spring`/`mybatis` 均找不到 | Maven 用错了本地仓库 | 回到 **第三步**，确认 Local repository = `D:\maven-3.9.12\apache-maven-3.9.12\repo`，然后 Reload All Maven Projects |
| 启动报 `Unsupported class file major version 69` | 用了 JDK 25 | Project Structure 与 Run Configuration 的 SDK 都改成 **17** |
| 启动报 `找不到或无法加载主类` | 模块没编译 / classpath 配置错 | Build → Rebuild Project；确认 Run 配置里 "Use classpath of module" 选了 `a02-career-navigator` |
| 启动报 **命令行太长**（Command line is too long） | Windows 命令行长度限制 | Run 配置里把 **Shorten command line** 改为 `JAR manifest` |
| 控制台中文乱码 | 缺 UTF-8 参数 | Run 配置 VM options 加 `-Dfile.encoding=UTF-8`；另可 Settings → Editor → File Encodings 全部设为 UTF-8 |
| 端口 8080 被占用 | 已有实例在跑 | 关掉旧实例，或在 VM options 加 `-Dserver.port=8081` |
| `mvn test` 失败：`surefire-junit-platform ... could not be resolved` | 离线仓库缺 Surefire provider | **改用 IDEA 测试运行器**（第六步方式 A）或 `SelfTestRunner`（方式 B） |
| `mvn spring-boot:run` 失败 | 离线仓库缺 `spring-boot-maven-plugin` 依赖 | 用 Application 运行配置（第四步）；或命令行 `.\run.ps1` |
| Maven Reload 卡很久 | 在尝试联网 | Settings → Maven 勾上 **Work offline** |
| 页面 404 或样式丢失 | 服务没起来 / 静态资源没编译进 target | 先确认 `http://localhost:8080/api/health` 返回 200；Build → Rebuild Project |

---

## 十一、一天之内最快上手路径（速查）

```
File → Open… → 选 team-project 目录
  ↓
File → Project Structure → Project SDK = 17（D:\Java17）
  ↓
File → Settings → Maven → home = D:\maven-3.9.12\apache-maven-3.9.12
                          settings = …\conf\settings.xml（Override）
                          勾上 Work offline
  ↓
Maven 面板 → 🔄 Reload All Maven Projects      ← 红线消失
  ↓
打开 A02Application.java → 点绿三角 → 只看「构建并运行」那行左边是不是 java 17
                          （其余字段 IDEA 已自动填好，直接点确定）→ Run
  ↓
等控制台出现「演示数据播种完成」→ 浏览器 http://localhost:8080 → demo / demo1234
  ↓
测试：RadarCalculatorTest 右键 Run ＋ 终端 node tools/api-contract.mjs
  ↓
调接口：打开 http/A02-接口测试.http → 先跑「② 登录」
```

---

## 十二、这份指南里哪些是"环境特例"

为免误导后来者（比如换到有网的机器），把「环境特例」与「项目本身的要求」分开说明：

| 步骤 | 属于环境特例 | 说明 |
|---|---|---|
| 指定 `D:\Java17` | ✅ 特例 | 本机默认 JDK 是 25；一般机器装 JDK 17 即可 |
| 指定离线 Maven 仓库 | ✅ 特例 | 有网机器用默认 `~/.m2/repository` + 自动下载即可 |
| 勾 **Work offline** | ✅ 特例 | 有网机器不要勾 |
| 不用 `mvn spring-boot:run` | ✅ 特例 | 有网机器能正常用；本项目也支持 `.\run.ps1` |
| 不用 Maven `test` 生命周期 | ✅ 特例 | 有网机器 `mvn test` 直接可用（CI 里就是这么跑的） |
| VM options `-Dfile.encoding=UTF-8` | ⚠️ 可选 | IDEA 新版默认已加；本项目读文件全走 classpath，不加也能跑 |
| 主类 `cn.zjnu.a02.A02Application` | ❌ 项目本身 | 任何机器都一样 |
| 访问 http://localhost:8080 | ❌ 项目本身 | 端口可在 `application.yml` 改 |
| `http/A02-接口测试.http` | ❌ 项目本身 | 任何机器都能用 |
