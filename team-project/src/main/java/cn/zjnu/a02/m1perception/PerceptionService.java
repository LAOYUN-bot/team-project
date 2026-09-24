package cn.zjnu.a02.m1perception;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.zjnu.a02.common.api.BizException;
import cn.zjnu.a02.common.api.ErrorCode;
import cn.zjnu.a02.common.db.Db;
import cn.zjnu.a02.common.event.Events;
import cn.zjnu.a02.common.util.JsonUtils;
import cn.zjnu.a02.m1perception.entity.User;
import cn.zjnu.a02.m1perception.entity.UserProfile;
import cn.zjnu.a02.m1perception.mapper.UserMapper;
import cn.zjnu.a02.m1perception.mapper.UserProfileMapper;
import cn.zjnu.a02.platform.audit.AuditService;
import cn.zjnu.a02.platform.security.JwtService;

/**
 * M1 感知层 —— 身份、基础信息、能力测评（FR-1.1 ~ FR-1.5）。
 *
 * <p>闭环职责：把用户的原始输入转化为**结构化、可计算**的能力初始向量，
 * 并广播事件交给 M3 决策层生成画像。
 */
@Service
public class PerceptionService {

    /** 8 个能力维度的定义（与雷达图一致，架构文档 §6.5）。 */
    public static final List<Map<String, String>> RADAR_DIMENSIONS = List.of(
            Map.of("key", "PRO_SKILL", "label", "专业技能", "hint", "岗位核心技术栈的掌握程度"),
            Map.of("key", "GEN_ABILITY", "label", "通用能力", "hint", "问题拆解、逻辑推理与学习迁移"),
            Map.of("key", "INDUSTRY_KNOW", "label", "行业认知", "hint", "对行业趋势、岗位职责的理解"),
            Map.of("key", "PROJECT_EXP", "label", "项目经验", "hint", "完整项目的参与深度与复杂度"),
            Map.of("key", "LEARNING_ABILITY", "label", "学习力", "hint", "持续学习与自我驱动的稳定性"),
            Map.of("key", "COMMUNICATION", "label", "沟通表达", "hint", "表达结构性与跨角色沟通效果"),
            Map.of("key", "TOOL_USAGE", "label", "工具使用", "hint", "开发、协作、AI 工具的使用熟练度"),
            Map.of("key", "COLLABORATION", "label", "协作意识", "hint", "团队配合、冲突处理与共情能力"));

    /** 测评题库：每维度 2 题，5 级量表（FR-1.3）。 */
    public static final List<Map<String, Object>> QUESTIONS = List.of(
            question(1, "PRO_SKILL", "我能独立完成目标岗位要求的核心开发/分析任务，而不需要他人逐步指导。"),
            question(2, "PRO_SKILL", "我能读懂并修改一个中等规模的开源项目代码/数据集。"),
            question(3, "GEN_ABILITY", "遇到陌生问题时，我能把它拆成几个可验证的小问题。"),
            question(4, "GEN_ABILITY", "我能把一个复杂结论用三句话讲清楚。"),
            question(5, "INDUSTRY_KNOW", "我说得出目标岗位的日常工作内容与主要考核指标。"),
            question(6, "INDUSTRY_KNOW", "我持续关注目标行业的用人需求变化。"),
            question(7, "PROJECT_EXP", "我参与过至少一个从需求到交付的完整项目。"),
            question(8, "PROJECT_EXP", "我能讲清楚自己项目里的技术选型理由与踩过的坑。"),
            question(9, "LEARNING_ABILITY", "我能保持每周固定的学习时间，并连续坚持 4 周以上。"),
            question(10, "LEARNING_ABILITY", "我会为学到的知识产出笔记或可展示的作品。"),
            question(11, "COMMUNICATION", "在讨论分歧时，我能先复述对方观点再表达自己的看法。"),
            question(12, "COMMUNICATION", "我能用书面形式把一件事说清楚（如周报、方案）。"),
            question(13, "TOOL_USAGE", "我熟练使用版本控制、IDE、调试等基础开发工具。"),
            question(14, "TOOL_USAGE", "我会用 AI 工具提升效率，并能核查它的输出。"),
            question(15, "COLLABORATION", "我在团队中主动同步进度，不让别人来催。"),
            question(16, "COLLABORATION", "遇到冲突时，我能对事不对人地推进解决。"));

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{4,32}$");

