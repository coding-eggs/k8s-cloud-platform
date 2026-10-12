package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.NamespaceStatDTO;
import com.coding.common.models.k8s.dto.QuotaState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配额三档判定（产品口径「每个命名空间必须有配额」）：判据只看 {@code requests.cpu} /
 * {@code requests.memory} 两个 key。没有 quota → MISSING；有 quota 但缺任一 key → PARTIAL；
 * 两个都有 → OK。三处（租户概览 / 集群概览明细 / 命名空间详情页）共用这一处判定。
 */
class QuotaClassifierTest {

    @Test
    void missing_when_no_quota_at_all() {
        NamespaceStatDTO s = new NamespaceStatDTO();
        s.setNamespace("team-a");
        assertThat(QuotaClassifier.state(s)).isEqualTo(QuotaState.MISSING);
    }

    @Test
    void partial_when_quota_lacks_requests_keys() {
        NamespaceStatDTO s = new NamespaceStatDTO();
        // 只有 cpu，缺 requests.memory
        s.setQuotaCpuHard(new BigDecimal("4"));
        assertThat(QuotaClassifier.state(s)).isEqualTo(QuotaState.PARTIAL);
    }

    @Test
    void ok_when_both_requests_keys_present() {
        NamespaceStatDTO s = new NamespaceStatDTO();
        s.setQuotaCpuHard(new BigDecimal("4"));
        s.setQuotaMemHard(new BigDecimal("8589934592"));
        assertThat(QuotaClassifier.state(s)).isEqualTo(QuotaState.OK);
    }
}
