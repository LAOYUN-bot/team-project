-- ============================================================================
-- A02 Demo 种子数据
-- 覆盖：RBAC、职业知识图谱（4 岗位域 / 124 技能节点 / 20 课程 / 6 认证）、
--       3 个职场模拟训练场景与评分 Rubric、系统配置、知识库引用
-- 说明：全部为公开资料改写与自构造模拟数据，符合「仅用公开/自构造数据」合规要求
-- ============================================================================

-- ---------------- RBAC ----------------
INSERT INTO sys_role (id, code, name) VALUES (1, 'USER', '普通用户'), (2, 'ADMIN', '管理员');
INSERT INTO sys_permission (id, code, name) VALUES
  (1, 'profile:read', '查看画像'), (2, 'profile:write', '修改画像'),
  (3, 'admin:read', '管理后台查看'), (4, 'admin:config', '系统配置');
INSERT INTO sys_role_permission (role_id, permission_id) VALUES (1,1),(1,2),(2,1),(2,2),(2,3),(2,4);

-- ---------------- 系统配置（雷达权重与衰减因子，可截图给评委看） ----------------
INSERT INTO sys_config (cfg_key, cfg_value, remark) VALUES
  ('radar.formula.version', 'v1', '雷达计算公式版本号'),
  ('radar.alpha.assessment', '0.6', '测评来源权重'),
  ('radar.alpha.simulation', '0.7', '训练评分来源权重'),
  ('radar.alpha.task', '0.4', '学习任务来源权重'),
  ('radar.decay.alpha', '0.35', '新旧值衰减因子 new = old*(1-a)+score*a'),
  ('radar.match.formula', '100*sum(require_i*radar_i/100)/sum(require_i)', '岗位匹配度公式'),
  ('ai.provider', 'mock', 'AI 提供方：mock / tbox'),
  ('privacy.collect.scope', '最小必要：不采集身份证/银行卡', '隐私采集边界');

-- ---------------- 知识库文档引用（向量托管于百宝箱知识库） ----------------
INSERT INTO kb_doc_ref (id, title, source, category, chunk_count, updated_at) VALUES
  (1, '“十四五”终身职业技能培训规划（节选）', '人力资源和社会保障部公开文件', '政策', 42, CURRENT_TIMESTAMP),
  (2, '2026 年数字技术人才需求趋势观察', '公开行业研究报告（改写）', '趋势', 66, CURRENT_TIMESTAMP),
  (3, 'Java 后端工程师岗位能力要求汇编', '公开招聘 JD 脱敏聚合（自构造）', '岗位', 88, CURRENT_TIMESTAMP),
  (4, '数据分析师岗位能力要求汇编', '公开招聘 JD 脱敏聚合（自构造）', '岗位', 74, CURRENT_TIMESTAMP),
  (5, '大模型应用开发岗位能力要求汇编', '公开招聘 JD 脱敏聚合（自构造）', '岗位', 59, CURRENT_TIMESTAMP),
  (6, 'AI 伦理与个人信息保护操作指引', '公开法规与规范整理', '伦理', 37, CURRENT_TIMESTAMP);

-- ---------------- 职业知识图谱：岗位实体 ----------------
INSERT INTO kg_entity (id, type, code, name, attrs_json) VALUES
  (1001, 'JOB', 'JAVA_BACKEND',    'Java 后端开发工程师', '{"domain":"后端开发","demandTrend":"平稳偏升","avgSalaryBand":"12-25K"}'),
  (1002, 'JOB', 'DATA_ANALYST',    '数据分析师',          '{"domain":"数据","demandTrend":"上升","avgSalaryBand":"10-20K"}'),
  (1003, 'JOB', 'FRONTEND_ENGINEER','前端开发工程师',      '{"domain":"前端开发","demandTrend":"平稳","avgSalaryBand":"10-20K"}'),
  (1004, 'JOB', 'AI_APP_ENGINEER', 'AI 应用开发工程师',    '{"domain":"人工智能","demandTrend":"快速上升","avgSalaryBand":"15-30K"}');

