package com.coding.auth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * {@code /internal/**} 的共享密钥校验：只有 platform-api 能调（spec §4.6）。
 *
 * <p>与 platform-api 的 {@code AlertWebhookTokenCheck} 同一套路：密钥没配就拒启（一个"忘了配就等于全开"的
 * 内部端点不该存在），比较用常量时间。nginx 另把 {@code /auth/internal/} 挡成 404，这里是第二道。
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Platform-Internal-Token";

    private static final String PREFIX = "/internal/";

    private final String token;

    public InternalTokenFilter(String token) {
        this.token = token;
    }

    @Override
    protected void initFilterBean() {
        if (!StringUtils.hasText(token)) {
            throw new IllegalStateException("platform.internal.token（env PLATFORM_INTERNAL_TOKEN）未配置："
                    + "客户端管理内部接口不能无密钥开放，拒绝启动。须与 platform-api 的 platform.auth.internal-token 同值");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        if (given == null || !MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"code\":401,\"msg\":\"内部接口凭据无效\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