    private final Db db;
    private final UserMapper userMapper;
    private final UserProfileMapper userProfileMapper;
    private final JwtService jwtService;
    private final AuditService audit;
    private final ApplicationEventPublisher publisher;
    private final JsonUtils json;
    private final cn.zjnu.a02.ai.AiService aiService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);

    public PerceptionService(Db db, UserMapper userMapper, UserProfileMapper userProfileMapper,
                             JwtService jwtService, AuditService audit,
                             ApplicationEventPublisher publisher, JsonUtils json,
                             cn.zjnu.a02.ai.AiService aiService) {
        this.db = db;
        this.userMapper = userMapper;
        this.userProfileMapper = userProfileMapper;
        this.jwtService = jwtService;
        this.audit = audit;
        this.publisher = publisher;
        this.json = json;
        this.aiService = aiService;
    }

    private static Map<String, Object> question(int no, String dim, String text) {
        return Map.of("no", no, "dimension", dim, "text", text, "scale", "1-5");
    }

    // ------------------------------------------------------------------
    // 认证（FR-1.1）
    // ------------------------------------------------------------------

    @Transactional
    public Map<String, Object> register(String username, String password, String nickname) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "用户名需为 4-32 位字母、数字或下划线");
        }
        validatePassword(password);
        if (userMapper.countByUsername(username) > 0) {
            throw new BizException(ErrorCode.USERNAME_TAKEN);
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setNickname(nickname == null || nickname.isBlank() ? username : nickname);
        user.setRole("USER");
        userMapper.insert(user);
        long userId = user.getId();

        db.update("INSERT INTO user_consent(user_id, privacy_agreed, ai_notice_agreed) VALUES (?,0,0)", userId);
        audit.record(userId, "REGISTER", "sys_user#" + userId, "新用户注册");
        return Map.of("userId", userId, "username", username,
                "token", jwtService.issue(userId, username, "USER"));
    }

    public Map<String, Object> login(String username, String password) {
        User user = userMapper.selectByUsername(username);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            audit.record(user == null ? null : user.getId(),
                    "LOGIN_FAIL", "sys_user", "账号或密码错误");
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        }
        long userId = user.getId();
        String token = jwtService.issue(userId, username, user.getRole());
        audit.record(userId, "LOGIN", "sys_user#" + userId, "登录成功");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("username", username);
        result.put("nickname", user.getNickname());
        result.put("role", user.getRole());
        result.put("token", token);
        result.put("profileComplete", isProfileComplete(userId));
        return result;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "密码至少 8 位");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "密码需同时包含字母与数字");
        }
    }

    // ------------------------------------------------------------------
    // 基础信息（FR-1.2）
    // ------------------------------------------------------------------

    @Transactional
    public Map<String, Object> saveProfile(long userId, ProfileInput input) {
        if (input.education() == null || input.education().isBlank()) {
            throw new BizException(ErrorCode.MISSING_FIELD, "学历为必填项");
        }
        if (input.targetJobId() == null) {
            throw new BizException(ErrorCode.MISSING_FIELD, "目标岗位为必填项");
        }
        if (input.weeklyHours() == null || input.weeklyHours() < 1 || input.weeklyHours() > 60) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "每周可用学习时长需在 1-60 小时之间");
        }
        Map<String, Object> job = db.one("SELECT id, name FROM kg_entity WHERE id=? AND type='JOB'",
                input.targetJobId());
        if (job == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "目标岗位不存在");
        }

        boolean exists = userProfileMapper.countByUserId(userId) > 0;
        UserProfile profile = new UserProfile();
        profile.setUserId(userId);
        profile.setEducation(input.education());
        profile.setMajor(input.major());
        profile.setGrade(input.grade());
        profile.setWorkYears(input.workYears() == null ? 0 : input.workYears());
        profile.setCurrentJob(input.currentJob());
        profile.setTargetJobId(input.targetJobId());
        profile.setInterestTags(input.interestTags());
        profile.setWeeklyHours(input.weeklyHours());
        if (exists) {
            userProfileMapper.update(profile);
        } else {
            userProfileMapper.insert(profile);
        }
        db.update("INSERT INTO behavior_event(user_id, event_type, payload) VALUES (?,?,?)",
                userId, "PROFILE_SAVED", "targetJobId=" + input.targetJobId());
        audit.record(userId, "PROFILE_SAVE", "user_profile", "更新基础信息，目标岗位=" + job.get("name"));
        return getProfile(userId);
    }

    /**
     * 读取画像。
     *
     * <p>安全约定：手机号 / 邮箱以 AES-GCM 密文入库（{@code phone_enc} / {@code email_enc}），
     * 此处**逐字段组装返回视图**，密文字段不会出现在响应里——比 {@code SELECT *} 后删字段更可靠。
     */
    public Map<String, Object> getProfile(long userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("complete", isProfileComplete(userId));

        UserProfile profile = userProfileMapper.selectByUserId(userId);
        if (profile == null) {
            result.put("profile", null);
            return result;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("userId", profile.getUserId());
        view.put("education", profile.getEducation());
        view.put("major", profile.getMajor());
        view.put("grade", profile.getGrade());
        view.put("workYears", profile.getWorkYears());
        view.put("currentJob", profile.getCurrentJob());
        view.put("targetJobId", profile.getTargetJobId());
        view.put("interestTags", profile.getInterestTags());
        view.put("weeklyHours", profile.getWeeklyHours());
        view.put("updatedAt", profile.getUpdatedAt());
        if (profile.getTargetJobId() != null) {
            Map<String, Object> job = db.one("SELECT name, code FROM kg_entity WHERE id=?",
                    profile.getTargetJobId());
            if (job != null) {
                view.put("targetJobName", job.get("name"));
                view.put("targetJobCode", job.get("code"));
            }
        }
        result.put("profile", view);
        return result;
    }

    public boolean isProfileComplete(long userId) {
        UserProfile profile = userProfileMapper.selectByUserId(userId);
        if (profile == null || profile.getEducation() == null || profile.getTargetJobId() == null) {
            return false;
        }
        boolean hasAssessment = db.count("SELECT COUNT(*) FROM assessment_record WHERE user_id=?", userId) > 0;
        return hasAssessment;
    }

    public Map<String, Object> saveConsent(long userId, boolean privacyAgreed, boolean aiNoticeAgreed) {
        if (db.count("SELECT COUNT(*) FROM user_consent WHERE user_id=?", userId) == 0) {
            db.update("INSERT INTO user_consent(user_id, privacy_agreed, ai_notice_agreed, agreed_at)"
                    + " VALUES (?,?,?,CURRENT_TIMESTAMP)", userId,
                    privacyAgreed ? 1 : 0, aiNoticeAgreed ? 1 : 0);
        } else {
            db.update("UPDATE user_consent SET privacy_agreed=?, ai_notice_agreed=?,"
                            + " agreed_at=CASE WHEN ?=1 THEN CURRENT_TIMESTAMP ELSE agreed_at END,"
                            + " revoked_at=CASE WHEN ?=0 THEN CURRENT_TIMESTAMP ELSE NULL END"
                            + " WHERE user_id=?",
                    privacyAgreed ? 1 : 0, aiNoticeAgreed ? 1 : 0,
                    privacyAgreed ? 1 : 0, privacyAgreed ? 1 : 0, userId);
        }
        audit.record(userId, "CONSENT_UPDATE", "user_consent",
                "隐私授权=" + privacyAgreed + ", AI 告知=" + aiNoticeAgreed);
        return getConsent(userId);
    }

    public Map<String, Object> getConsent(long userId) {
        Map<String, Object> row = db.one("SELECT * FROM user_consent WHERE user_id=?", userId);
        return row == null ? Map.of("privacyAgreed", false, "aiNoticeAgreed", false) : db.camel(row);
    }

    // ------------------------------------------------------------------
    // 能力测评（FR-1.3）
    // ------------------------------------------------------------------

    public List<Map<String, Object>> questions() {
        return QUESTIONS;
    }

    /**
     * 提交测评：把 5 级量表答案折算为 8 维能力分值（0-100），并广播事件触发画像生成。
     */
    @Transactional
    public Map<String, Object> submitAssessment(long userId, List<AnswerInput> answers) {
        if (answers == null || answers.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "测评答案不能为空");
        }
        Map<String, Integer> sums = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AnswerInput a : answers) {
            String dim = dimensionOf(a.no());
            if (dim == null) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "题号不存在: " + a.no());
            }
            int v = a.value() == null ? 0 : a.value();
            if (v < 1 || v > 5) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "第 " + a.no() + " 题答案需在 1-5 之间");
            }
            sums.merge(dim, v, Integer::sum);
            counts.merge(dim, 1, Integer::sum);
        }

        Map<String, Double> scores = new LinkedHashMap<>();
        for (Map<String, String> dim : RADAR_DIMENSIONS) {
            String key = dim.get("key");
            int sum = sums.getOrDefault(key, 0);
            int count = counts.getOrDefault(key, 0);
            // 5 级量表 → 0-100：缺失作答的维度按中间值 60 处理，避免"未答即 0 分"
            scores.put(key, count == 0 ? 60.0 : Math.round(sum * 100.0 / (count * 5.0) * 10) / 10.0);
        }

        long recordId = db.insert("INSERT INTO assessment_record(user_id, score_json, scale) VALUES (?,?,?)",
                userId, json.stringify(scores), "LEVEL5");
        for (AnswerInput a : answers) {
            db.insert("INSERT INTO assessment_answer(record_id, question_no, dimension, answer) VALUES (?,?,?,?)",
                    recordId, a.no(), dimensionOf(a.no()), a.value());
        }
        db.update("INSERT INTO behavior_event(user_id, event_type, payload) VALUES (?,?,?)",
                userId, "ASSESSMENT_SUBMITTED", "recordId=" + recordId);
        audit.record(userId, "ASSESSMENT_SUBMIT", "assessment_record#" + recordId, "提交能力测评");

        Long targetJobId = targetJobId(userId);
        publisher.publishEvent(new Events.AssessmentSubmitted(userId, recordId, targetJobId));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordId", recordId);
        result.put("scores", scores);
        result.put("profileComplete", isProfileComplete(userId));
        return result;
    }

    public Map<String, Object> latestAssessment(long userId) {
        Map<String, Object> row = db.one(
                "SELECT id, score_json, created_at FROM assessment_record WHERE user_id=? ORDER BY id DESC LIMIT 1",
                userId);
        if (row == null) {
            return Map.of("exists", false);
        }
        return Map.of("exists", true, "recordId", row.get("id"),
                "scores", json.parseMap(String.valueOf(row.get("score_json"))),
                "createdAt", String.valueOf(row.get("created_at")));
    }

    public Long targetJobId(long userId) {
        UserProfile profile = userProfileMapper.selectByUserId(userId);
        return profile == null ? null : profile.getTargetJobId();
    }

    public Map<String, Object> userContext(long userId) {
        Map<String, Object> row = db.one("SELECT u.nickname, p.education, p.major, p.grade, p.work_years,"
                + " p.weekly_hours, p.interest_tags, p.target_job_id, j.name AS target_job_name"
                + " FROM sys_user u LEFT JOIN user_profile p ON p.user_id=u.id"
                + " LEFT JOIN kg_entity j ON j.id=p.target_job_id WHERE u.id=?", userId);
        return row == null ? Map.of() : db.camel(row);
    }

    private static String dimensionOf(int no) {
        for (Map<String, Object> q : QUESTIONS) {
            if (((Number) q.get("no")).intValue() == no) {
                return String.valueOf(q.get("dimension"));
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 简历/经历抽取（FR-1.4）
    // ------------------------------------------------------------------

    @Transactional
    public Map<String, Object> extractResumeTags(long userId, String text) {
        if (text == null || text.isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "请粘贴简历或项目经历文本");
        }
        if (text.length() > 8000) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "文本过长，请控制在 8000 字以内");
        }
        var result = aiService.structured("resume", userId, Map.of("text", text), null);
        Object tags = result.data().getOrDefault("tags", List.of());
        if (tags instanceof List<?> list) {
            for (Object tag : list) {
                db.insert("INSERT INTO resume_extract_tag(user_id, tag, category, confirmed) VALUES (?,?,?,0)",
                        userId, String.valueOf(tag), "SKILL");
            }
        }
        return Map.of("tags", tags,
                "degraded", result.degraded(),
                "note", "AI 抽取结果需你确认后才会写入画像（合规要求：不直连外部简历库）");
    }

    public List<Map<String, Object>> resumeTags(long userId) {
        return db.camelList("SELECT id, tag, category, confirmed FROM resume_extract_tag"
                + " WHERE user_id=? ORDER BY id", userId);
    }

    @Transactional
    public Map<String, Object> confirmResumeTags(long userId, List<String> tags) {
        db.update("DELETE FROM resume_extract_tag WHERE user_id=?", userId);
        if (tags != null) {
            for (String tag : tags) {
                if (tag != null && !tag.isBlank()) {
                    db.insert("INSERT INTO resume_extract_tag(user_id, tag, category, confirmed) VALUES (?,?,?,1)",
                            userId, tag.trim(), "SKILL");
                }
            }
        }
        audit.record(userId, "RESUME_TAGS_CONFIRM", "resume_extract_tag", "确认技能标签 " + (tags == null ? 0 : tags.size()) + " 个");
        return Map.of("confirmed", tags == null ? 0 : tags.size());
    }

    // ------------------------------------------------------------------
    // 入参 DTO
    // ------------------------------------------------------------------

    public record ProfileInput(String education, String major, String grade, Integer workYears,
                               String currentJob, Long targetJobId, String interestTags,
                               Integer weeklyHours) {
    }

    public record AnswerInput(Integer no, Integer value) {
    }
}