-- ---------------- 职业知识图谱：技能实体（124 个） ----------------
INSERT INTO kg_entity (id, type, code, name, attrs_json) VALUES
  (2001,'SKILL','JAVA_BASIC','Java 语言基础',NULL),(2002,'SKILL','OO_DESIGN','面向对象设计',NULL),
  (2003,'SKILL','COLLECTION_GENERIC','集合与泛型',NULL),(2004,'SKILL','CONCURRENCY','并发与多线程',NULL),
  (2005,'SKILL','JVM_TUNING','JVM 原理与调优',NULL),(2006,'SKILL','SPRING_FRAMEWORK','Spring Framework',NULL),
  (2007,'SKILL','SPRING_BOOT','Spring Boot',NULL),(2008,'SKILL','SPRING_SECURITY','Spring Security',NULL),
  (2009,'SKILL','ORM_MYBATIS','MyBatis / ORM',NULL),(2010,'SKILL','REST_API','RESTful API 设计',NULL),
  (2011,'SKILL','MYSQL','MySQL 数据库',NULL),(2012,'SKILL','SQL_TUNING','SQL 优化与索引',NULL),
  (2013,'SKILL','REDIS','Redis 缓存',NULL),(2014,'SKILL','KAFKA','消息队列 Kafka',NULL),
  (2015,'SKILL','MICROSERVICE','微服务架构',NULL),(2016,'SKILL','DOCKER','Docker 容器化',NULL),
  (2017,'SKILL','LINUX','Linux 常用命令',NULL),(2018,'SKILL','GIT','Git 版本控制',NULL),
  (2019,'SKILL','MAVEN','Maven 构建',NULL),(2020,'SKILL','JUNIT','单元测试 JUnit',NULL),
  (2021,'SKILL','DESIGN_PATTERN','设计模式',NULL),(2022,'SKILL','DISTRIBUTED_TX','分布式事务',NULL),
  (2023,'SKILL','HIGH_CONCURRENCY','高并发系统设计',NULL),(2024,'SKILL','NGINX','Nginx 反向代理',NULL),
  (2025,'SKILL','CICD','CI/CD 流水线',NULL),(2026,'SKILL','OPENAPI','接口文档 OpenAPI',NULL),
  (2027,'SKILL','OBSERVABILITY','日志与链路追踪',NULL),(2028,'SKILL','PERF_TEST','性能压测',NULL),
  (2029,'SKILL','DDD','领域驱动设计',NULL),(2030,'SKILL','CODE_REVIEW','代码评审与规范',NULL),
  (2031,'SKILL','HTML5','HTML5 语义化',NULL),(2032,'SKILL','CSS3','CSS3 布局与响应式',NULL),
  (2033,'SKILL','JAVASCRIPT','JavaScript ES6+',NULL),(2034,'SKILL','TYPESCRIPT','TypeScript',NULL),
  (2035,'SKILL','VUE3','Vue 3 组合式 API',NULL),(2036,'SKILL','REACT','React 基础',NULL),
  (2037,'SKILL','PINIA','状态管理 Pinia',NULL),(2038,'SKILL','VUE_ROUTER','前端路由',NULL),
  (2039,'SKILL','COMPONENT_DESIGN','组件化设计',NULL),(2040,'SKILL','VITE','前端工程化 Vite',NULL),
  (2041,'SKILL','WEB_PERF','前端性能优化',NULL),(2042,'SKILL','RENDER_PRINCIPLE','浏览器渲染原理',NULL),
  (2043,'SKILL','HTTP_PROTOCOL','HTTP 协议',NULL),(2044,'SKILL','CROSS_PLATFORM','跨端适配',NULL),
  (2045,'SKILL','ECHARTS','数据可视化 ECharts',NULL),(2046,'SKILL','SVG_CANVAS','SVG 与 Canvas 绘图',NULL),
  (2047,'SKILL','VITEST','前端单元测试 Vitest',NULL),(2048,'SKILL','WEB_SECURITY','Web 安全 XSS/CSRF',NULL),
  (2049,'SKILL','A11Y','无障碍访问',NULL),(2050,'SKILL','MICRO_FRONTEND','微前端',NULL),
  (2051,'SKILL','MINIPROGRAM','小程序开发',NULL),(2052,'SKILL','NODEJS','Node.js 服务端',NULL),
  (2053,'SKILL','PKG_MANAGE','包管理与依赖治理',NULL),(2054,'SKILL','FE_MONITOR','前端监控',NULL),
  (2055,'SKILL','DESIGN_SYSTEM','设计系统与组件库',NULL),
  (2056,'SKILL','STATISTICS','统计学基础',NULL),(2057,'SKILL','PROBABILITY','概率论',NULL),
  (2058,'SKILL','EXCEL','Excel 高级分析',NULL),(2059,'SKILL','SQL_ANALYSIS','SQL 数据分析',NULL),
  (2060,'SKILL','PYTHON','Python 编程',NULL),(2061,'SKILL','PANDAS','Pandas 数据处理',NULL),
  (2062,'SKILL','NUMPY','NumPy 数值计算',NULL),(2063,'SKILL','DATA_CLEAN','数据清洗',NULL),
  (2064,'SKILL','EDA','探索性数据分析',NULL),(2065,'SKILL','TABLEAU','数据可视化 Tableau',NULL),
  (2066,'SKILL','METRIC_SYSTEM','指标体系设计',NULL),(2067,'SKILL','AB_TEST','A/B 测试',NULL),
  (2068,'SKILL','USER_BEHAVIOR','用户行为分析',NULL),(2069,'SKILL','FUNNEL_RETENTION','漏斗与留存分析',NULL),
  (2070,'SKILL','DW_MODELING','数据仓库建模',NULL),(2071,'SKILL','HIVE','Hive 数据查询',NULL),
  (2072,'SKILL','SPARK','Spark 大数据处理',NULL),(2073,'SKILL','ML_BASIC','机器学习基础',NULL),
  (2074,'SKILL','MODEL_REG_CLS','回归与分类模型',NULL),(2075,'SKILL','TIME_SERIES','时间序列预测',NULL),
  (2076,'SKILL','BIZ_INSIGHT','商业洞察与报告',NULL),(2077,'SKILL','DATA_GOVERNANCE','数据治理',NULL),
  (2078,'SKILL','TRACKING_DESIGN','埋点方案设计',NULL),(2079,'SKILL','BI_REPORT','BI 报表开发',NULL),
  (2080,'SKILL','DATA_STORYTELLING','数据讲故事',NULL),
  (2081,'SKILL','ML_ENGINEERING','机器学习工程',NULL),(2082,'SKILL','DEEP_LEARNING','深度学习基础',NULL),
  (2083,'SKILL','NEURAL_NET','神经网络原理',NULL),(2084,'SKILL','NLP','自然语言处理',NULL),
  (2085,'SKILL','LLM_PRINCIPLE','大语言模型原理',NULL),(2086,'SKILL','PROMPT_ENGINEERING','提示工程',NULL),
  (2087,'SKILL','RAG','RAG 检索增强生成',NULL),(2088,'SKILL','VECTOR_DB','向量数据库',NULL),
  (2089,'SKILL','KNOWLEDGE_GRAPH','知识图谱',NULL),(2090,'SKILL','AGENT_ORCHESTRATION','智能体编排',NULL),
  (2091,'SKILL','MCP_PROTOCOL','MCP 协议',NULL),(2092,'SKILL','LANGCHAIN','LangChain 框架',NULL),
  (2093,'SKILL','FINE_TUNING','模型微调',NULL),(2094,'SKILL','MODEL_EVAL','模型评估与对齐',NULL),
  (2095,'SKILL','AI_ETHICS','AI 伦理与合规',NULL),(2096,'SKILL','DATA_PRIVACY','数据隐私保护',NULL),
  (2097,'SKILL','EMBEDDING','文本嵌入与检索',NULL),(2098,'SKILL','MULTIMODAL','多模态应用',NULL),
  (2099,'SKILL','MODEL_SERVING','模型部署与推理优化',NULL),(2100,'SKILL','DIALOG_SYSTEM','对话系统设计',NULL),
  (2101,'SKILL','WORKFLOW_AUTO','工作流自动化',NULL),(2102,'SKILL','AI_PRODUCT','AI 产品设计',NULL),
  (2103,'SKILL','HALLUCINATION_CTRL','幻觉抑制',NULL),(2104,'SKILL','PROMPT_INJECTION_DEF','提示注入防护',NULL),
  (2105,'SKILL','COMMUNICATION','沟通表达',NULL),(2106,'SKILL','TEAMWORK','团队协作',NULL),
  (2107,'SKILL','PROJECT_MGMT','项目管理',NULL),(2108,'SKILL','REQUIREMENT_ANALYSIS','需求分析',NULL),
  (2109,'SKILL','TECH_WRITING','技术写作',NULL),(2110,'SKILL','PRESENTATION','演讲汇报',NULL),
  (2111,'SKILL','TIME_MGMT','时间管理',NULL),(2112,'SKILL','LEARNING_METHOD','学习方法论',NULL),
  (2113,'SKILL','PROBLEM_DECOMPOSE','问题拆解',NULL),(2114,'SKILL','CRITICAL_THINKING','批判性思维',NULL),
  (2115,'SKILL','CROSS_DEPT','跨部门协作',NULL),(2116,'SKILL','SCRUM','敏捷开发 Scrum',NULL),
  (2117,'SKILL','ENGLISH_READING','英语技术阅读',NULL),(2118,'SKILL','CAREER_PLANNING','职业规划',NULL),
  (2119,'SKILL','RESUME_INTERVIEW','简历与面试',NULL),(2120,'SKILL','EMOTION_MGMT','情绪管理',NULL),
  (2121,'SKILL','CONFLICT_RESOLVE','冲突处理',NULL),(2122,'SKILL','EMPATHY','同理心沟通',NULL),
  (2123,'SKILL','LEADERSHIP','领导力基础',NULL),(2124,'SKILL','BIZ_UNDERSTANDING','商业理解',NULL);

