package cn.zjnu.a02.m1perception.entity;

import java.time.LocalDateTime;

/**
 * 用户基础信息实体（对应表 {@code user_profile}）。
 *
 * <p>{@code phoneEnc} / {@code emailEnc} 存放 AES-GCM 密文（NFR-3），
 * 实体层不负责加解密，由 {@code platform.privacy.FieldEncryptor} 在 Service 层处理，
 * 保证「加密是业务决策，不是数据库细节」。
 */
public class UserProfile {

    private Long id;
    private Long userId;
    private String education;
    private String major;
    private String grade;
    private Integer workYears;
    private String currentJob;
    private Long targetJobId;
    private String interestTags;
    private Integer weeklyHours;
    private String phoneEnc;
    private String emailEnc;
    private LocalDateTime updatedAt;

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

    public String getEducation() {
        return education;
    }

    public void setEducation(String education) {
        this.education = education;
    }

    public String getMajor() {
        return major;
    }

    public void setMajor(String major) {
        this.major = major;
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }

    public Integer getWorkYears() {
        return workYears;
    }

    public void setWorkYears(Integer workYears) {
        this.workYears = workYears;
    }

    public String getCurrentJob() {
        return currentJob;
    }

    public void setCurrentJob(String currentJob) {
        this.currentJob = currentJob;
    }

    public Long getTargetJobId() {
        return targetJobId;
    }

    public void setTargetJobId(Long targetJobId) {
        this.targetJobId = targetJobId;
    }

    public String getInterestTags() {
        return interestTags;
    }

    public void setInterestTags(String interestTags) {
        this.interestTags = interestTags;
    }

    public Integer getWeeklyHours() {
        return weeklyHours;
    }

    public void setWeeklyHours(Integer weeklyHours) {
        this.weeklyHours = weeklyHours;
    }

    public String getPhoneEnc() {
        return phoneEnc;
    }

    public void setPhoneEnc(String phoneEnc) {
        this.phoneEnc = phoneEnc;
    }

    public String getEmailEnc() {
        return emailEnc;
    }

    public void setEmailEnc(String emailEnc) {
        this.emailEnc = emailEnc;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
