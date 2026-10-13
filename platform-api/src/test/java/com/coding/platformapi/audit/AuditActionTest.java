package com.coding.platformapi.audit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuditAction#all()} 是**反射**来的（不手写第二份 List，否则会与常量漂移）。
 * 本测试钉住「新加常量会自动进候选值」这条性质 —— 凭据页的动作码筛选靠它。
 */
class AuditActionTest {

    @Test
    void allExposesCredentialActions() {
        List<String> all = AuditAction.all();

        assertThat(all).contains(
                AuditAction.CREDENTIAL_CREATE,
                AuditAction.CREDENTIAL_UPDATE,
                AuditAction.CREDENTIAL_DELETE,
                AuditAction.CREDENTIAL_TEST);
        assertThat(all).contains("credential:create", "credential:update",
                "credential:delete", "credential:test");
    }

    @Test
    void allIsStillSortedAndComplete() {
        // S0.1 的 23 个 + 本批 4 个
        assertThat(AuditAction.all()).isSorted().hasSizeGreaterThanOrEqualTo(27);
    }
}