-- ---------------- 职业知识图谱：课程与认证实体 ----------------
INSERT INTO kg_entity (id, type, code, name, attrs_json) VALUES
  (3001,'COURSE','C_JAVA_CORE','《Java 核心技术》','{"platform":"公开慕课","hours":48}'),
  (3002,'COURSE','C_SPRING_BOOT','《Spring Boot 实战》','{"platform":"公开慕课","hours":32}'),
  (3003,'COURSE','C_MYSQL_PERF','《MySQL 高性能实践》','{"platform":"公开慕课","hours":24}'),
  (3004,'COURSE','C_REDIS','《Redis 设计与实现》','{"platform":"技术书籍","hours":20}'),
  (3005,'COURSE','C_DOCKER','《Docker 与容器化部署》','{"platform":"公开慕课","hours":16}'),
  (3006,'COURSE','C_VUE3','《Vue 3 官方教程》','{"platform":"官方文档","hours":30}'),
  (3007,'COURSE','C_TS','《TypeScript 深入浅出》','{"platform":"公开慕课","hours":20}'),
  (3008,'COURSE','C_ECHARTS','《ECharts 数据可视化实战》','{"platform":"公开慕课","hours":14}'),
  (3009,'COURSE','C_PYTHON','《Python 数据分析》','{"platform":"公开慕课","hours":36}'),
  (3010,'COURSE','C_PANDAS','《Pandas 数据处理实战》','{"platform":"公开慕课","hours":22}'),
  (3011,'COURSE','C_STAT_ML','《统计学习方法》','{"platform":"经典教材","hours":60}'),
  (3012,'COURSE','C_BIGDATA','《大数据技术原理》','{"platform":"公开慕课","hours":40}'),
  (3013,'COURSE','C_PROMPT','《提示工程指南》','{"platform":"公开文档","hours":10}'),
  (3014,'COURSE','C_RAG','《RAG 应用开发实战》','{"platform":"公开课程","hours":18}'),
  (3015,'COURSE','C_MCP','《MCP 协议与工具开发》','{"platform":"官方规范","hours":12}'),
  (3016,'COURSE','C_KG','《知识图谱构建方法》','{"platform":"公开课程","hours":24}'),
  (3017,'COURSE','C_AI_ETHICS','《AI 伦理与个人信息保护》','{"platform":"公开课程","hours":8}'),
  (3018,'COURSE','C_COMM','《高效沟通与协作》','{"platform":"公开课程","hours":12}'),
  (3019,'COURSE','C_INTERVIEW','《求职面试训练营》','{"platform":"公开课程","hours":16}'),
  (3020,'COURSE','C_GIT','《Git 与团队协作规范》','{"platform":"官方文档","hours":8}'),
  (4001,'CERT','CERT_SE','软件设计师（软考中级）','{"issuer":"工信部教育与考试中心","validity":"长期有效"}'),
  (4002,'CERT','CERT_ARCH','系统架构设计师（软考高级）','{"issuer":"工信部教育与考试中心","validity":"长期有效"}'),
  (4003,'CERT','CERT_ACP','阿里云 ACP 云计算工程师','{"issuer":"阿里云","validity":"3 年"}'),
  (4004,'CERT','CERT_CDA','CDA 数据分析师（Level Ⅰ）','{"issuer":"CDA 协会","validity":"长期有效"}'),
  (4005,'CERT','CERT_HCIA_AI','华为 HCIA-AI','{"issuer":"华为","validity":"3 年"}'),
  (4006,'CERT','CERT_PMP','PMP 项目管理专业人士','{"issuer":"PMI","validity":"3 年"}');

