-- ============================================================================
-- A02 AI 职业导航与终身学习伙伴系统 —— 数据库 DDL
-- 依据：docs/04-架构设计.md §6.6 表单清单
-- 约定：主键 BIGINT 自增；业务表带 created_at/updated_at/deleted（逻辑删除）
-- 兼容：H2(MySQL 模式) 与 MySQL 8.0 均可执行
-- ============================================================================

-- ---------- 身份与权限域 ----------
CREATE TABLE IF NOT EXISTS sys_user (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  username      VARCHAR(64)  NOT NULL,
  password_hash VARCHAR(120) NOT NULL,          -- BCrypt(cost=10)
  nickname      VARCHAR(64),
  role          VARCHAR(32)  NOT NULL DEFAULT 'USER',  -- USER / ADMIN (RBAC)
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted       TINYINT      NOT NULL DEFAULT 0,
  CONSTRAINT uk_sys_user_username UNIQUE (username)
);

CREATE TABLE IF NOT EXISTS sys_role (
  id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  code     VARCHAR(32) NOT NULL,
  name     VARCHAR(64) NOT NULL,
  CONSTRAINT uk_sys_role_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS sys_permission (
  id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  code    VARCHAR(64) NOT NULL,
  name    VARCHAR(64) NOT NULL,
  CONSTRAINT uk_sys_perm_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS sys_user_role (
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, role_id)
);

CREATE TABLE IF NOT EXISTS sys_role_permission (
  role_id       BIGINT NOT NULL,
  permission_id BIGINT NOT NULL,
  PRIMARY KEY (role_id, permission_id)
);

-- 隐私授权与撤回（NFR-3）
CREATE TABLE IF NOT EXISTS user_consent (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id       BIGINT      NOT NULL,
  privacy_agreed TINYINT    NOT NULL DEFAULT 0,
  ai_notice_agreed TINYINT  NOT NULL DEFAULT 0,
  agreed_at     TIMESTAMP,
  revoked_at    TIMESTAMP
);

-- ---------- 画像域（M1 感知） ----------
CREATE TABLE IF NOT EXISTS user_profile (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id       BIGINT      NOT NULL,
  education     VARCHAR(32),
  major         VARCHAR(64),
  grade         VARCHAR(32),
  work_years    INT         NOT NULL DEFAULT 0,
  current_job   VARCHAR(64),
  target_job_id BIGINT,
  interest_tags VARCHAR(255),
  weekly_hours  INT         NOT NULL DEFAULT 8,
  phone_enc     VARCHAR(255),                    -- AES-GCM 加密列
  email_enc     VARCHAR(255),                    -- AES-GCM 加密列
  updated_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_user_profile_user UNIQUE (user_id)
);

CREATE TABLE IF NOT EXISTS assessment_record (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT    NOT NULL,
  score_json CLOB      NOT NULL,                 -- 8 维原始分（0-100）
  scale      VARCHAR(16) NOT NULL DEFAULT 'LEVEL5',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS assessment_answer (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  record_id   BIGINT NOT NULL,
  question_no INT    NOT NULL,
  dimension   VARCHAR(32),
  answer      INT    NOT NULL
);

-- 简历/经历文本的 LLM 结构化抽取标签（用户可编辑确认）
CREATE TABLE IF NOT EXISTS resume_extract_tag (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT      NOT NULL,
  tag        VARCHAR(64) NOT NULL,
  category   VARCHAR(32),
  confirmed  TINYINT     NOT NULL DEFAULT 0,
  created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 行为埋点（FR-1.5）
CREATE TABLE IF NOT EXISTS behavior_event (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT      NOT NULL,
  event_type VARCHAR(48) NOT NULL,
  payload    VARCHAR(512),
  created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------- 职业知识图谱域（M2 认知，AD-5 三元组 + 闭包表） ----------
CREATE TABLE IF NOT EXISTS kg_entity (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  type       VARCHAR(16)  NOT NULL,              -- JOB / SKILL / COURSE / CERT
  code       VARCHAR(64)  NOT NULL,
  name       VARCHAR(128) NOT NULL,
  attrs_json CLOB,
  status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT uk_kg_entity UNIQUE (type, code)
);

CREATE TABLE IF NOT EXISTS kg_relation (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  subject_id BIGINT      NOT NULL,
  predicate  VARCHAR(32) NOT NULL,               -- REQUIRES/TEACHES/RELATED_TO/PREREQUISITE_OF/VERIFIED_BY
  object_id  BIGINT      NOT NULL,
  weight     DECIMAL(6,3) NOT NULL DEFAULT 1,    -- 岗位能力模型权重 / 相关度
  source     VARCHAR(64),
  updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_kg_relation UNIQUE (subject_id, predicate, object_id)
);

-- 闭包表：加速 ≤3 跳查询（技能迁移映射等）
CREATE TABLE IF NOT EXISTS kg_path_closure (
  ancestor_id   BIGINT NOT NULL,
  descendant_id BIGINT NOT NULL,
  depth         INT    NOT NULL,
  path_weight   DECIMAL(6,3) NOT NULL DEFAULT 1,
  PRIMARY KEY (ancestor_id, descendant_id, depth)
);

-- 岗位—技能权重（由 kg_relation 中 predicate=REQUIRES 派生，物化便于统计）
CREATE TABLE IF NOT EXISTS job_skill_weight (
  job_id   BIGINT NOT NULL,
  skill_id BIGINT NOT NULL,
  weight   DECIMAL(6,3) NOT NULL,
  level    VARCHAR(16)  NOT NULL DEFAULT 'REQUIRED',
  PRIMARY KEY (job_id, skill_id)
);

CREATE TABLE IF NOT EXISTS course_skill (
  course_id BIGINT NOT NULL,
  skill_id  BIGINT NOT NULL,
  level     VARCHAR(16) NOT NULL DEFAULT 'BEGINNER',
  PRIMARY KEY (course_id, skill_id)
);

CREATE TABLE IF NOT EXISTS cert_skill (
  cert_id  BIGINT NOT NULL,
  skill_id BIGINT NOT NULL,
  PRIMARY KEY (cert_id, skill_id)
);

-- 知识库文档引用（v2.0：自建 RAG 管线，向量由本项目维护）
CREATE TABLE IF NOT EXISTS kb_doc_ref (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  code        VARCHAR(64),                       -- 语料文件名，对应 classpath:kb/{code}.md
  title       VARCHAR(200) NOT NULL,
  source      VARCHAR(200) NOT NULL,
  category    VARCHAR(32),
  chunk_count INT NOT NULL DEFAULT 0,
  updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================================
-- v2.0 新增：RAG 知识库（FR-7.2）+ 模型调用日志（NFR-8）
-- ============================================================================

-- 知识库片段：切块 + 向量（向量以 JSON 数组存储；规模小，无需外部向量库）
CREATE TABLE IF NOT EXISTS kb_chunk (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_id         BIGINT       NOT NULL,
  chunk_no       INT          NOT NULL,
  content        CLOB         NOT NULL,
  embedding_json CLOB         NOT NULL,
  char_count     INT          NOT NULL,
  source         VARCHAR(200) NOT NULL,
  category       VARCHAR(32),
  updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_kb_chunk UNIQUE (doc_id, chunk_no)
);

-- 检索日志：支撑 Recall@K 评估与拒答率统计
CREATE TABLE IF NOT EXISTS kb_retrieval_log (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT,
  query      VARCHAR(500) NOT NULL,
  top_score  DECIMAL(8,4),
  hit_count  INT     NOT NULL DEFAULT 0,
  refused    TINYINT NOT NULL DEFAULT 0,
  latency_ms BIGINT  NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- RAG 评测集：问答对，用于评估召回命中率（Recall@K）
CREATE TABLE IF NOT EXISTS kb_eval_case (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  question          VARCHAR(300) NOT NULL,
  expected_doc_code VARCHAR(64),
  expected_keywords VARCHAR(300),
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 模型调用日志（NFR-8 必备表）
CREATE TABLE IF NOT EXISTS llm_call_log (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id           BIGINT,
  scene             VARCHAR(48)  NOT NULL,
  provider          VARCHAR(32)  NOT NULL,
  model             VARCHAR(64)  NOT NULL,
  prompt_version    VARCHAR(24),
  prompt_tokens     INT     NOT NULL DEFAULT 0,
  completion_tokens INT     NOT NULL DEFAULT 0,
  total_tokens      INT     NOT NULL DEFAULT 0,
  latency_ms        BIGINT  NOT NULL DEFAULT 0,
  success           TINYINT NOT NULL DEFAULT 1,
  error_code        VARCHAR(48),
  cache_hit         TINYINT NOT NULL DEFAULT 0,
  degraded          TINYINT NOT NULL DEFAULT 0,
  retry_count       INT     NOT NULL DEFAULT 0,
  cost_cny          DECIMAL(12,6) NOT NULL DEFAULT 0,
  trace_id          VARCHAR(64),
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------- 决策域（M3） ----------
-- 雷达图版本化（AD-4）：版本一旦生成只读，重算产生新版本
CREATE TABLE IF NOT EXISTS competency_radar (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id          BIGINT      NOT NULL,
  version          INT         NOT NULL,
  target_job_id    BIGINT,
  triggered_by     VARCHAR(24) NOT NULL,          -- ASSESSMENT/SIMULATION/TASK/MANUAL/REPLAN
  formula_version  VARCHAR(16) NOT NULL DEFAULT 'v1',
  calc_input_json  CLOB        NOT NULL,          -- 快照输入：各来源分值与权重（可审计）
  created_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_radar_user_version UNIQUE (user_id, version)
);

CREATE TABLE IF NOT EXISTS radar_dimension_score (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
  radar_id           BIGINT      NOT NULL,
  dimension_key      VARCHAR(32) NOT NULL,
  raw_score          DECIMAL(6,2) NOT NULL,
  weighted_score     DECIMAL(6,2) NOT NULL,
  source_detail_json CLOB
);

CREATE TABLE IF NOT EXISTS career_path (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id       BIGINT    NOT NULL,
  target_job_id BIGINT,
  horizon_years INT       NOT NULL DEFAULT 4,
  status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  prompt_version VARCHAR(24),
  degraded      TINYINT   NOT NULL DEFAULT 0,
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS path_stage (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  path_id     BIGINT      NOT NULL,
  seq         INT         NOT NULL,
  name        VARCHAR(128) NOT NULL,
  start_month INT         NOT NULL,
  end_month   INT         NOT NULL,
  goal        VARCHAR(512),
  skills      VARCHAR(512),
  outputs     VARCHAR(512),
  status      VARCHAR(16) NOT NULL DEFAULT 'PLANNED'
);

CREATE TABLE IF NOT EXISTS gap_analysis (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT    NOT NULL,
  job_id     BIGINT    NOT NULL,
  items_json CLOB      NOT NULL,                 -- Top-N 差距项（按优先级排序）
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------- 生成域（M4） ----------
CREATE TABLE IF NOT EXISTS learning_unit (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  stage_id    BIGINT      NOT NULL,
  user_id     BIGINT      NOT NULL,
  seq         INT         NOT NULL,
  period      VARCHAR(32) NOT NULL,              -- Q1 / M1 / W1
  title       VARCHAR(200) NOT NULL,
  deliverable VARCHAR(300),                      -- 可验证产出物（D-2 反空泛）
  est_hours   DECIMAL(6,1) NOT NULL DEFAULT 6,
  status      VARCHAR(16) NOT NULL DEFAULT 'TODO'
);

CREATE TABLE IF NOT EXISTS resource_recommendation (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT      NOT NULL,
  unit_id    BIGINT,
  title      VARCHAR(200) NOT NULL,
  source     VARCHAR(200) NOT NULL,              -- 每条必须带来源
  url        VARCHAR(300),
  category   VARCHAR(32),
  created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS chat_session (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT      NOT NULL,
  kind       VARCHAR(24) NOT NULL,               -- AGENT / SIMULATION
  title      VARCHAR(200),
  created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS chat_message (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id      BIGINT      NOT NULL,
  role            VARCHAR(16) NOT NULL,          -- user / assistant / system
  content         CLOB        NOT NULL,
  is_ai_generated TINYINT     NOT NULL DEFAULT 0, -- NFR-4 AI 内容标识
  sources_json    CLOB,
  created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------- 执行域（M5） ----------
CREATE TABLE IF NOT EXISTS learning_task (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT      NOT NULL,
  unit_id    BIGINT,
  title      VARCHAR(200) NOT NULL,
  due_date   DATE,
  status     VARCHAR(16) NOT NULL DEFAULT 'TODO', -- TODO / DOING / DONE
  done_at    TIMESTAMP,
  created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS task_checkin (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT    NOT NULL,
  checkin_date DATE      NOT NULL,
  minutes      INT       NOT NULL DEFAULT 0,
  note         VARCHAR(255),
  CONSTRAINT uk_checkin UNIQUE (user_id, checkin_date)
);

CREATE TABLE IF NOT EXISTS simulation_scenario (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  code        VARCHAR(32)  NOT NULL,
  name        VARCHAR(128) NOT NULL,
  description VARCHAR(512),
  role_card   CLOB,                              -- 对手角色卡（注入 System 层）
  opening     CLOB,                              -- 开场白
  difficulty  VARCHAR(16) DEFAULT 'NORMAL',
  CONSTRAINT uk_scenario_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS rubric_dimension (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  scenario_id BIGINT      NOT NULL,
  dim_key     VARCHAR(32) NOT NULL,
  name        VARCHAR(64) NOT NULL,
  weight      DECIMAL(6,3) NOT NULL DEFAULT 1,
  description VARCHAR(300)
);

CREATE TABLE IF NOT EXISTS simulation_session (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id     BIGINT      NOT NULL,
  scenario_id BIGINT      NOT NULL,
  chat_session_id BIGINT,
  status      VARCHAR(16) NOT NULL DEFAULT 'ONGOING',  -- ONGOING / FINISHED
  turn_count  INT         NOT NULL DEFAULT 0,
  started_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ended_at    TIMESTAMP
);

CREATE TABLE IF NOT EXISTS evaluation (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id      BIGINT      NOT NULL,
  user_id         BIGINT      NOT NULL,
  overall_score   DECIMAL(6,2) NOT NULL,
  level           VARCHAR(16),
  summary         VARCHAR(512),
  suggestions_json CLOB,
  prompt_version  VARCHAR(24),
  degraded        TINYINT     NOT NULL DEFAULT 0,
  created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS evaluation_score (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  evaluation_id BIGINT      NOT NULL,
  dim_key       VARCHAR(32) NOT NULL,
  name          VARCHAR(64) NOT NULL,
  score         DECIMAL(6,2) NOT NULL,
  weight        DECIMAL(6,3) NOT NULL DEFAULT 1,
  comment       VARCHAR(300)
);

-- ---------- 反馈域（M6） ----------
CREATE TABLE IF NOT EXISTS growth_record (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT      NOT NULL,
  radar_id     BIGINT      NOT NULL,
  snapshot_json CLOB      NOT NULL,
  remark       VARCHAR(200),
  created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS growth_snapshot_metric (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  record_id   BIGINT      NOT NULL,
  metric_key  VARCHAR(48) NOT NULL,
  metric_value DECIMAL(10,2) NOT NULL
);

CREATE TABLE IF NOT EXISTS incentive_log (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT      NOT NULL,
  type       VARCHAR(32) NOT NULL,               -- STREAK / MILESTONE / ENCOURAGE
  content    VARCHAR(512) NOT NULL,
  created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS deviation_alert (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT      NOT NULL,
  type       VARCHAR(32) NOT NULL,
  message    VARCHAR(512) NOT NULL,
  resolved   TINYINT     NOT NULL DEFAULT 0,
  created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------- 平台与合规域（M7） ----------
CREATE TABLE IF NOT EXISTS trend_report (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id        BIGINT,
  topic          VARCHAR(128) NOT NULL,
  content        CLOB        NOT NULL,
  sources_json   CLOB,                            -- 无来源不外答
  prompt_version VARCHAR(24),
  degraded       TINYINT     NOT NULL DEFAULT 0,
  created_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS trend_citation (
  id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  report_id BIGINT       NOT NULL,
  title     VARCHAR(200) NOT NULL,
  source    VARCHAR(200) NOT NULL,
  url       VARCHAR(300)
);

CREATE TABLE IF NOT EXISTS mcp_tool_log (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  tool_name      VARCHAR(64) NOT NULL,
  args_digest    VARCHAR(300),
  status         VARCHAR(24) NOT NULL,            -- OK / TIMEOUT / INVALID_ARGUMENT / UPSTREAM_UNAVAILABLE
  elapsed_ms     BIGINT      NOT NULL DEFAULT 0,
  result_summary VARCHAR(300),
  created_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai_generation_log (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id        BIGINT,
  scene          VARCHAR(48) NOT NULL,
  provider       VARCHAR(24) NOT NULL,
  model          VARCHAR(64),
  prompt_version VARCHAR(24),
  latency_ms     BIGINT      NOT NULL DEFAULT 0,
  retry_count    INT         NOT NULL DEFAULT 0,
  degraded       TINYINT     NOT NULL DEFAULT 0,
  created_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS audit_log (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT,
  action     VARCHAR(48) NOT NULL,
  target     VARCHAR(200),
  detail     VARCHAR(512),
  ip         VARCHAR(64),
  created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_config (
  cfg_key   VARCHAR(64) PRIMARY KEY,
  cfg_value VARCHAR(255) NOT NULL,
  remark    VARCHAR(200)
);

CREATE TABLE IF NOT EXISTS data_erasure_request (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT      NOT NULL,
  status     VARCHAR(16) NOT NULL DEFAULT 'DONE',
  scope      VARCHAR(200),
  created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------- 常用索引 ----------
CREATE INDEX IF NOT EXISTS idx_radar_user        ON competency_radar(user_id, version);
CREATE INDEX IF NOT EXISTS idx_rdscore_radar     ON radar_dimension_score(radar_id);
CREATE INDEX IF NOT EXISTS idx_path_user         ON career_path(user_id);
CREATE INDEX IF NOT EXISTS idx_stage_path        ON path_stage(path_id, seq);
CREATE INDEX IF NOT EXISTS idx_unit_stage        ON learning_unit(stage_id, seq);
CREATE INDEX IF NOT EXISTS idx_task_user         ON learning_task(user_id, status);
CREATE INDEX IF NOT EXISTS idx_msg_session       ON chat_message(session_id);
CREATE INDEX IF NOT EXISTS idx_kb_chunk_doc      ON kb_chunk(doc_id, chunk_no);
CREATE INDEX IF NOT EXISTS idx_kb_retr_created   ON kb_retrieval_log(created_at);
CREATE INDEX IF NOT EXISTS idx_llm_log_created   ON llm_call_log(created_at);
CREATE INDEX IF NOT EXISTS idx_llm_log_scene     ON llm_call_log(scene, created_at);
CREATE INDEX IF NOT EXISTS idx_eval_user         ON evaluation(user_id);
CREATE INDEX IF NOT EXISTS idx_kg_rel_subject    ON kg_relation(subject_id, predicate);
CREATE INDEX IF NOT EXISTS idx_kg_rel_object     ON kg_relation(object_id, predicate);
CREATE INDEX IF NOT EXISTS idx_mcp_created       ON mcp_tool_log(created_at);
CREATE INDEX IF NOT EXISTS idx_aigen_user        ON ai_generation_log(user_id, created_at);
