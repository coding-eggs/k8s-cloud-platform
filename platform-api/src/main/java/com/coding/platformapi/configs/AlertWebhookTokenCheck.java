package com.coding.platformapi.configs;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Alertmanager webhook 的共享密钥。
 *
 * <p>{@code /alert/webhook} 是平台**第一个非用户身份的入站端点** —— 它不是某个登录用户调的，
 * 没有 JWT，所以既进 {@link com.coding.platformapi.security.ExemptPaths}（跳过 JWT 鉴权），
 * 又必须自校验一个共享密钥。
 *
 * <p><b>密钥没配就拒启</b>（fail-closed）—— 理由同 PermissionCrossCheckRunner：
 * 一个"忘了配就等于全开"的入站端点不该存在。宁可起不来。
 */
@Slf4j
@Component
public class AlertWebhookTokenCheck {

    private final String token;

    public AlertWebhookTokenCheck(@Value("${platform.alert.webhook.token:}") String token) {
        this.token = token;
    }

    @PostConstruct
    void requireConfigured() {
        if (!StringUtils.hasText(token)) {
            throw new IllegalStateException(
                    "platform.alert.webhook.token 未配置 —— /alert/webhook 是公开可达的入站端点，"
                            + "没有密钥就等于全开。请配置 ALERT_WEBHOOK_TOKEN 后重启。");
        }
        log.info("告警接收端点已启用（共享密钥已配置）");
    }

    /** 常量时间比较，避免按字符泄时。带不带 {@code Bearer } 前缀都认。 */
    public boolean matches(String presented) {
        if (presented == null) {
            return false;
        }
        // 两边都剥一次前缀：配置里写成 "Bearer xxx" 或 "xxx" 都能对上（本地开发常直接抄一份头）
        String v = stripBearer(presented);
        String expected = stripBearer(token);
        return MessageDigest.isEqual(v.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private static String stripBearer(String s) {
        return s != null && s.startsWith("Bearer ") ? s.substring(7) : s;
    }
}
