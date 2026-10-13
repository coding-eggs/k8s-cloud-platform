package com.coding.platformapi.audit;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.models.system.PlatformAuditLog;
import com.coding.data.models.system.TokenUserInfo;
import com.coding.data.models.system.UserTenantInfo;
import com.coding.platformapi.security.AuthContext;
import com.coding.platformapi.services.AuditService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuditAspect}：成功 / 失败的字段与结果、异常原样抛回、信息不全时仍落记录。
 *
 * <p>用**真实**的 {@link AuditTargetResolver}（它是纯函数、无 Spring 依赖），
 * 只把 {@link AuditService} 与 {@link AuthContext} 换成替身 —— 这样「目标/上下文从哪来」
 * 是一条端到端断言，而不是对着 mock 断言。
 */
class AuditAspectTest {

    AuditService auditService = mock(AuditService.class);
    AuthContext authContext = mock(AuthContext.class);
    AuditTargetResolver resolver = new AuditTargetResolver();
    AuditAspect aspect = new AuditAspect(auditService, authContext, resolver);

    // ---- 被切的方法（只提供签名与参数名，不会被真正调用） ----

    @SuppressWarnings("unused")
    private void delete(String name, String tenantId, String clusterId, String namespace) {
    }

    @SuppressWarnings("unused")
    private void noTenantParam(String name, String clusterId, String namespace) {
    }

    @SuppressWarnings("unused")
    private void lonely(String name) {
    }

    private TokenUserInfo user(String username) {
        TokenUserInfo u = new TokenUserInfo();
        u.setUsername(username);
        u.setDisplayName("显示名");
        UserTenantInfo ti = new UserTenantInfo();
        ti.setTenantId("t-hat");
        u.setTenantInfo(ti);
        return u;
    }

    private ProceedingJoinPoint pjp(String method, Class<?>[] types, Object[] args,
                                    Object result, Throwable toThrow) throws Throwable {
        Method m = AuditAspectTest.class.getDeclaredMethod(method, types);
        MethodSignature sig = mock(MethodSignature.class);
        when(sig.getMethod()).thenReturn(m);
        when(sig.getName()).thenReturn(method);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.getArgs()).thenReturn(args);
        if (toThrow != null) {
            when(pjp.proceed()).thenThrow(toThrow);
        } else {
            when(pjp.proceed()).thenReturn(result);
        }
        return pjp;
    }

    /** 走 delete(String,String,String,String) 的 join point */
    private ProceedingJoinPoint deletePjp(Object result, Throwable toThrow) throws Throwable {
        return pjp("delete",
                new Class<?>[]{String.class, String.class, String.class, String.class},
                new Object[]{"web-a", "t1", "c1", "ns1"}, result, toThrow);
    }

    private Auditable ann(String target) {
        return new Auditable() {
            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return Auditable.class;
            }

            @Override
            public String action() {
                return AuditAction.WORKLOAD_DELETE;
            }

            @Override
            public String target() {
                return target;
            }

            @Override
            public String scope() {
                return "";
            }
        };
    }

    @Test
    void successRecordsWithResolvedTargetAndScope() throws Throwable {
        when(authContext.current()).thenReturn(user("alice"));

        aspect.around(deletePjp("ok", null), ann(""));

        PlatformAuditLog r = capture();
        assertThat(r.getResult()).isEqualTo(AuditResult.SUCCESS.name());
        assertThat(r.getAction()).isEqualTo(AuditAction.WORKLOAD_DELETE);
        assertThat(r.getTargetName()).isEqualTo("web-a");
        assertThat(r.getClusterId()).isEqualTo("c1");
        assertThat(r.getNamespace()).isEqualTo("ns1");
        // 参数里的 tenantId 优先于 token 帽（代操作时记的是被操作的租户，spec §5.6）
        assertThat(r.getTenantId()).isEqualTo("t1");
        assertThat(r.getActorUsername()).isEqualTo("alice");
        assertThat(r.getActorDisplayName()).isEqualTo("显示名");
        assertThat(r.getErrorCode()).isNull();
        assertThat(r.getDurationMs()).isNotNull();
    }

    @Test
    void tenantIdFallsBackToTokenHatWhenParamsCarryNone() throws Throwable {
        when(authContext.current()).thenReturn(user("alice"));
        when(authContext.hatTenantId()).thenReturn("t-hat");

        aspect.around(pjp("noTenantParam",
                new Class<?>[]{String.class, String.class, String.class},
                new Object[]{"web-a", "c1", "ns1"}, "ok", null), ann(""));

        assertThat(capture().getTenantId()).isEqualTo("t-hat");
    }

    @Test
    void failedRecordsBusinessCodeAndRethrows() throws Throwable {
        when(authContext.current()).thenReturn(user("alice"));
        CloudPlatformException boom =
                new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "该命名空间仍分配给租户");

        assertThatThrownBy(() -> aspect.around(deletePjp(null, boom), ann("")))
                .isSameAs(boom);

        PlatformAuditLog r = capture();
        assertThat(r.getResult()).isEqualTo(AuditResult.FAILED.name());
        assertThat(r.getErrorCode())
                .isEqualTo(String.valueOf(EnumResponseType.BEAN_VALIDATION_EXCEPTION.getCode()));
        assertThat(r.getErrorMsg()).isEqualTo("该命名空间仍分配给租户");
    }

    @Test
    void unexpectedExceptionAlsoRecordsFailedAndRethrows() throws Throwable {
        when(authContext.current()).thenReturn(user("alice"));

        assertThatThrownBy(() -> aspect.around(deletePjp(null, new IllegalStateException("boom")), ann("")))
                .isInstanceOf(IllegalStateException.class);

        PlatformAuditLog r = capture();
        assertThat(r.getResult()).isEqualTo(AuditResult.FAILED.name());
        assertThat(r.getErrorMsg()).contains("IllegalStateException");
    }

    @Test
    void auditFailureNeverBreaksBusiness() throws Throwable {
        when(authContext.current()).thenReturn(user("alice"));
        doThrow(new RuntimeException("db down")).when(auditService).record(any());

        // service.record 本身已是 fail-open；这里再钉一次 Aspect 不得因此改变业务返回
        assertThat(aspect.around(deletePjp("ok", null), ann(""))).isEqualTo("ok");
    }

    @Test
    void insufficientInfoStillProducesRecord() throws Throwable {
        // 身份与上下文全取不到（非 HTTP 线程 / 无 token）—— 仍要落一条，字段留空、不抛
        when(authContext.current()).thenReturn(null);
        when(authContext.hatTenantId()).thenReturn(null);

        aspect.around(pjp("lonely", new Class<?>[]{String.class}, new Object[]{"web-a"}, null, null), ann(""));

        PlatformAuditLog r = capture();
        assertThat(r.getActorUsername()).isEqualTo(AuditService.UNKNOWN_ACTOR);
        assertThat(r.getTargetName()).isEqualTo("web-a");
        assertThat(r.getResult()).isEqualTo(AuditResult.SUCCESS.name());
        // 单测里没有 request 上下文 → RequestContextHolder 取不到，这两项为空且不抛（spec §5.2）
        assertThat(r.getClientIp()).isNull();
        assertThat(r.getUserAgent()).isNull();
    }

    private PlatformAuditLog capture() {
        ArgumentCaptor<PlatformAuditLog> captor = ArgumentCaptor.forClass(PlatformAuditLog.class);
        verify(auditService).record(captor.capture());
        return captor.getValue();
    }
}
