package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 命名空间级 ListenerSet 的下发参数（platform-api → k8s-server）。
 *
 * <p>listeners 为空 = 删除该 ListenerSet，并从命名空间上去掉 {@link #namespaceLabels} 的键。
 * 主 Gateway 的 {@code allowedListeners} 只放行带这些标签的命名空间，所以打标签与 ListenerSet 同进同出。
 */
@Data
public class IngressListenerSetRequestDTO {
    private String clusterId;
    private String namespace;
    private String name;
    private String parentNamespace;
    private String parentName;
    private Map<String, String> namespaceLabels;
    private List<IngressListenerDTO> listeners;
}