-- ---------------- 岗位 —技能 权重（岗位能力模型，FR-2.3） ----------------
INSERT INTO kg_relation (subject_id, predicate, object_id, weight, source) VALUES
  (1001,'REQUIRES',2001,5,'岗位能力模型 v1'),(1001,'REQUIRES',2002,5,'岗位能力模型 v1'),
  (1001,'REQUIRES',2003,4,'岗位能力模型 v1'),(1001,'REQUIRES',2004,4,'岗位能力模型 v1'),
  (1001,'REQUIRES',2005,3,'岗位能力模型 v1'),(1001,'REQUIRES',2006,5,'岗位能力模型 v1'),
  (1001,'REQUIRES',2007,5,'岗位能力模型 v1'),(1001,'REQUIRES',2009,4,'岗位能力模型 v1'),
  (1001,'REQUIRES',2010,5,'岗位能力模型 v1'),(1001,'REQUIRES',2011,5,'岗位能力模型 v1'),
  (1001,'REQUIRES',2012,4,'岗位能力模型 v1'),(1001,'REQUIRES',2013,4,'岗位能力模型 v1'),
  (1001,'REQUIRES',2015,3,'岗位能力模型 v1'),(1001,'REQUIRES',2016,3,'岗位能力模型 v1'),
  (1001,'REQUIRES',2018,4,'岗位能力模型 v1'),(1001,'REQUIRES',2020,3,'岗位能力模型 v1'),
  (1001,'REQUIRES',2105,3,'岗位能力模型 v1'),(1001,'REQUIRES',2106,3,'岗位能力模型 v1'),
  (1002,'REQUIRES',2056,5,'岗位能力模型 v1'),(1002,'REQUIRES',2059,5,'岗位能力模型 v1'),
  (1002,'REQUIRES',2060,5,'岗位能力模型 v1'),(1002,'REQUIRES',2061,5,'岗位能力模型 v1'),
  (1002,'REQUIRES',2062,3,'岗位能力模型 v1'),(1002,'REQUIRES',2063,4,'岗位能力模型 v1'),
  (1002,'REQUIRES',2064,4,'岗位能力模型 v1'),(1002,'REQUIRES',2065,3,'岗位能力模型 v1'),
  (1002,'REQUIRES',2066,4,'岗位能力模型 v1'),(1002,'REQUIRES',2068,3,'岗位能力模型 v1'),
  (1002,'REQUIRES',2070,4,'岗位能力模型 v1'),(1002,'REQUIRES',2076,5,'岗位能力模型 v1'),
  (1002,'REQUIRES',2080,3,'岗位能力模型 v1'),(1002,'REQUIRES',2105,4,'岗位能力模型 v1'),
  (1002,'REQUIRES',2109,3,'岗位能力模型 v1'),(1002,'REQUIRES',2113,4,'岗位能力模型 v1'),
  (1003,'REQUIRES',2031,5,'岗位能力模型 v1'),(1003,'REQUIRES',2032,5,'岗位能力模型 v1'),
  (1003,'REQUIRES',2033,5,'岗位能力模型 v1'),(1003,'REQUIRES',2034,4,'岗位能力模型 v1'),
  (1003,'REQUIRES',2035,5,'岗位能力模型 v1'),(1003,'REQUIRES',2037,4,'岗位能力模型 v1'),
  (1003,'REQUIRES',2039,4,'岗位能力模型 v1'),(1003,'REQUIRES',2040,4,'岗位能力模型 v1'),
  (1003,'REQUIRES',2041,4,'岗位能力模型 v1'),(1003,'REQUIRES',2043,4,'岗位能力模型 v1'),
  (1003,'REQUIRES',2045,3,'岗位能力模型 v1'),(1003,'REQUIRES',2046,3,'岗位能力模型 v1'),
  (1003,'REQUIRES',2048,4,'岗位能力模型 v1'),(1003,'REQUIRES',2049,2,'岗位能力模型 v1'),
  (1003,'REQUIRES',2053,3,'岗位能力模型 v1'),(1003,'REQUIRES',2055,3,'岗位能力模型 v1'),
  (1004,'REQUIRES',2060,5,'岗位能力模型 v1'),(1004,'REQUIRES',2082,4,'岗位能力模型 v1'),
  (1004,'REQUIRES',2084,4,'岗位能力模型 v1'),(1004,'REQUIRES',2085,5,'岗位能力模型 v1'),
  (1004,'REQUIRES',2086,5,'岗位能力模型 v1'),(1004,'REQUIRES',2087,5,'岗位能力模型 v1'),
  (1004,'REQUIRES',2088,4,'岗位能力模型 v1'),(1004,'REQUIRES',2089,3,'岗位能力模型 v1'),
  (1004,'REQUIRES',2090,5,'岗位能力模型 v1'),(1004,'REQUIRES',2091,4,'岗位能力模型 v1'),
  (1004,'REQUIRES',2092,4,'岗位能力模型 v1'),(1004,'REQUIRES',2094,4,'岗位能力模型 v1'),
  (1004,'REQUIRES',2095,5,'岗位能力模型 v1'),(1004,'REQUIRES',2096,4,'岗位能力模型 v1'),
  (1004,'REQUIRES',2099,3,'岗位能力模型 v1'),(1004,'REQUIRES',2102,3,'岗位能力模型 v1'),
  (1004,'REQUIRES',2103,4,'岗位能力模型 v1'),(1004,'REQUIRES',2104,4,'岗位能力模型 v1');

