package com.coding.platformapi.controllers;

import com.coding.platformapi.audit.AuditAction;
import com.coding.platformapi.audit.Auditable;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⚠️ 结构性钉子（同 {@link CredentialControllerTest}）：创建 / 重置的响应体里有明文密钥，
 * 审计不记响应体；这里再钉住 target 只能取 id / clientId，拿不到请求体里的其它字段。
 */
class OAuthClientControllerTest {

    private static final Set<String> ALLOWED_TARGETS = Set.of("#request.id", "#request.clientId");

    private List<Method> audited() {
        return Arrays.stream(OAuthClientController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Auditable.class)).toList();
    }

    @Test
    void auditedTargetsAreOnlyIdOrClientId() {
        assertThat(audited()).extracting(m -> m.getAnnotation(Auditable.class).target())
                .allMatch(ALLOWED_TARGETS::contains);
    }

    @Test
    void everyWriteEndpointIsAuditedAndReadsAreNot() {
        Set<String> reads = Set.of("/list", "/get", "/scopes");
        for (Method m : OAuthClientController.class.getDeclaredMethods()) {
            PostMapping pm = m.getAnnotation(PostMapping.class);
            if (pm == null) continue;
            boolean isRead = reads.contains(pm.value()[0]);
            assertThat(m.isAnnotationPresent(Auditable.class)).as(pm.value()[0]).isEqualTo(!isRead);
        }
        assertThat(audited()).extracting(m -> m.getAnnotation(Auditable.class).action())
                .containsExactlyInAnyOrder(AuditAction.OAUTH_CLIENT_CREATE, AuditAction.OAUTH_CLIENT_UPDATE,
                        AuditAction.OAUTH_CLIENT_DELETE, AuditAction.OAUTH_CLIENT_ENABLE,
                        AuditAction.OAUTH_CLIENT_RESET_SECRET);
    }
}
