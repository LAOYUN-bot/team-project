package cn.zjnu.a02.m1perception.entity;

import java.time.LocalDateTime;

/**
 * 用户实体（对应表 {@code sys_user}）。
 *
 * <p>课程实验一要求：Entity / Mapper / Service / Controller 四层分层。
 * 本类即「Entity 层」，由 {@code UserMapper} + {@code resources/mapper/UserMapper.xml} 完成持久化。
 *
 * <p>注意：{@code passwordHash} 在数据库中为 BCrypt 密文，**任何情况下不得返回给前端**，
 * 接口层只输出 {@code userId / username / nickname / role}。
 */
public class User {

    private Long id;
    private String username;
    private String passwordHash;
    private String nickname;
    private String role;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }

    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }
}
