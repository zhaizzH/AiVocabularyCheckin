package com.vocab.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册鉴权拦截器 + 管理端跨域。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;

    /**
     * 允许跨域的来源。
     *
     * <p>默认值是管理端 Vite dev server（design.md §5.4），
     * 生产环境通过 {@code CORS_ALLOWED_ORIGINS} 环境变量覆盖。
     * 不从代码里写死，否则部署时得改代码。
     */
    private final String[] allowedOrigins;

    public WebMvcConfig(JwtInterceptor jwtInterceptor,
                        @Value("${cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
                        String allowedOriginsCsv) {
        this.jwtInterceptor = jwtInterceptor;
        this.allowedOrigins = allowedOriginsCsv.split(",");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                // 拦截全部 /api/**；公开路径由拦截器内部白名单放行，
                // 好处是白名单集中在 JwtInterceptor 一处，不会两处漂移。
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/error",
                        "/doc.html",
                        "/swagger-ui/**",
                        "/v3/api-docs/**"
                );
    }

    /**
     * 跨域配置。
     *
     * <p>小程序端不发 CORS 请求（走微信 wx.request），此配置只服务管理端浏览器。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Authorization")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
