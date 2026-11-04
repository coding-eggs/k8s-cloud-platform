package com.coding.gateway.audit;

import com.coding.data.mapper.system.PlatformAuditLogMapper;
import com.coding.data.models.system.PlatformAuditLog;
import com.coding.gateway.security.GatewayAuthorizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.InetSocketAddress;
import java.util.Date;
import java.util.Map;
import java.util.Set;

/**
 * 被拒（403）审计（spec §5）：只有网关知道请求被拒了，故 DENIED 由网关写；SUCCESS/FAILED 仍由业务服务写。
 *
 * <p>字段与 api 原先的 {@code AuditService.recordDenied} 对齐。target 不取（取它就得解析业务请求体）；
 * 路径不含 query（WS 的 access_token 在 query 里，绝不能进审计表）。
 *
 * <p>{@link #record} <b>永不出错</b>：写库失败只记 ERROR，403 照常返回 —— 否则一次库抖动会把 403 变成 500。
 * 网关没有业务事务，直接 {@code insert}，不需要 api 那边的 REQUIRES_NEW。
 */
public class GatewayDeniedAuditor {

    private static final Logger log = LoggerFactory.getLogger(GatewayDeniedAuditor.class);

    /** 身份取不到时记的字面量（与 common 的 AuditRecorder.UNKNOWN_ACTOR 同值） */
    static final String UNKNOWN_ACTOR = "unknown";

    private final PlatformAuditLogMapper mapper;
    private final String dataKey;

    public GatewayDeniedAuditor(PlatformAuditLogMapper mapper, String dataKey) {
        this.mapper = mapper;
        this.dataKey = dataKey;
    }

    public Mono<Void> record(ServerWebExchange exchange, Authentication auth) {
        return Mono.fromRunnable(() -> mapper.insert(build(exchange, auth)))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.error("审计（拒绝）写入失败 path={}", exchange.getRequest().getPath().value(), e);
                    return Mono.empty();
                })
                .then();
    }

    private PlatformAuditLog build(ServerWebExchange exchange, Authentication auth) {
        ServerHttpRequest req = exchange.getRequest();
        Map<?, ?> data = dataClaim(auth);
        PlatformAuditLog r = new PlatformAuditLog();
        r.setOccurredAt(new Date());
        String username = text(data.get("username"));
        r.setActorUsername(StringUtils.hasText(username) ? username : UNKNOWN_ACTOR);
        r.setActorDisplayName(text(data.get("displayName")));
        r.setTenantId(data.get("tenantInfo") instanceof Map<?, ?> t ? text(t.get("tenantId")) : null);
        // 与 api 原 AuditDenied.mark 同格式（存量审计行一致）：逗号拼接、无方括号（审计页按 action = 权限码 精确筛选）；没命中权限行 → null
        Set<String> codes = exchange.getAttribute(GatewayAuthorizationManager.DENIED_CODES_ATTR);
        r.setAction(codes == null || codes.isEmpty() ? null : String.join(",", codes));
        r.setResult("DENIED");
        r.setHttpMethod(req.getMethod().name());
        r.setRequestPath(req.getPath().value());
        r.setClientIp(clientIp(req));
        r.setUserAgent(req.getHeaders().getFirst("User-Agent"));
        return r;
    }

    private Map<?, ?> dataClaim(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwt && jwt.getToken().getClaim(dataKey) instanceof Map<?, ?> m) {
            return m;
        }
        return Map.of();
    }

    /** X-Real-IP（nginx 设的）→ X-Forwarded-For 第一段 → 对端地址 */
    private static String clientIp(ServerHttpRequest req) {
        String real = req.getHeaders().getFirst("X-Real-IP");
        if (StringUtils.hasText(real)) {
            return real.trim();
        }
        String forwarded = req.getHeaders().getFirst("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        InetSocketAddress remote = req.getRemoteAddress();
        return remote == null || remote.getAddress() == null ? null : remote.getAddress().getHostAddress();
    }

    private static String text(Object o) {
        return o == null ? null : o.toString();
    }
}
