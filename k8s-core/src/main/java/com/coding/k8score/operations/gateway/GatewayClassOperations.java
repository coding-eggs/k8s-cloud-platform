package com.coding.k8score.operations.gateway;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.GatewayClassDTO;
import com.coding.k8score.converter.impl.gateway.GatewayClassConverter;
import com.coding.k8score.operations.ClusterOperations;
import com.coding.k8score.operations.ServerSideApply;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * GatewayClass 操作（gateway.networking.k8s.io/v1 CRD，<b>集群级</b>，fabric8 通用 CRD API）。
 * <p>全 cluster-scoped：无 namespace 维度，list/get 走 {@code .list()} / {@code .withName(name).get()}。
 * 集群未装 Gateway API 时 apiserver 返回 404，由上层异常体系透出（前端横幅降级「未安装」）。
 * <p><b>版本</b>：GatewayClass 自 Gateway API v1 起 GA 且从未有 v1alpha2 —— 单版本，无需 capability 分派
 * （对比 L4 路由，见 {@code GatewayOperations} 的说明）。
 * <p><b>部署侧待确认（RBAC）</b>：本操作用 admin client（platform-system SA），其 ClusterRole 需覆盖
 * gateway.networking.k8s.io/v1 gatewayclasses 的 get/list/create/update/patch/delete。未覆盖时被 apiserver
 * 拒绝（403），由上层异常透出。若平台决定 GatewayClass 完全由运维手工维护，只需给 platform-api 侧
 * 撤掉写端点的权限行（读端点保留给 Gateway 编辑器的下拉候选）。
 */
@Slf4j
@RequiredArgsConstructor
public class GatewayClassOperations implements ClusterOperations<GatewayClassDTO> {

    public final static String apiVersion = "gateway.networking.k8s.io/v1";

    private static final CustomResourceDefinitionContext CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup("gateway.networking.k8s.io")
            .withVersion("v1")
            .withKind("GatewayClass")
            .withPlural("gatewayclasses")
            .withScope("Cluster")
            .build();

    private final KubernetesClient client;

    private final GatewayClassConverter converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    @Override
    public List<GatewayClassDTO> list(String labelSelector, String fieldSelector) {
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<GenericKubernetesResource> items = client.genericKubernetesResources(CRD)
                .list(options).getItems();
        return items.stream().map(converter::revert).toList();
    }

    @Override
    public GatewayClassDTO get(String name) {
        if (!StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        GenericKubernetesResource res = client.genericKubernetesResources(CRD)
                .withName(name)
                .get();
        return converter.revert(res);
    }

    @Override
    public GatewayClassDTO create(GatewayClassDTO gc) {
        String name = gc.getName();
        if (checkExist(name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
        }
        GenericKubernetesResource in = converter.convert(gc);
        GenericKubernetesResource created = client.genericKubernetesResources(CRD)
                .resource(in)
                .create();
        return converter.revert(created);
    }

    @Override
    public GatewayClassDTO update(GatewayClassDTO gc) {
        String name = gc.getName();
        // fetch-overlay：先取线上对象，converter 以它为底覆盖建模字段，保留未建模的 spec 外部字段
        GenericKubernetesResource live = client.genericKubernetesResources(CRD)
                .withName(name)
                .get();
        if (live == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        GenericKubernetesResource in = converter.convertForUpdate(gc, live);
        GenericKubernetesResource updated = client.genericKubernetesResources(CRD)
                .resource(in)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return converter.revert(updated);
    }

    public boolean checkExist(String name) {
        GenericKubernetesResource existing = client.genericKubernetesResources(CRD)
                .withName(name)
                .get();
        return existing != null;
    }

    @Override
    public void delete(String name) {
        if (!checkExist(name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        client.genericKubernetesResources(CRD)
                .withName(name)
                .delete();
    }

    @Override
    public String yaml(String name) {
        if (!StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        GenericKubernetesResource res = client.genericKubernetesResources(CRD)
                .withName(name)
                .get();
        if (res != null && res.getMetadata() != null) {
            res.getMetadata().setManagedFields(null);
        }
        return Serialization.asYaml(res);
    }

}
