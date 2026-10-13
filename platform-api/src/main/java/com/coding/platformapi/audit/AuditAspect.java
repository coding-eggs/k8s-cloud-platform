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
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Date;

/**
 * 写操作的审计切面：包住带 {@link Auditable} 的 **Controller** 方法。
 *
 * <h3>为什么切 Controller 而不是 Service</h3>
 * 审计的单元是「**一次用户请求**」，不是「一次内部方法调用」——拦截点就该是定义这条边界的层：
 * <ul>
 *   <li><b>粒度天然正确</b>：端点与用户动作 1:1；Service 方法可能被多个入口复用、或被内部编排调用
 *       （例如 {@code TenantService.create} 内部调 {@code TenantMemberService.add}，
 *       标在 Service 上会让一次「创建租户」记两条）。</li>
 *   <li><b>与 403 记录同层同粒度</b>：那条已在 HTTP 边界（{@code accessDeniedHandler}），
 *       整条审计轨统一在「请求边界」，心智模型一致。</li>
 *   <li><b>覆盖面可枚举</b>：写端点集合就在权限表里；标在 Service 上这份清单根本不存在。</li>
 *   <li>直接拿得到 URL / 来源 IP / User-Agent，写操作记录也能带 {@code requestPath}。</li>
 * </ul>
 *
 * <p><b>不需要 {@code @Order}</b>：Controller 不带 {@code @Transactional}，而它调用的 Service 事务
 * 都在方法内部完成 —— 切面记录时所有内层事务都已提交或回滚，看到的是真实结果。
 * （若将来有人给 controller 加 {@code @Transactional}，这里就要重新考虑顺序。）
 *
 * <h3>结果语义</h3>
 * 方法正常返回 = {@code SUCCESS}；抛异常 = {@code FAILED}（典型是 {@link CloudPlatformException}，
 * 带上它的业务码与 msg）。异常**原样抛回**，切面不改业务语义。
 * Controller 不吞异常（本仓一个 {@code try} 都没有），所以业务失败在这里看得到。
 *
 * <p><b>已知限制</b>：审计记的是「这次请求的成败」，**不等于内部所有改动都回滚了** ——
 * 复合操作里各 Service 方法有各自独立的事务，第 2 个失败时第 1 个可能已提交。
 *
 * <h3>为什么用 @Around 而不是 @AfterThrowing</h3>
 * 成功与失败必须是**同一条记录**里的不同 result，而不是两条互不相干的记录 ——
 * 否则「重试 / 重发」会在表里留下两条，读的人分不清。
 */
@Slf4j
@Aspect
@Component
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

        // 切在本层，请求上下文照例存在；但非 HTTP 线程（将来的定时任务）取不到时留空，绝不抛（spec §5.2）
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            r.setClientIp(sra.getRequest().getRemoteAddr());
            r.setUserAgent(sra.getRequest().getHeader("User-Agent"));
            r.setRequestPath(sra.getRequest().getRequestURI());
            r.setHttpMethod(sra.getRequest().getMethod());
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
