package cn.zjnu.a02.m5execution.entity;

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

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getUnitId() {
        return unitId;
    }

    public void setUnitId(Long unitId) {
        this.unitId = unitId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getDoneAt() {
        return doneAt;
    }

    public void setDoneAt(LocalDateTime doneAt) {
        this.doneAt = doneAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getUnitTitle() {
        return unitTitle;
    }

    public void setUnitTitle(String unitTitle) {
        this.unitTitle = unitTitle;
    }

    public String getStageName() {
        return stageName;
    }

    public void setStageName(String stageName) {
        this.stageName = stageName;
    }
}
