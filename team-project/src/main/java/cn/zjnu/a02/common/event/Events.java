package cn.zjnu.a02.common.event;

/**
 * 模块间领域事件（架构文档 §2.3：业务模块之间允许通过接口依赖，不允许通过 Mapper 依赖）。
 *
 * <p>用事件而不是直接调用来打通闭环，好处是依赖方向单向：
 * <pre>
 *   M1 感知 --AssessmentSubmitted--> M3 决策（生成雷达 v1）--调用--> M2 认知（差距分析）
 *   M5 执行 --EvaluationCompleted--> M3 决策（生成雷达 v(n+1)，D-3 画像动态迭代）
 *                                  --> M6 反馈（写成长快照）
 *   M5 执行 --TaskChanged---------> M3 决策（按任务完成情况重算学习力维度）
 * </pre>
 */
public final class Events {

    private Events() {
    }

    /** 完成能力测评（FR-1.3）：触发初始画像与差距分析。 */
    public record AssessmentSubmitted(long userId, long assessmentId, Long targetJobId) {
    }

    /** 模拟训练评分完成（FR-5.3）：触发画像重算（D-3 的物证）。 */
    public record EvaluationCompleted(long userId, long evaluationId, String scenarioCode, double overallScore) {
    }

    /** 学习任务状态变化（FR-5.1）。 */
    public record TaskChanged(long userId, String reason) {
    }

    /** 生成了新的画像版本（AD-4）：由 M6 反馈层写入成长快照。 */
    public record RadarVersionCreated(long userId, long radarId, int version, String triggeredBy) {
    }

    /** 生成了职业路径（FR-3.2）：由 M4 生成层据此拆解学习单元。 */
    public record PathGenerated(long userId, long pathId) {
    }
}
