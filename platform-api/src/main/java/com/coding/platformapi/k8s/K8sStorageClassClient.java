package com.coding.platformapi.k8s;

import com.coding.common.models.k8s.dto.StorageClassDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * k8s-server 存储类专用端点 client（{@code /storageclasses/**} 中非六端点形态的"设为/取消默认"动作）。
 * 纯传输零业务。
 * <p>六端点（list/get/yaml）仍在 {@link K8sClient}（DTO 驱动）；这里只放这个动作。
 * clusterId / tenantId / isDefault 全走 query，无 body。tenantId 透传但 k8s-server 侧 SC 端点不收
 * （集群级、admin client，同 {@code /storageclasses/{name}}），带上无害。
 */
@Component
@RequiredArgsConstructor
public class K8sStorageClassClient {

    private final K8sServerGateway gateway;

    /**设为 / 取消集群默认存储类：POST /storageclasses/{name}/default?clusterId[&tenantId]&isDefault */
    public StorageClassDTO setDefault(String name, String tenantId, String clusterId, boolean isDefault) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("clusterId", clusterId);
        if (tenantId != null) {
            params.put("tenantId", tenantId);
        }
        params.put("isDefault", String.valueOf(isDefault));
        return gateway.exchange(HttpMethod.POST, "/storageclasses/" + name + "/default", params, null,
                gateway.responseType(StorageClassDTO.class));
    }
}
