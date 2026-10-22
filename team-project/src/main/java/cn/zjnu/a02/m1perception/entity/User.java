package cn.zjnu.a02.m1perception.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
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

    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }
}
