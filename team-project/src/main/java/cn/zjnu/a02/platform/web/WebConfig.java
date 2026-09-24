package cn.zjnu.a02.platform.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import cn.zjnu.a02.platform.security.AuthInterceptor;

/** Web 配置：注册鉴权拦截器、CORS 白名单（架构文档 §9.1 L1）。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 用户侧接口走 JWT；/internal/v1/** 由 MCP 控制器用独立 Service Token 校验（AD-8 权限最小化）
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // 注册/登录与健康检查不需要令牌
                .excludePathPatterns("/api/auth/**", "/api/public/**", "/api/health");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Demo 前后端同源（前端静态资源由本服务托管）；此处仅为本地分离调试预留
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
