package com.coding.platformapi.components;

/**
 * OTel 组件（{@code otel-operator}）的安装表单开关。
 *
 * <p>{@code ComponentRecipe.otel()} 为 null 表示该组件不是 OTel 采集栈；
 * 非 null 时前端才渲染 Tempo / ES / 采样率等区块。
 *
 * @param instrumentation     是否暴露「语言 / 追踪 / 日志」表单（工作负载接入开关配套）
 * @param tempoInstall        是否同时安装 Tempo（MANAGEMENT；表单要填 MinIO 地址/桶/凭证）
 * @param tempoRemoteWrite    是否引用 {@code ${tempo.endpoint}}（表单里要填 Tempo 地址）
 * @param tempoEndpointDefault 该画像下 Tempo 地址的预填默认值（可空；业务集群由用户填）
 */
public record OtelRecipe(boolean instrumentation, boolean tempoInstall, boolean tempoRemoteWrite,
                         String tempoEndpointDefault) {
}
