package cn.zjnu.a02.m3decision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import cn.zjnu.a02.common.db.Db;
import cn.zjnu.a02.common.event.Events;
import cn.zjnu.a02.m1perception.PerceptionService;
import cn.zjnu.a02.m2cognition.CognitionService;

/**
 * M3 决策层的闭环接线（架构文档 §3.1 关键闭环约束）。
 *
 * <p>把「感知 → 决策 → 认知」与「执行 → 决策」两条链路接上：
 * <ul>
 *   <li>测评完成 → 生成雷达 v1（ASSESSMENT）→ 触发差距分析；</li>
 *   <li>训练评分完成 → 生成雷达 v(n+1)（SIMULATION）→ 重算差距 —— <b>这就是 D-3 画像动态迭代的物证</b>；</li>
 *   <li>任务状态变化 → 生成新版本（TASK），带 1 小时节流，避免版本爆炸。</li>
 * </ul>
 */
@Component
public class DecisionEventListener {

    private static final Logger log = LoggerFactory.getLogger(DecisionEventListener.class);

    private final RadarService radarService;
    private final CognitionService cognition;
    private final PerceptionService perception;
    private final Db db;

    public DecisionEventListener(RadarService radarService, CognitionService cognition,
                                 PerceptionService perception, Db db) {
        this.radarService = radarService;
        this.cognition = cognition;
        this.perception = perception;
        this.db = db;
    }

    @EventListener
    @Transactional
    public void onAssessmentSubmitted(Events.AssessmentSubmitted event) {
        try {
            radarService.ensureInitialRadar(event.userId(), event.targetJobId());
            if (event.targetJobId() != null) {
                cognition.calculateAndSave(event.userId(), event.targetJobId());
            }
            log.info("测评完成 → 已生成初始画像与差距分析 userId={}", event.userId());
        } catch (Exception e) {
            // 闭环中的画像生成失败不能导致测评本身失败
            log.warn("生成初始画像失败 userId={} err={}", event.userId(), e.getMessage());
        }
    }

    @EventListener
    @Transactional
    public void onEvaluationCompleted(Events.EvaluationCompleted event) {
        try {
            Long jobId = perception.targetJobId(event.userId());
            radarService.createVersion(event.userId(), "SIMULATION", jobId);
            if (jobId != null) {
                cognition.calculateAndSave(event.userId(), jobId);
            }
            log.info("训练评分完成 → 已生成新画像版本 userId={} score={}", event.userId(), event.overallScore());
        } catch (Exception e) {
            log.warn("训练后重算画像失败 userId={} err={}", event.userId(), e.getMessage());
        }
    }

    @EventListener
    @Transactional
    public void onTaskChanged(Events.TaskChanged event) {
        try {
            // 节流：1 小时内已因任务变化重算过则跳过（截止时间在 Java 侧计算，保持 SQL 方言无关）
            java.sql.Timestamp cutoff = java.sql.Timestamp.valueOf(
                    java.time.LocalDateTime.now().minusHours(1));
            long recent = db.count("SELECT COUNT(*) FROM competency_radar WHERE user_id=?"
                    + " AND triggered_by='TASK' AND created_at > ?", event.userId(), cutoff);
            if (recent > 0) {
                return; // 节流：1 小时内已因任务变化重算过
            }
            Long jobId = perception.targetJobId(event.userId());
            radarService.createVersion(event.userId(), "TASK", jobId);
            if (jobId != null) {
                cognition.calculateAndSave(event.userId(), jobId);
            }
        } catch (Exception e) {
            log.warn("任务变化后重算画像失败 userId={} err={}", event.userId(), e.getMessage());
        }
    }
}
