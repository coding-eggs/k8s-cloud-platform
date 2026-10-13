package com.coding.platformapi.controllers;

import com.coding.platformapi.audit.AuditAction;
import com.coding.platformapi.audit.Auditable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⚠️ **结构性钉子**，不是行为测试。
 *
 * <p>「审计里不记凭据内容与 endpoint」这条契约无法靠行为测试守住 —— 它是否定性的
 * （「某个东西不在记录里」），而一个有 bug 的实现照样能通过「action 对不对」这类断言。
 * 这里退一步，钉住**它成立的原因**：{@code @Auditable.target} 的 SpEL 只允许取
 * {@code #request.id} / {@code #request.name}，拿不到请求体里的敏感字段
 * （{@code username} / {@code secret} / {@code probeUrl} / {@code endpoint}）。
 *
 * <p>本测试**能**挡住的回归：有人把 {@code target} 改成 {@code #request.secret}
 * 或 {@code #request.endpoint}，把凭据内容抄进审计表。
 *
 * <p>本测试**挡不住**的：{@code AuditAspect} 本身被改成读请求体 ——
 * 那属于 S0.1 的范围，由那边的测试守着。
 */
class CredentialControllerTest {

    /** 允许被写进审计目标的请求字段。加字段前先读类注释。 */
    private static final Set<String> ALLOWED_TARGETS = Set.of("#request.id", "#request.name");

    private List<Method> auditedMethods() {
        return Arrays.stream(CredentialController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Auditable.class))
                .toList();
    }

    @Test
    void auditedTargetsNeverReachSensitiveRequestFields() {
        var offenders = auditedMethods().stream()
                .map(m -> m.getAnnotation(Auditable.class).target())
                .filter(t -> !ALLOWED_TARGETS.contains(t))
                .toList();

        assertThat(offenders)
                .as("凭据端点的审计 target 只允许 %s；出现别的说明有人把敏感字段写进了审计表", ALLOWED_TARGETS)
                .isEmpty();
    }

    @Test
    void auditedMethodsAreExactlyTheThreeWritesPlusTest() {
        // 列表是纯读，不标审计；其余四个（create/update/delete/test）全标。
        // 测试连接要标 —— 它真的解密凭据并出站打对端，是本批唯一的「使用」路径。
        var actions = auditedMethods().stream()
                .map(m -> m.getAnnotation(Auditable.class).action())
                .sorted()
                .toList();

        assertThat(actions).containsExactly(
                AuditAction.CREDENTIAL_CREATE,
                AuditAction.CREDENTIAL_DELETE,
                AuditAction.CREDENTIAL_TEST,
                AuditAction.CREDENTIAL_UPDATE);
    }
}
