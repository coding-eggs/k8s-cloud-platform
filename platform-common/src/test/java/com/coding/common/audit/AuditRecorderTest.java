package com.coding.common.audit;

import com.coding.data.models.system.PlatformAuditLog;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link AuditRecorder}：fail-open 契约（随审计迁入 platform-common，自 api 的 AuditServiceTest 拆出；
 * DENIED 记录的字段来源随拒绝判定一起去了网关，见 GatewayDeniedAuditorTest）。
 *
 * <p>外加一条**结构性钉子**（{@link #writerUsesRequiresNewIndependentTransaction()}）—— 见其 javadoc
 * 说明为什么它只能是反射断言而不是行为测试。
 */
class AuditRecorderTest {

    AuditWriter writer = mock(AuditWriter.class);
    AuditRecorder svc = new AuditRecorder(writer);

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
}
