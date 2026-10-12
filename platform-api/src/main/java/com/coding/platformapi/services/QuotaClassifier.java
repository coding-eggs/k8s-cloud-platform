package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.NamespaceStatDTO;
import com.coding.common.models.k8s.dto.QuotaState;

/**
 * 配额三档判定（产品口径「每个命名空间必须有配额」的落点）。
 * <p>判据只看两个 key：{@code requests.cpu} / {@code requests.memory}（与平台配额编辑器同一对 key）。
 * 没有 quota → {@link QuotaState#MISSING}；有 quota 但缺任一 key → {@link QuotaState#PARTIAL}；
 * 两个都有 → {@link QuotaState#OK}。
 * <p><b>放一处</b>：租户概览、集群概览明细、命名空间详情页三处都调它，不许各自判
 * （前端也不许按前缀判 —— 状态由后端回带，见 {@code NamespaceStatEntry}）。
 */
public final class QuotaClassifier {

    private QuotaClassifier() {
    }

    public static QuotaState state(NamespaceStatDTO stat) {
        if (stat == null || (stat.getQuotaCpuHard() == null && stat.getQuotaMemHard() == null)) {
            return QuotaState.MISSING;
        }
        return stat.getQuotaCpuHard() != null && stat.getQuotaMemHard() != null
                ? QuotaState.OK : QuotaState.PARTIAL;
    }
}
