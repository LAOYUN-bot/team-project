# 实验一：搭建 Web 基础项目（Spring Boot / SSM）

- 姓名：＿＿＿＿＿　学号：＿＿＿＿＿　日期：＿＿＿＿＿
- 结对成员（如有）：＿＿＿＿＿

---

## 一、实验目标（课程原文）

> 用 Maven / Gradle 创建 Web 项目，配置数据源，完成**用户表 CRUD**；
> 用内嵌容器或 Tomcat 部署运行。

课程步骤要点：
1. 创建项目（推荐 Spring Boot 3.x，起步依赖 `spring-boot-starter-web`、`mybatis-spring-boot-starter`、`mysql-connector-j`）；
2. 配置 `application.yml` 数据源；编写 **Entity / Mapper / Service / Controller 分层**；
3. 用 AI 助手生成脚手架与单测，但须逐行理解并人工验证；
4. 运行 `mvn spring-boot:run` 或打成 jar 部署，验证 CRUD。

---

## 二、实验环境

| 项 | 版本 | 检查命令 |
|---|---|---|
| JDK | 17 | `java -version` |
| Maven | 3.9.12 | `mvn -v` |
| Spring Boot | 4.0.8 | 见 `pom.xml` |
| MyBatis | `mybatis-spring-boot-starter` 4.0.1（内部 mybatis 3.5.19） | `mvn dependency:tree` |
| 数据库 | H2 2.2.224（内存，零配置）；可选 MySQL 8 | —— |
| IDE | IntelliJ IDEA | —— |

> **说明**：课程推荐 Spring Boot 3.x + MySQL。本项目开发机为离线环境，
> 本地仓库只有 Spring Boot 4.0.x，且无 Docker 起 MySQL，因此使用
> **Spring Boot 4.0.8 + H2 内存库**（同为 Jakarta 命名空间，写法与 3.x 一致），
> 并保留 MySQL profile 一键切换。这一点在报告 §四「问题与解决」中详细说明。

---

## 三、实验步骤

### 3.1 创建项目与分层骨架

项目坐标与依赖（`pom.xml` 节选）：

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>4.0.8</version>
</parent>

<dependencies>
  <!-- Web MVC + 内嵌 Tomcat -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
  </dependency>
  <!-- 数据源与连接池 -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
  </dependency>
  <!-- MyBatis：课程实验一要求的持久层 -->
  <dependency>
    <groupId>org.mybatis.spring.boot</groupId>
    <artifactId>mybatis-spring-boot-starter</artifactId>
    <version>4.0.1</version>
  </dependency>
  <!-- H2 内存库（MySQL 亦可，见 application-mysql profile） -->
  <dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <version>2.2.224</version>
    <scope>runtime</scope>
  </dependency>
</dependencies>
```

**四层分层**（对应课程要求）：

| 层 | 本项目位置 | 职责 |
|---|---|---|
| Controller | `m1perception/AuthController`、`PerceptionController` | 接收请求、参数校验、返回统一响应体 |
| Service | `m1perception/PerceptionService` | 业务规则（用户名唯一、密码强度、BCrypt 加密） |
| Mapper | `m1perception/mapper/UserMapper` + `resources/mapper/UserMapper.xml` | SQL 持久化 |
| Entity | `m1perception/entity/User`、`UserProfile` | 数据载体 |

📷 **截图 1**：项目目录树（展开 `entity` / `mapper` / `service` / `controller` 四层）
📷 **截图 2**：`pom.xml` 中的依赖片段

### 3.2 配置数据源

`src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:a02;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE
    driver-class-name: org.h2.Driver
    username: sa
    password: ''
  sql:
    init:
      mode: always
      schema-locations: classpath:db/schema.sql   # 45 张表 DDL
      data-locations: classpath:db/data.sql       # 知识图谱种子数据