-- ---------------- 课程 —技能（TEACHES） ----------------
INSERT INTO kg_relation (subject_id, predicate, object_id, weight, source) VALUES
  (3001,'TEACHES',2001,1,'课程大纲'),(3001,'TEACHES',2002,1,'课程大纲'),(3001,'TEACHES',2003,1,'课程大纲'),
  (3002,'TEACHES',2007,1,'课程大纲'),(3002,'TEACHES',2010,1,'课程大纲'),
  (3003,'TEACHES',2011,1,'课程大纲'),(3003,'TEACHES',2012,1,'课程大纲'),
  (3004,'TEACHES',2013,1,'课程大纲'),(3005,'TEACHES',2016,1,'课程大纲'),
  (3006,'TEACHES',2035,1,'课程大纲'),(3006,'TEACHES',2039,1,'课程大纲'),
  (3007,'TEACHES',2034,1,'课程大纲'),
  (3008,'TEACHES',2045,1,'课程大纲'),(3008,'TEACHES',2046,1,'课程大纲'),
  (3009,'TEACHES',2060,1,'课程大纲'),(3009,'TEACHES',2061,1,'课程大纲'),
  (3010,'TEACHES',2061,1,'课程大纲'),(3010,'TEACHES',2063,1,'课程大纲'),(3010,'TEACHES',2064,1,'课程大纲'),
  (3011,'TEACHES',2073,1,'课程大纲'),(3011,'TEACHES',2074,1,'课程大纲'),
  (3012,'TEACHES',2070,1,'课程大纲'),(3012,'TEACHES',2071,1,'课程大纲'),(3012,'TEACHES',2072,1,'课程大纲'),
  (3013,'TEACHES',2086,1,'课程大纲'),
  (3014,'TEACHES',2087,1,'课程大纲'),(3014,'TEACHES',2088,1,'课程大纲'),
  (3015,'TEACHES',2091,1,'课程大纲'),
  (3016,'TEACHES',2089,1,'课程大纲'),
  (3017,'TEACHES',2095,1,'课程大纲'),(3017,'TEACHES',2096,1,'课程大纲'),
  (3018,'TEACHES',2105,1,'课程大纲'),(3018,'TEACHES',2106,1,'课程大纲'),(3018,'TEACHES',2122,1,'课程大纲'),
  (3019,'TEACHES',2118,1,'课程大纲'),(3019,'TEACHES',2119,1,'课程大纲'),
  (3020,'TEACHES',2018,1,'课程大纲');

