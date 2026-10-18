package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OTel 组件的安装参数（{@code otel-operator} recipe 的 {@code otel} 字段）。
 *
 * <p>Tempo 地址两个画像都要；Tempo 存储只在 MANAGEMENT（{@code tempoInstall}）；
 * ES 只在打开日志时必填。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OtelInstallRequest {
    /** collector 的 traces 导出目标（gRPC host:port；管理集群默认 Tempo 集群内地址） */
    private String tempoEndpoint;
    /** 采样率百分比（1..100，默认 10） */
    private Integer samplingPercentage;
    /** collector Deployment 副本数（1..10，默认 1） */
    private Integer gatewayReplicas;
    /** 是否打开应用日志（OTel logs → ES） */
    private Boolean logsEnabled;
    /** Tempo 的 traces 保留期（Go duration，如 168h；默认 7 天） */
    private String tempoRetention;
    /** collector → Tempo 的传输模式：plaintext / tls / tls-skip-verify */
    private String tempoTlsMode;
    /** Tempo 的 MinIO 参数（仅 MANAGEMENT） */
    private TempoStorageConfig tempoStorage;
    /** ES 参数（logsEnabled 时必填） */
    private EsConfig es;
}
