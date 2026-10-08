package com.coding.platformapi.k8s;

import com.coding.common.models.k8s.BaseResources;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * k8s-server <b>公共行为 client</b>：DTO 驱动的六操作（list/get/yaml/create/update/delete），纯传输零业务。
 * <p>
 * 路径取 {@link BaseResources#getApiPath()}，调用签名只收 DTO；集群域 / 命名空间域的差异只是
 * "namespace 为 null 不拼、tenantId 有值才拼"。wire 形态：list = POST {apiPath}/list（条件全在 body），
 * get/yaml/create/update/delete 保持 verb + query 参数形态。
 * <p>
 * <b>三族前缀已去掉</b>（2026-10-08 批次）：api 侧 {@code /resource/**}、k8s-server 侧 {@code /resources/**}
 * 与 {@code /admin/**} 已统一去掉，两跳的 URL 空间因此重合（api {@code /configmaps} ≡ k8s-server
 * {@code /configmaps}）。路径此后只用于寻址，<b>不再表达任何授权含义</b> —— 能不能调用由 platform-api
 * 权限表决定，k8s-server 侧由各 controller 声明的 {@code AccessBoundary} 决定
 * （见 docs/development/backend-layering.md）。非六端点形态的专用端点各自独立成 client
 * （{@code K8sCalicoClient} / {@code K8sNodeClient} / {@code K8sPodClient} / {@code K8sLifecycleClient}）。
 * <p>
 * <b>本类不承担安全边界</b>：授权在 platform-api 权限表，租户/集群边界与 K8s 凭据选择在 k8s-server
 * 的 {@code ResourceAccessResolver}，K8s RBAC 是第三道闸。见 docs/development/backend-layering.md。
 */
@Component
@RequiredArgsConstructor
public class K8sClient {

    private final K8sServerGateway gateway;

    /**列出资源：POST {apiPath}/list，clusterId/namespace/labelSelector 全在 body */
    public <T extends BaseResources> List<T> list(T query) {
        return gateway.exchange(HttpMethod.POST, query.getApiPath() + "/list", null, query,
                gateway.listResponseType(query.getClass()));
    }

    /**
     * 跨全部命名空间列举：POST {apiPath}/list-all（body 不带 namespace）。
     * <p>
     * 平台侧动作 —— k8s-server 侧只有<b>声明开放了该能力</b>的资源才有这个端点，其余返回
     * "本资源不支持跨命名空间列举"；租户调用方则被 k8s-server 的平台侧身份校验拒掉。
     * 授权在 api 侧权限表，是与 {@code /list} 分开的权限码（见各自 controller 的注释）。
     */
    public <T extends BaseResources> List<T> listAll(T query) {
        return gateway.exchange(HttpMethod.POST, query.getApiPath() + "/list-all", null, query,
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
