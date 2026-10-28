package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;

/**
 * 对外入口 Gateway CR 的下发参数（platform-api → k8s-server）。
 *
 * <p>由 Istio 组件安装成功后的收尾步骤调用：组件同时创建 Gateway CR，并把
 * {@code spec.addresses} 指向承载它的 Service —— 这层绑定以前要手工写，漏了就
 * {@code Programmed=False}（AddressNotUsable）。
 */
@Data
public class ExternalGatewayRequestDTO {
    private String clusterId;
    private String namespace;
    /** Gateway CR 名（平台固定 external-gateway） */
    private String name;
    /** 承载它的 Service 名（同 namespace） */
    private String serviceName;
    private List<GatewayListenerDTO> listeners;

    /**
     * true = 按名字**合并**进集群里已有的 Gateway（只增补/覆盖同名 listener，其余保留）；
     * false/空 = 整份替换（Istio 安装/重放时的行为，能顺带裁掉端口表里删掉的项）。
     *
     * <p>组件（Harbor / S3 这类）只带自己那一条 listener 来，用 merge=true —— 否则它们会把
     * Istio 的端口表整份覆盖掉。
     */
    private Boolean merge;
}
