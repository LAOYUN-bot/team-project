package cn.zjnu.a02.platform.security;

import java.util.Arrays;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import cn.zjnu.a02.common.api.BizException;
import cn.zjnu.a02.common.api.ErrorCode;

/**
 * 鉴权拦截器：解析 JWT → 写入 {@link AuthContext} → 校验 {@link RequireRole}。
 *
 * <p>拦截 {@code /api/**}，放行 {@code /api/auth/**}、静态资源与健康检查。
 * 令牌来源优先取 {@code Authorization: Bearer}，其次取 Cookie（架构文档 §9.2 AD-7）。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtService jwtService;

    public AuthInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 必须先清空：SSE 等异步请求走的是 afterConcurrentHandlingStarted，
        // 不会触发 afterCompletion，若不清空会把上一个请求的登录态泄漏给同一线程上的后续请求。
        AuthContext.clear();

        String token = resolveToken(request);
        JwtService.Claims claims = jwtService.parse(token);
        if (claims != null) {
            AuthContext.set(new AuthContext.CurrentUser(claims.userId(), claims.username(), claims.role()));
        }

        if (handler instanceof HandlerMethod method) {
            RequireRole requireRole = method.getMethodAnnotation(RequireRole.class);
            if (requireRole == null) {
                requireRole = method.getBeanType().getAnnotation(RequireRole.class);
            }
            if (requireRole != null) {
                AuthContext.CurrentUser user = AuthContext.get();
                if (user == null) {
                    throw new BizException(ErrorCode.UNAUTHENTICATED);
                }
                boolean allowed = Arrays.stream(requireRole.value())
                        .anyMatch(role -> role.equalsIgnoreCase(user.role()));
                if (!allowed) {
                    throw new BizException(ErrorCode.FORBIDDEN);
                }
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AuthContext.clear();
    }

    public static String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if ("A02_TOKEN".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