-- ---------------- 技能迁移/前置关系（供转岗人群压缩式路径，闭包表基于此计算） ----------------
INSERT INTO kg_relation (subject_id, predicate, object_id, weight, source) VALUES
  (2001,'PREREQUISITE_OF',2002,1,'知识图谱'),(2002,'PREREQUISITE_OF',2021,1,'知识图谱'),
  (2001,'PREREQUISITE_OF',2004,1,'知识图谱'),(2004,'PREREQUISITE_OF',2023,1,'知识图谱'),
  (2006,'PREREQUISITE_OF',2007,1,'知识图谱'),(2007,'PREREQUISITE_OF',2010,1,'知识图谱'),
  (2011,'PREREQUISITE_OF',2012,1,'知识图谱'),(2031,'PREREQUISITE_OF',2032,1,'知识图谱'),
  (2032,'PREREQUISITE_OF',2033,1,'知识图谱'),(2033,'PREREQUISITE_OF',2034,1,'知识图谱'),
  (2034,'PREREQUISITE_OF',2035,1,'知识图谱'),(2035,'PREREQUISITE_OF',2037,1,'知识图谱'),
  (2060,'PREREQUISITE_OF',2061,1,'知识图谱'),(2061,'PREREQUISITE_OF',2064,1,'知识图谱'),
  (2064,'PREREQUISITE_OF',2066,1,'知识图谱'),(2085,'PREREQUISITE_OF',2086,1,'知识图谱'),
  (2086,'PREREQUISITE_OF',2087,1,'知识图谱'),(2087,'PREREQUISITE_OF',2088,1,'知识图谱'),
  (2088,'PREREQUISITE_OF',2089,1,'知识图谱'),(2090,'PREREQUISITE_OF',2092,1,'知识图谱'),
  (2001,'RELATED_TO',2060,1,'技能迁移映射'),(2011,'RELATED_TO',2059,1,'技能迁移映射'),
  (2011,'RELATED_TO',2070,1,'技能迁移映射'),(2033,'RELATED_TO',2060,1,'技能迁移映射'),
  (2035,'RELATED_TO',2045,1,'技能迁移映射'),(2061,'RELATED_TO',2073,1,'技能迁移映射'),
  (2086,'RELATED_TO',2103,1,'技能迁移映射'),(2020,'RELATED_TO',2047,1,'技能迁移映射'),
  (2018,'RELATED_TO',2025,1,'技能迁移映射'),(2105,'RELATED_TO',2115,1,'技能迁移映射'),
  (2106,'RELATED_TO',2116,1,'技能迁移映射'),(2004,'RELATED_TO',2023,1,'技能迁移映射'),
  (2003,'VERIFIED_BY',4001,1,'认证映射'),(2015,'VERIFIED_BY',4002,1,'认证映射'),
  (2016,'VERIFIED_BY',4003,1,'认证映射'),(2059,'VERIFIED_BY',4004,1,'认证映射'),
  (2085,'VERIFIED_BY',4005,1,'认证映射'),(2107,'VERIFIED_BY',4006,1,'认证映射');

