package cn.zjnu.a02.platform.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明接口所需角色（RBAC，架构文档 §9.1 L2）。
 *
 * <p>标注在 Controller 方法或类上；由 {@link AuthInterceptor} 统一校验，
 * 不满足返回 403 与业务码 20003。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequireRole {
    String[] value() default {"ADMIN"};
}
