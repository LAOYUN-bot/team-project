package cn.zjnu.a02.m1perception.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户基础信息实体（对应表 {@code user_profile}）。
 *
 * <p>{@code phoneEnc} / {@code emailEnc} 存放 AES-GCM 密文（NFR-3），
 * 实体层不负责加解密，由 {@code platform.privacy.FieldEncryptor} 在 Service 层处理，
 * 保证「加密是业务决策，不是数据库细节」。
 */
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
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

}
