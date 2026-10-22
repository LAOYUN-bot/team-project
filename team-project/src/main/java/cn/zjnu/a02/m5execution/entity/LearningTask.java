package cn.zjnu.a02.m5execution.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 学习任务实体（对应表 {@code learning_task}）。
 *
 * <p>课程实验一与本项目需求文档 §4.2 都点名「用户表、任务表 CRUD」，
 * 因此任务表是第二个用 MyBatis Mapper 承载的实体：
 * {@code LearningTaskMapper} + {@code resources/mapper/LearningTaskMapper.xml}。
 *
 * <p>状态机：{@code TODO → DOING → DONE}，变更后需要落 {@code behavior_event}
 * 并广播 {@code Events.TaskChanged} 触发画像重算（闭环 ⑤→③）。
 */
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class LearningTask {

    private Long id;
    private Long userId;
    private Long unitId;
    private String title;
    private LocalDate dueDate;
    private String status;
    private LocalDateTime doneAt;
    private LocalDateTime createdAt;
    /** 关联查询字段（非本表列）：所属学习单元标题 */
    private String unitTitle;
    /** 关联查询字段（非本表列）：所属职业路径阶段名 */
    private String stageName;

    public boolean isDone() {
        return "DONE".equalsIgnoreCase(status);
    }

}
