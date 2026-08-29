package com.coding.platformapi.k8s;

import com.coding.common.models.k8s.BaseResources;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * k8s-server 通用资源 client：DTO 驱动的六操作（list/get/yaml/create/update/delete）。
 * <p>
 * 路径取 {@link BaseResources#getApiPath()}，调用签名只收 DTO；集群域 / 命名空间域的差异只是
 * "namespace 为 null 不拼、tenantId 有值才拼"。wire 形态：list = POST {apiPath}/list（条件全在 body），
 * get/yaml/create/update/delete 保持 verb + query 参数形态。
 */
@Component
@RequiredArgsConstructor
public class K8sResourceClient {

    private final K8sServerGateway gateway;

    /**列出资源：POST {apiPath}/list，clusterId/namespace/labelSelector 全在 body */
    public <T extends BaseResources> List<T> list(T query) {
        return gateway.exchange(HttpMethod.POST, query.getApiPath() + "/list", null, query,
                gateway.listResponseType(query.getClass()));
    }

    /**查询单个：GET {apiPath}/{name}?clusterId[&namespace][&tenantId]；不存在返回 null */
    public <T extends BaseResources> T get(T dto) {
        return gateway.exchange(HttpMethod.GET, namedPath(dto), queryOf(dto, true), null,
                gateway.responseType(dto.getClass()));
    }

    /**查询 YAML（只读展示）：GET {apiPath}/{name}/yaml?... */
    public <T extends BaseResources> String yaml(T dto) {
        return gateway.exchange(HttpMethod.GET, namedPath(dto) + "/yaml", queryOf(dto, true), null,
                gateway.responseType(String.class));
    }

    /**创建：POST {apiPath}?clusterId[&tenantId]（namespace 在 body） */
    public <T extends BaseResources> T create(T dto) {
        return gateway.exchange(HttpMethod.POST, dto.getApiPath(), queryOf(dto, false), dto,
                gateway.responseType(dto.getClass()));
    }

    /**更新：PUT {apiPath}/{name}?clusterId[&tenantId]（namespace 在 body） */
    public <T extends BaseResources> T update(T dto) {
        return gateway.exchange(HttpMethod.PUT, namedPath(dto), queryOf(dto, false), dto,
                gateway.responseType(dto.getClass()));
    }

    /**删除：DELETE {apiPath}/{name}?clusterId[&namespace][&tenantId] */
    public <T extends BaseResources> void delete(T dto) {
        gateway.exchange(HttpMethod.DELETE, namedPath(dto), queryOf(dto, true), null,
                gateway.responseType(Void.class));
    }

    private String namedPath(BaseResources dto) {
        return dto.getApiPath() + "/" + dto.getName();
    }

    /**query 拼装：clusterId 必带；namespace/tenantId 仅在有值时拼（create/update 的 namespace 在 body，不拼） */
    private Map<String, String> queryOf(BaseResources dto, boolean withNamespace) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("clusterId", dto.getClusterId());
        if (withNamespace && dto.getNamespace() != null) {
            params.put("namespace", dto.getNamespace());
        }
        if (dto.getTenantId() != null) {
            params.put("tenantId", dto.getTenantId());
        }
        return params;
    }
}
