package com.coding.auth.controller;

import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.system.ResponseData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话相关端点：滑动续期（keepalive）。
 *
 * <p>会话存于 Redis（Spring Session），其 TTL 只在「会话被修改并保存」时重置，<b>光读不刷新</b>。
 * 因此本端点是 SPA 侧<b>唯一</b>的滑动续期手段：写入一个轻量属性触发一次保存，从而把根凭证
 * （HttpOnly 会话）的空闲 TTL 重置为 {@code spring.session.timeout}。access_token 过期后，
 * 只要该会话仍在 TTL 内，即可用 session-renewal grant 重新签发 token。
 *
 * <p>SPA 需在活跃期间定期（远小于 timeout，例如每 10~30 分钟）调用本端点；返回未登录即代表
 * 根凭证已失效，应跳转重新登录。该端点位于默认安全链 {@code anyRequest().authenticated()} 之后：
 * 会话有效时进入本控制器；会话过期时由既有的 expired/invalid session 策略返回
 * {@link EnumResponseType#USER_SESSION_EXPIRED}。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/session")
@Tag(name = "会话管理", description = "会话滑动续期（keepalive）")
public class SessionController {

    /** 用于触发 Redis 会话保存的属性名（值 = 最近一次 keepalive 的 epoch millis） */
    private static final String LAST_KEEPALIVE_ATTR = "lastKeepaliveAt";

    @GetMapping("/keepalive")
    @Operation(summary = "会话滑动续期（刷新会话空闲超时）")
    public ResponseData<Map<String, Object>> keepalive(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return new ResponseData<>(EnumResponseType.USER_UN_LOGIN, null);
        }
        // Spring Session Redis：读不刷新 TTL，必须写属性触发一次保存才会重置过期时间（滑动续期）
        long now = System.currentTimeMillis();
        session.setAttribute(LAST_KEEPALIVE_ATTR, now);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", session.getId());
        data.put("lastAccessedAt", now);
        data.put("maxInactiveIntervalSeconds", session.getMaxInactiveInterval());
        return new ResponseData<>(data);
    }
}
