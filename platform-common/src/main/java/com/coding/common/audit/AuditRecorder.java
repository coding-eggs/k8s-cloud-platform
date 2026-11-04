package com.coding.common.audit;

import com.coding.common.security.AuthContext;
import com.coding.data.models.system.PlatformAuditLog;
import com.coding.data.models.system.TokenUserInfo;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.Date;

/**
 * 审计记录的写入门面（servlet 业务服务共用）：**fail-open 包装** + 403 拒绝记录。
 *
 * <p>实际写库经 {@link AuditWriter}（独立事务）—— 本类**不直接调 mapper.insert**，
 * 否则 REQUIRES_NEW 会因为同类自调用而失效（见 AuditWriter 的类注释）。
 *
 * <p>网关上线后 DENIED 由网关写（{@code GatewayDeniedAuditor}），本类的 {@link #recordDenied}
 * 只在业务服务自己还判接口权限时有用。
 */
@Slf4j
@RequiredArgsConstructor
public class AuditRecorder {

    /** 身份取不到时记的字面量（不记 NULL —— 与「平台侧无租户操作」的 NULL 语义区分开） */
    public static final String UNKNOWN_ACTOR = "unknown";

    private final AuditWriter writer;

    private final AuthContext authContext;

    /**
     * fail-open：审计写不进去只记错误日志，**绝不改变业务结果**（spec「写入契约」）。
     */
    public void record(PlatformAuditLog record) {
        try {
            writer.write(record);
        } catch (Exception e) {
            log.error("审计写入失败 action={} result={} path={}",
                    record.getAction(), record.getResult(), record.getRequestPath(), e);
        }
    }

    /**
     * 记录一条 403 被拒请求。
     *
     * <p><b>绝不能抛</b>：这是唯一一处「审计失败会改变用户可见结果」的位置 ——
     * 它一抛，403 就变成 500，把「权限不足」伪装成「服务故障」，还掩盖了真实原因（spec §5.4）。
     */
    public void recordDenied(HttpServletRequest request) {
        try {
            TokenUserInfo user = authContext.current();
            PlatformAuditLog r = new PlatformAuditLog();
            r.setOccurredAt(new Date());
            r.setActorUsername(user != null && StringUtils.hasText(user.getUsername())
                    ? user.getUsername() : UNKNOWN_ACTOR);
            r.setActorDisplayName(user == null ? null : user.getDisplayName());
            r.setTenantId(authContext.hatTenantId());
            r.setAction(AuditDenied.codesOf(request));
            r.setResult(AuditResult.DENIED.name());
            r.setHttpMethod(request.getMethod());
            r.setRequestPath(request.getRequestURI());
            r.setClientIp(clientIp(request));
            r.setUserAgent(request.getHeader("User-Agent"));
            writer.write(r);
        } catch (Exception e) {
            log.error("审计（拒绝）写入失败 path={}", request.getRequestURI(), e);
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
