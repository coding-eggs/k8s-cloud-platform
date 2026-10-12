package com.coding.common.models.k8s.dto;

/**
 * 命名空间配额三档（产品口径「每个命名空间必须有配额」）。
 * <p>判定集中在 platform-api 的 {@code QuotaClassifier.state(NamespaceStatDTO)} 一处，
 * 前端与各页面只消费这个结果、不自行按 key 判。
 */
public enum QuotaState {

    /** 有 ResourceQuota，且含 {@code requests.cpu} + {@code requests.memory} 两个 key */
    OK,

    /** 没有任何 ResourceQuota */
    MISSING,

    /** 有 quota 但缺 {@code requests.cpu} / {@code requests.memory} 中的任一 key */
    PARTIAL
}