mybatis:
  mapper-locations: classpath:mapper/*.xml
  type-aliases-package: cn.zjnu.a02.m1perception.entity,cn.zjnu.a02.m5execution.entity
  configuration:
    map-underscore-to-camel-case: true   # user_id → userId 自动映射
```

**关键点**：`MODE=MySQL` 让 H2 兼容 MySQL 语法；`DATABASE_TO_LOWER=TRUE` 让列名统一小写，
避免不同数据库大小写行为不一致导致的隐性 bug。

📷 **截图 3**：`application.yml` 数据源配置

### 3.3 Entity 层

```java
public class User {
    private Long id;
    private String username;
    private String passwordHash;   // BCrypt 密文，任何情况下不得返回前端
    private String nickname;
    private String role;           // USER / ADMIN
    private String status;
    private LocalDateTime createdAt;
    // getter / setter ...
}
```

### 3.4 Mapper 层（接口 + XML）

`UserMapper.java`：

```java
@Mapper
public interface UserMapper {
    User selectById(@Param("id") Long id);
    User selectByUsername(@Param("username") String username);
    int countByUsername(@Param("username") String username);
    int insert(User user);                                    // 主键回填
    int updateRole(@Param("id") Long id, @Param("role") String role);
    int updatePassword(@Param("id") Long id, @Param("passwordHash") String passwordHash);
    int softDelete(@Param("id") Long id);
    List<User> selectAll();
}
```

`resources/mapper/UserMapper.xml`：

```xml
<mapper namespace="cn.zjnu.a02.m1perception.mapper.UserMapper">
    <sql id="columns">
        id, username, password_hash, nickname, role, status, created_at, updated_at, deleted
    </sql>

    <select id="selectByUsername" resultType="cn.zjnu.a02.m1perception.entity.User">
        SELECT <include refid="columns"/>
        FROM sys_user
        WHERE username = #{username} AND deleted = 0
    </select>

    <insert id="insert" parameterType="cn.zjnu.a02.m1perception.entity.User"
            useGeneratedKeys="true" keyProperty="id">
        INSERT INTO sys_user (username, password_hash, nickname, role, status, deleted)
        VALUES (#{username}, #{passwordHash}, #{nickname}, #{role}, 'ACTIVE', 0)
    </insert>
    ...
</mapper>
```

**三个值得说明的写法**：
1. `<sql id="columns">` 显式列清单，**不用 `SELECT *`**（表结构演进时不会污染实体）；
2. 所有参数用 `#{}` 预编译，**禁止 `${}` 拼接**（防 SQL 注入）；
3. `deleted = 0` 体现在每一处查询里，实现逻辑删除。

📷 **截图 4**：`UserMapper.xml`（含 namespace 与 insert 的 useGeneratedKeys）

### 3.5 Service 层事务与业务规则

```java
@Transactional
public Map<String, Object> register(String username, String password, String nickname) {
    if (!USERNAME.matcher(username).matches()) {
        throw new BizException(ErrorCode.INVALID_ARGUMENT, "用户名需为 4-32 位字母、数字或下划线");
    }
    validatePassword(password);
    if (userMapper.countByUsername(username) > 0) {
        throw new BizException(ErrorCode.USERNAME_TAKEN);
    }
    User user = new User();
    user.setUsername(username);
    user.setPasswordHash(passwordEncoder.encode(password));   // BCrypt(cost=10)
    user.setNickname(nickname == null || nickname.isBlank() ? username : nickname);
    user.setRole("USER");
    userMapper.insert(user);
    long userId = user.getId();          // useGeneratedKeys 回填的主键
    ...
}
```

### 3.6 Controller 层

```java
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody RegisterRequest request,
                                                     HttpServletResponse response) {
        Map<String, Object> result = perception.register(
                request.username(), request.password(), request.nickname());
        setTokenCookie(response, String.valueOf(result.get("token")));   // HttpOnly Cookie
        return ApiResponse.ok(result);
    }
}
```

📷 **截图 5**：`AuthController` 代码

### 3.7 启动与 CRUD 验证

```powershell
cd team-project
.\run.ps1
# 控制台出现：Started A02Application in 4.3 seconds
```

**CRUD 验证（用 Postman / curl / 浏览器 F12）**：

| 操作 | 请求 | 预期 |
|---|---|---|
| Create | `POST /api/auth/register` `{"username":"test001","password":"test1234","nickname":"小测"}` | `code=0`，返回 `userId` |
| Read | `POST /api/auth/login` `{"username":"test001","password":"test1234"}` | `code=0`，返回 token 与 `profileComplete=false` |
| Read | `GET /api/users/me`（带 Cookie） | 返回当前用户信息 |
| Update | `PUT /api/users/me/profile` `{"education":"本科","targetJobId":1001,"weeklyHours":10}` | `code=0`，返回已保存的画像 |
| 错误分支 | 重复注册 `test001` | `code=20004 用户名已被占用` |
| 错误分支 | 弱口令 `123456` | `code=10001 密码至少 8 位` |

📷 **截图 6**：注册成功响应（Postman 或 curl 输出）
📷 **截图 7**：重复注册返回 20004
📷 **截图 8**：H2 控制台（http://localhost:8080/h2-console，JDBC URL 填 `jdbc:h2:mem:a02`）里 `SELECT * FROM sys_user` 的结果

> **注意截图 8 的看点是**：`password_hash` 列是 `$2a$10$...` 的 BCrypt 密文，**不是明文** ——
> 这是实验四的安全要求，在实验一截图里就能一并证明。

---

## 四、遇到的问题与解决

| # | 问题 | 现象 | 解决 |
|---|------|------|------|
| 1 | 开发机无外网，Spring Boot 3.x 依赖拉不到 | `mvn` 报找不到 3.2.x | 改用本地仓库已有的 Spring Boot 4.0.8（同为 Jakarta 命名空间，`jakarta.*` 写法一致），并记录到 `README.md` 的技术选型偏差说明 |
| 2 | 没有 Docker / MySQL 容器 | 无法起数据库 | 默认使用 H2 内存库（`MODE=MySQL` 兼容模式），另提供 `-Profile mysql` 切换本机 MySQL 8 |
| 3 | `Db.insert` 抛 `The getKey method should only be used when a single key is returned` | H2 在 `RETURN_GENERATED_KEYS` 下回传整行 | `prepareStatement(sql, new String[]{"id"})` 显式指定只取 id 列 |
| 4 | 中文列名/数据在控制台乱码 | 控制台代码页问题 | 确认日志文件本身为 UTF-8（用十六进制核对字节），属显示问题，不影响功能 |
| 5 | `sys_user` 等表名与保留字冲突风险 | —— | 统一加 `sys_` 前缀，避开 `user` 等保留字 |

---

## 五、验证与结果

```powershell
# 编译
mvn -o compile                                    # BUILD SUCCESS

# 单元测试（含 MyBatis 相关行为）
mvn -o test-compile
java -cp "target/classes;target/test-classes;$(Get-Content target/cp-test.txt -Raw)" cn.zjnu.a02.SelfTestRunner
# → 通过 46 项，失败 0 项
```

**CRUD 走的是 MyBatis Mapper 的证据**：接口契约自检中「当前用户 / 基础信息 / 学习任务」
三项全部通过（分别对应 `UserMapper`、`UserProfileMapper`、`LearningTaskMapper`）：

```powershell
node tools/api-contract.mjs
# ✓ 当前用户   GET /api/users/me          OK
# ✓ 基础信息   GET /api/users/me/profile  OK
# ✓ 学习任务   GET /api/learning/tasks    OK
```

📷 **截图 9**：`node tools/api-contract.mjs` 的运行结果

---

## 六、总结与反思

> （请各同学写自己的内容，以下为可选参考方向）

**学到了什么**
- 分层不是形式：把 SQL 放进 XML 后，Service 里只剩业务规则，改一处不影响另一处；
- `useGeneratedKeys` + `keyProperty` 是「插入后立刻拿到主键」的标准做法，
  比插入后再按唯一键查一次更可靠；
- 逻辑删除（`deleted=0`）要在**每一处查询**里体现，漏一处就会出现「已删除数据又冒出来」。

**容易忽略的点**
- `SELECT *` 的危害：表加字段后实体映射可能出错，且会把不该暴露的列（如 `password_hash`）
  带进内存，增加泄漏面；
- H2 与 MySQL 在「返回生成键」「大小写」上的差异，需要用兼容模式 + 统一小写规避。

---

## 七、AI 工具使用记录

> （请填自己真实的使用情况；以下是本项目开发中实际发生过的例子，供参照）

### 7.1 使用的工具与环节

| 工具 | 环节 | 用途 |
|---|---|---|
| Claude Code / Cursor 等 | 脚手架 | 生成 Entity 的 getter/setter 与 Mapper 接口框架 |
| 同上 | 单测 | 让 AI 枚举边界情况（空用户名、超长用户名、弱口令） |
| 同上 | 报错排查 | 把 `The getKey method should only be used...` 贴给 AI 找原因 |

### 7.2 提示词要点

- **给出约束再要代码**：`「用 MyBatis 注解 + XML 两种方式各写一个 selectByUsername，参数用 #{}，返回实体，表名 sys_user，逻辑删除过滤 deleted=0」`
- **要求解释而非只给结果**：`「先说明这段 SQL 在 H2 的 MySQL 兼容模式下是否有方言问题，再给代码」`

### 7.3 产出如何验证（**这是本栏目的重点**）

| AI 产出 | 验证手段 | 结果 |
|---|---|---|
| Mapper XML | 启动应用后调真实接口（`/api/users/me`） | 通过；曾因 H2 生成键问题失败，已修复 |
| 边界单测 | 运行 `SelfTestRunner`，看断言是否真能失败 | 通过 |
| 数据源配置 | 启动日志出现 `HikariPool-1 - Added connection conn0: url=jdbc:h2:mem:a02` | 通过 |

### 7.4 AI 出的问题与我的处理

1. AI 建议用 `SELECT *` 简化 XML —— **未采纳**，改为显式列清单（§3.4 说明）；
2. AI 生成的一个 `catch` 分支里只有注释没有日志 —— 人工补上 `log.warn`，
   否则排查问题时看不到任何线索；
3. AI 给出的 MyBatis-Plus 写法在本环境不可用（依赖不存在）—— 改为原生 MyBatis。
