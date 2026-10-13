package com.coding.platformapi.services;

import com.coding.data.mapper.system.PlatformAuditLogMapper;
import com.coding.data.models.system.PlatformAuditLog;
import com.coding.data.models.system.PlatformAuditLogQuery;
import com.coding.data.models.system.TokenUserInfo;
import com.coding.platformapi.audit.AuditDenied;
import com.coding.platformapi.audit.AuditResult;
import com.coding.platformapi.audit.AuditWriter;
import com.coding.platformapi.security.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;

/**
 * 审计记录的门面：**fail-open 包装** + 查询 + 拒绝记录。
 *
 * <p>记录**组装**不在这里 —— 写操作的组装在 {@code AuditAspect}（它才有 join point），
 * 拒绝记录的组装在 {@link #recordDenied}（它拿到的是 HTTP 请求）。
 * 本类只负责「怎么把记录安全地写下去」。
 *
 * <p>实际写库经 {@link AuditWriter}（独立事务）—— 本类**不直接调 mapper.insert**，
 * 否则 REQUIRES_NEW 会因为同类自调用而失效（见 AuditWriter 的类注释）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    /**
     * 身份取不到时记的字面量（不记 NULL —— 与「平台侧无租户操作」的 NULL 语义区分开）。
     *
     * <p><b>public</b>：{@code AuditAspect} 在 {@code ...audit} 包，是本类的跨包使用者。
     */
    public static final String UNKNOWN_ACTOR = "unknown";

    private static final int DEFAULT_LIMIT = 50;

    private static final int MAX_LIMIT = 200;

    /** 候选动作码上限：distinct 的码通常几十个，500 足够且防呆 */
    private static final int MAX_ACTION_OPTIONS = 500;

    private final AuditWriter writer;

    private final PlatformAuditLogMapper mapper;

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

    /** 查询：limit 由本层钳制，调用方不必关心上限 */
    public List<PlatformAuditLog> query(PlatformAuditLogQuery q) {
        clamp(q);
        return mapper.query(q);
    }

    /** 删除：闭开区间 [from, to)；返回实际删除行数 */
    public int purge(Date from, Date to) {
        return mapper.deleteByTimeRange(from, to);
    }

    /**
     * 动作码候选值：审计表里**实际出现过**的码，按出现频次倒序 —— 供筛选 autocomplete。
     *
     * <p>不从一份写死的码表来：那会与 {@code AuditAction} 漂移，而且"哪些码还在用"只有数据知道。
     */
    public List<String> listActions() {
        return mapper.listActions(MAX_ACTION_OPTIONS);
    }

    private void clamp(PlatformAuditLogQuery q) {
        Integer limit = q.getLimit();
        if (limit == null || limit <= 0) {
            q.setLimit(DEFAULT_LIMIT);
        } else if (limit > MAX_LIMIT) {
            q.setLimit(MAX_LIMIT);
        }
    }

    /**
     * 客户端 IP：优先 X-Forwarded-For 的第一个（平台通常经网关），退化到 remoteAddr。
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