-- ---------------- 模拟训练场景（FR-5.2，≥3 个） ----------------
INSERT INTO simulation_scenario (id, code, name, description, difficulty, role_card, opening) VALUES
  (1, 'REMOTE_COLLAB', '远程协作冲突处理',
   '你所在的项目组分布在两个城市，需求方临时追加需求，远程同事对排期有异议。你需要通过线上沟通推动事情落地。',
   'NORMAL',
   '你是「林工」，一位资深后端工程师，常驻另一城市。你性格直率、注重排期合理性，不喜欢需求随意变更，但只要你给出清晰的影响分析和替代方案，你会配合。你会反问细节、提出风险，如果对方含糊其辞你会追问。',
   '林工：看到需求方又加需求了？我们这边排期已经很满了。你先说说具体是什么，为什么现在必须加——另外，你评估过对现有迭代的影响吗？'),
  (2, 'AI_OFFICE', 'AI 辅助办公：用 AI 提速周报与方案',
   '你需要在一小时内产出一份项目周报和一份技术方案初稿。你可以选择指挥 AI 助手协作，也要对 AI 产出做事实核查。',
   'NORMAL',
   '你是「智能助理小舟」，负责协助整理周报与方案初稿。你会按要求产出内容，但也会暴露不确定性：当信息不足时你会主动索要数据，当你不确定时会明确说「这部分我无法确认」。',
   '小舟：好的，我可以帮你起草周报和方案。为了写得准确，请先告诉我：本周完成了哪些事项、遇到什么问题、下周计划是什么？另外方案要面向谁汇报？'),
  (3, 'CROSS_ROLE', '跨岗位沟通：向产品经理争取技术优化时间',
   '你希望在迭代中留出时间偿还技术债，但产品经理更关心业务交付。你需要用业务语言说明技术优化的价值。',
   'HARD',
   '你是「陈经理」，产品负责人。你关注交付节奏与业务指标，对纯技术叙事不感兴趣。如果对方能把技术优化翻译成业务收益（稳定性、用户留存、返工成本），你会认真考虑并给出折中方案。',
   '陈经理：你找我聊技术债？我先说清楚，这个版本业务方催得很紧。你准备用多久、能带来什么业务上的变化？如果说不清楚，我这边很难点头。');

