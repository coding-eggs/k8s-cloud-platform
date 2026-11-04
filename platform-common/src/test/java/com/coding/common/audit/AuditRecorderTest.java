package com.coding.common.audit;

import com.coding.common.security.AuthContext;
import com.coding.data.models.system.PlatformAuditLog;
import com.coding.data.models.system.TokenUserInfo;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuditRecorder}：fail-open 契约、recordDenied 的字段来源（随审计迁入 platform-common，自 api 的 AuditServiceTest 拆出）。
 *
 * <p>外加一条**结构性钉子**（{@link #writerUsesRequiresNewIndependentTransaction()}）—— 见其 javadoc
 * 说明为什么它只能是反射断言而不是行为测试。
 */
class AuditRecorderTest {

    AuditWriter writer = mock(AuditWriter.class);
    AuthContext authContext = mock(AuthContext.class);
    AuditRecorder svc = new AuditRecorder(writer, authContext);

    private TokenUserInfo user(String username, String displayName) {
        TokenUserInfo u = new TokenUserInfo();
        u.setUsername(username);
        u.setDisplayName(displayName);
        return u;
    }

    @Test
    void recordIsFailOpenWhenWriterThrows() {
        PlatformAuditLog record = new PlatformAuditLog();
        doThrow(new RuntimeException("db down")).when(writer).write(any());

        // 审计写库失败绝不能冒泡到业务调用方
        assertThatCode(() -> svc.record(record)).doesNotThrowAnyException();
    }

    @Test
    void recordDelegatesToWriter() {
        PlatformAuditLog record = new PlatformAuditLog();

        svc.record(record);

        verify(writer).write(record);
    }

    @Test
    void recordDeniedCarriesDeniedPermissionCode() {
        when(authContext.current()).thenReturn(user("alice", "爱丽丝"));
        when(authContext.hatTenantId()).thenReturn("t1");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/nodes/list");
        req.setRemoteAddr("10.0.0.9");
        req.addHeader("User-Agent", "curl/8");
        req.setAttribute(AuditDenied.ATTR_DENIED_CODES, "platform:cluster:manage");

        svc.recordDenied(req);

        PlatformAuditLog captured = captureWritten();
        assertThat(captured.getResult()).isEqualTo(AuditResult.DENIED.name());
        assertThat(captured.getAction()).isEqualTo("platform:cluster:manage");
        assertThat(captured.getActorUsername()).isEqualTo("alice");
        assertThat(captured.getActorDisplayName()).isEqualTo("爱丽丝");
        assertThat(captured.getTenantId()).isEqualTo("t1");
        assertThat(captured.getHttpMethod()).isEqualTo("POST");
        assertThat(captured.getRequestPath()).isEqualTo("/nodes/list");
        assertThat(captured.getClientIp()).isEqualTo("10.0.0.9");
        assertThat(captured.getUserAgent()).isEqualTo("curl/8");
        // DENIED 记录没有目标对象与耗时：拒绝发生在方法调用之前
        assertThat(captured.getTargetName()).isNull();
        assertThat(captured.getDurationMs()).isNull();
    }

    @Test
    void recordDeniedWithoutCodeStillRecords() {
        when(authContext.current()).thenReturn(null);
        MockHttpServletRequest req = new MockHttpServletRequest("DELETE", "/pods/web-a");

        svc.recordDenied(req);

        PlatformAuditLog captured = captureWritten();
        assertThat(captured.getResult()).isEqualTo(AuditResult.DENIED.name());
        assertThat(captured.getAction()).isNull();
        // 身份取不到 → 记字面量 unknown（不记 NULL，便于区分「身份丢失」与「平台侧无租户」）
        assertThat(captured.getActorUsername()).isEqualTo(AuditRecorder.UNKNOWN_ACTOR);
    }

    @Test
    void recordDeniedPrefersForwardedFor() {
        when(authContext.current()).thenReturn(user("bob", "Bob"));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/audit/list");
        req.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");

        svc.recordDenied(req);

        assertThat(captureWritten().getClientIp()).isEqualTo("203.0.113.7");
    }

    @Test
    void recordDeniedIsFailOpenWhenWriterThrows() {
        doThrow(new RuntimeException("db down")).when(writer).write(any());
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/audit/list");

        // 这是唯一一处「审计失败会改变用户可见结果」的位置：一抛就把 403 变成 500
        assertThatCode(() -> svc.recordDenied(req)).doesNotThrowAnyException();
    }

    /**
     * ⚠️ **结构性钉子**，不是行为测试。
     *
     * <p>{@code REQUIRES_NEW} 是「业务回滚不带走审计记录」的唯一保证（spec §5.1），而它由 Spring 代理
     * 在**跨 bean 调用**时实施 —— 本仓的单测一律不起 Spring 上下文（见 RoleServiceTest 等的写法），
     * 所以这个行为在这里验证不了。退而求其次：用反射钉住注解本身。
     *
     * <p>本测试**能**挡住的回归：有人删掉注解、或把传播级别改小。
     *
     * <p>本测试**挡不住**的：有人把 {@code write} 挪进 {@code AuditRecorder} 由同类方法自调用 ——
     * 那时注解还在、代理却被绕过，只能靠 code review 把关
     * （{@code PermissionRowWriter} 的类注释记着同一个坑）。
     */
    @Test
    void writerUsesRequiresNewIndependentTransaction() throws Exception {
        Method m = AuditWriter.class.getMethod("write", PlatformAuditLog.class);
        Transactional tx = m.getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    private PlatformAuditLog captureWritten() {
        var captor = org.mockito.ArgumentCaptor.forClass(PlatformAuditLog.class);
        verify(writer).write(captor.capture());
        return captor.getValue();
    }
}
