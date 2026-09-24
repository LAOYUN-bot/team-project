package cn.zjnu.a02.platform.security;

import cn.zjnu.a02.common.api.BizException;
import cn.zjnu.a02.common.api.ErrorCode;

/**
 * 当前登录用户上下文（ThreadLocal）。
 *
 * <p>由 {@link AuthInterceptor} 在请求进入时写入、结束时清理。
 * 业务层通过 {@link #require()} 获取，避免在 Service 签名里到处传递 userId。
 */
public final class AuthContext {

    public record CurrentUser(long userId, String username, String role) {
        public boolean isAdmin() {
            return "ADMIN".equalsIgnoreCase(role);
        }
    }

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static CurrentUser require() {
        CurrentUser user = HOLDER.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        return user;
    }

    public static long userId() {
        return require().userId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
