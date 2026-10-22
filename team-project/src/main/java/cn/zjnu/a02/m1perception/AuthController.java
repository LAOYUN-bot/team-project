package cn.zjnu.a02.m1perception;

import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.zjnu.a02.common.api.ApiResponse;
import cn.zjnu.a02.platform.audit.AuditService;
import cn.zjnu.a02.platform.security.AuthContext;

/**
 * 认证接口（FR-1.1）。
 *
 * <p>{@code /api/auth/**} 不要求令牌。令牌同时以两种方式下发：
 * <ul>
 *   <li><b>HttpOnly + SameSite=Lax Cookie</b>（架构文档 AD-7，前端不接触令牌，防 XSS 窃取）——
 *       Web 前端走这条通道；</li>
 *   <li><b>响应体中的 token</b>——供接口调试与自动化测试使用（这类客户端没有 Cookie 语义）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** 与 AuthInterceptor 读取的 Cookie 名保持一致。 */
    private static final String COOKIE_NAME = "A02_TOKEN";

    private final PerceptionService perception;
    private final AuditService audit;
    private final long ttlMinutes;

    public AuthController(PerceptionService perception, AuditService audit,
                          @Value("${a02.security.jwt.access-ttl-minutes:120}") long ttlMinutes) {
        this.perception = perception;
        this.audit = audit;
        this.ttlMinutes = ttlMinutes;
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody RegisterRequest request,
                                                     HttpServletResponse response) {
        Map<String, Object> result = perception.register(
                request.username(), request.password(), request.nickname());
        setTokenCookie(response, String.valueOf(result.get("token")));
        return ApiResponse.ok(result);
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody LoginRequest request,
                                                  HttpServletResponse response) {
        Map<String, Object> result = perception.login(request.username(), request.password());
        setTokenCookie(response, String.valueOf(result.get("token")));
        return ApiResponse.ok(result);
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout(HttpServletResponse response) {
        var user = AuthContext.get();
        if (user != null) {
            audit.record(user.userId(), "LOGOUT", "sys_user#" + user.userId(), "登出");
        }
        // 立即过期（Max-Age=0），令牌本身由服务端黑名单/短 TTL 兜底
        response.addHeader("Set-Cookie", COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
        return ApiResponse.ok(Map.of("loggedOut", true));
    }

    private void setTokenCookie(HttpServletResponse response, String token) {
        long maxAge = ttlMinutes * 60;
        response.addHeader("Set-Cookie", COOKIE_NAME + "=" + token + "; Path=/; Max-Age=" + maxAge
                + "; HttpOnly; SameSite=Lax");
    }

    public record RegisterRequest(String username, String password, String nickname) {
    }

    public record LoginRequest(String username, String password) {
    }
}
