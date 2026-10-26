package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * ES 凭证 Secret 的下发参数（OTel 批次一）。
 *
 * <p>collector 的 elasticsearch exporter 用 basic auth 写 ES。来源 Secret 必须放在
 * {@code monitor} 且包含固定的 {@code username} / {@code password} 两个 key；k8s-server
 * 把它复制成 {@code monitor/platform-otel-es}（同名 key），密码不经过 platform-api。
 */
@Data
public class OtelEsSecretRequestDTO {
    private String clusterId;
    /** 来源 Secret 的命名空间（固定 monitor） */
    private String namespace;
    /** 来源 Secret 名 */
    private String secretName;
    /** 目标 Secret 的命名空间（collector 在 monitor，必须同 ns） */
    private String targetNamespace;
    /** 目标 Secret 名（collector 的 env 指向它） */
    private String targetSecretName;
    /**
     * collector 的 {@code OpenTelemetryCollector} CR 名。换凭证后平台改它的
     * {@code spec.podAnnotations} 让 operator 滚动 Pod —— 直接改 operator 生成的 Deployment 会被 reconcile 回去。
     */
    private String collectorName;
}
