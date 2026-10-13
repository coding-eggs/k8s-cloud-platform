package com.coding.platformapi.audit;

import com.coding.common.exception.CloudPlatformException;
import com.coding.data.models.system.PlatformAuditLog;
import com.coding.data.models.system.TokenUserInfo;
import com.coding.platformapi.security.AuthContext;
import com.coding.platformapi.services.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Date;

/**
 * 写操作的审计切面：包住带 {@link Auditable} 的 Service 方法。
 *
 * <h3>⚠️ 为什么 {@code @Order(0)} 是必须的</h3>
 * 本仓 {@code @EnableTransactionManagement} + Service 方法级 {@code @Transactional} 真在用
 * （RoleService / TenantMemberService / PermissionRowWriter）。事务通知的默认 order 是
 * {@code Ordered.LOWEST_PRECEDENCE}，而**值越小越靠外** —— {@code @Order(0)} 让本切面排在
 * 事务通知**外面**，于是记录时机在业务**提交/回滚之后**，看到的是真实结果。
 * 若排在里面，会记下一条随后被回滚的 SUCCESS（spec §5.1）。
 *
 * <h3>结果语义</h3>
 * 方法正常返回 = {@code SUCCESS}；抛异常 = {@code FAILED}（典型是 {@link CloudPlatformException}，
 * 带上它的业务码与 msg）。异常**原样抛回**，切面不改业务语义。
 *
 * <h3>为什么用 @Around 而不是 @AfterThrowing</h3>
 * 成功与失败必须是**同一条记录**里的不同 result，而不是两条互不相干的记录 ——
 * 否则「重试 / 重发」会在表里留下两条，读的人分不清。
 */
@Slf4j
@Aspect
@Component
@Order(0)
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditService auditService;

    private final AuthContext authContext;

    private final AuditTargetResolver targetResolver;

    @Around("@annotation(auditable)")
    public Object around(ProceedingJoinPoint pjp, Auditable auditable) throws Throwable {
        long start = System.currentTimeMillis();
        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable t) {
            recordQuietly(pjp, auditable, AuditResult.FAILED, t, start);
            throw t;
        }
        recordQuietly(pjp, auditable, AuditResult.SUCCESS, null, start);
        return result;
    }

    /**
     * 记录本身再包一层 fail-open。
     *
     * <p>为什么不能只靠 {@link AuditService#record} 里的 try/catch：那样「审计失败不影响业务」
     * 这条契约就完全寄存在另一个类里 —— 谁把那个 try/catch 去掉，本切面会静默地开始破坏业务
     * （成功路径的记录调用一旦抛出，业务返回值就丢了）。包在这里，契约才是本地的。
     */
    private void recordQuietly(ProceedingJoinPoint pjp, Auditable auditable,
                               AuditResult result, Throwable error, long start) {
        try {
            auditService.record(build(pjp, auditable, result, error, start));
        } catch (Throwable t) {
            log.error("审计记录失败（已忽略，不影响业务）action={} result={}",
                    auditable.action(), result, t);
        }
    }

    private PlatformAuditLog build(ProceedingJoinPoint pjp, Auditable auditable,
                                   AuditResult result, Throwable error, long start) {
        PlatformAuditLog r = new PlatformAuditLog();
        r.setOccurredAt(new Date());
        r.setAction(auditable.action());
        r.setResult(result.name());
        r.setDurationMs((int) (System.currentTimeMillis() - start));

        TokenUserInfo user = authContext.current();
        r.setActorUsername(user != null && StringUtils.hasText(user.getUsername())
                ? user.getUsername() : AuditService.UNKNOWN_ACTOR);
        r.setActorDisplayName(user == null ? null : user.getDisplayName());

        r.setTargetName(targetResolver.resolveTarget(pjp, auditable.target()));

        AuditTargetResolver.Scope scope = targetResolver.resolveScope(pjp, auditable.scope());
        // tenantId：参数里的优先（代操作时那是「被操作的租户」），退而取 token 租户帽（spec §5.6）
        r.setTenantId(scope.tenantId() != null ? scope.tenantId() : authContext.hatTenantId());
        r.setClusterId(scope.clusterId());
        r.setNamespace(scope.namespace());

        if (error instanceof CloudPlatformException cpe) {
            r.setErrorCode(String.valueOf(cpe.getCode()));
            r.setErrorMsg(cpe.getMsg());
        } else if (error != null) {
            r.setErrorMsg(truncate(error.getClass().getSimpleName() + ": " + error.getMessage(), 512));
        }

        // 非 HTTP 线程（将来的定时任务）取不到请求上下文 → 这两项留空，绝不抛（spec §5.2）
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            r.setClientIp(sra.getRequest().getRemoteAddr());
            r.setUserAgent(sra.getRequest().getHeader("User-Agent"));
        }
        return r;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
