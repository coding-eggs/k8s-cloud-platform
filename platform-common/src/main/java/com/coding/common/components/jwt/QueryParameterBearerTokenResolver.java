package com.coding.common.components.jwt;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Bearer token 解析器：优先 Authorization 头，回退 query 参数 access_token。
 * <p>
 * 浏览器 WebSocket 无法自定义请求头，exec/中继等 WS 握手只能把 token 放 query；
 * 普通 HTTP 请求行为不变（仍走 header）。
 */
public class QueryParameterBearerTokenResolver implements BearerTokenResolver {

    public static final String QUERY_PARAM = "access_token";

    @Override
    public String resolve(HttpServletRequest request) {
        String headerValue = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (headerValue != null && !headerValue.isBlank()) {
            return resolveFromHeader(headerValue);
        }
        return request.getParameter(QUERY_PARAM);
    }

    private String resolveFromHeader(String headerValue) {
        //与 DefaultBearerTokenResolver 一致：仅识别 "Bearer xxx"
        if (headerValue.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = headerValue.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    /**当前请求的原始 token（WS 中继向下游转发 Authorization 头用）；无则 null */
    public static String currentRawToken() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            HttpServletRequest request = servletAttrs.getRequest();
            String headerValue = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (headerValue != null && headerValue.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return headerValue.substring(7).trim();
            }
            return request.getParameter(QUERY_PARAM);
        }
        return null;
    }

}