-- ---------------- 评分 Rubric（FR-5.3，逐维权重可配） ----------------
INSERT INTO rubric_dimension (scenario_id, dim_key, name, weight, description) VALUES
  (1,'COMMUNICATION','沟通表达',1.2,'是否表达清晰、结构分明、主动确认对方理解'),
  (1,'TASK_PROGRESS','任务推进',1.3,'是否把讨论推向明确结论、形成可执行动作项'),
  (1,'COLLABORATION','协作意识',1.2,'是否考虑对方处境与资源约束、寻求共赢'),
  (1,'PROBLEM_SOLVING','问题解决',1.1,'是否给出影响分析与替代方案而非单纯诉苦'),
  (1,'PROFESSIONALISM','职业素养',0.8,'是否尊重、克制、对事不对人'),
  (2,'COMMUNICATION','沟通表达',1.0,'指令是否具体、信息是否完整'),
  (2,'AI_LITERACY','AI 工具运用',1.4,'是否分步骤指挥 AI、提供充分上下文、做结果核查'),
  (2,'CRITICAL_THINKING','批判性核查',1.3,'是否识别 AI 输出的不确定性并交叉验证'),
  (2,'TASK_PROGRESS','任务推进',1.1,'是否按时产出可用成果物'),
  (2,'PROFESSIONALISM','职业素养',0.7,'是否标注 AI 生成内容、保持责任意识'),
  (3,'COMMUNICATION','沟通表达',1.3,'是否用业务语言而非纯技术语言'),
  (3,'INFLUENCING','说服与影响',1.3,'是否用数据/业务收益支撑诉求'),
  (3,'PROBLEM_SOLVING','问题解决',1.2,'是否给出多套方案与折中路径'),
  (3,'COLLABORATION','协作意识',1.0,'是否理解产品目标、寻求共同利益'),
  (3,'PROFESSIONALISM','职业素养',0.7,'是否情绪稳定、对事不对人');
