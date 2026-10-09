package com.coding.k8score.operations.gateway;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.GrpcRouteDTO;
import com.coding.k8score.converter.impl.gateway.GrpcRouteConverter;
import com.coding.k8score.operations.NamespacedOperations;
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
 * GRPCRoute 操作（gateway.networking.k8s.io/<b>v1</b> CRD，命名空间级，fabric8 通用 CRD API）。
 * <p>租户域资源，边界同 Gateway（分配表三元组）。
 * <p><b>版本单挂 v1</b>（GRPCRoute 自 Gateway API v1.1 GA）。有意<b>不</b>像 L4 路由那样回退到
 * v1alpha2：pre-v1.1 的 v1alpha2 GRPCRoute 结构与 v1 不同（有 queryParams、filter 类型也不同），
 * 悄悄切过去会写出结构错误的对象。v1 不存在时由 {@code KubernetesOperationsFactory} 在构造前显式报错
 * （capability 已知时），比让 apiserver 404 更可读；capability 未探测时按 v1 试，404 由上层异常透出。
 */
@Slf4j
@RequiredArgsConstructor
public class GrpcRouteOperations implements NamespacedOperations<GrpcRouteDTO> {

    public final static String apiVersion = "gateway.networking.k8s.io/v1";

    private static final CustomResourceDefinitionContext CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup("gateway.networking.k8s.io")
            .withVersion("v1")
            .withKind("GRPCRoute")
            .withPlural("grpcroutes")
            .withScope("Namespaced")
            .build();

    private final KubernetesClient client;

    private final GrpcRouteConverter converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    @Override
    public List<GrpcRouteDTO> list(String namespace, String labelSelector, String fieldSelector) {
        String ns = StringUtils.hasText(namespace) ? namespace : "";
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<GenericKubernetesResource> items = client.genericKubernetesResources(CRD)
                .inNamespace(ns).list(options).getItems();
        return items.stream().map(converter::revert).toList();
    }

    @Override
    public GrpcRouteDTO get(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        GenericKubernetesResource res = client.genericKubernetesResources(CRD)
                .inNamespace(namespace)
                .withName(name)
                .get();
        return converter.revert(res);
    }

    @Override
    public GrpcRouteDTO create(GrpcRouteDTO route) {
        String namespace = route.getNamespace();
        String name = route.getName();
        if (checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
        }
        GenericKubernetesResource in = converter.convert(route);
        GenericKubernetesResource created = client.genericKubernetesResources(CRD)
                .inNamespace(namespace)
                .resource(in)
                .create();
        return converter.revert(created);
    }

    @Override
    public GrpcRouteDTO update(GrpcRouteDTO route) {
        String namespace = route.getNamespace();
        String name = route.getName();
        // fetch-overlay：rules/matches/filters/backendRefs 都是 atomic list，
        // 未建模的 sessionPersistence 等必须从线上带回，否则 SSA 一次 apply 就抹掉
        GenericKubernetesResource live = client.genericKubernetesResources(CRD)
                .inNamespace(namespace)
                .withName(name)
                .get();
        if (live == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        GenericKubernetesResource in = converter.convertForUpdate(route, live);
        GenericKubernetesResource updated = client.genericKubernetesResources(CRD)
                .inNamespace(namespace)
                .resource(in)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return converter.revert(updated);
    }

    public boolean checkExist(String namespace, String name) {
        GenericKubernetesResource existing = client.genericKubernetesResources(CRD)
                .inNamespace(namespace)
                .withName(name)
                .get();
        return existing != null;
    }

    @Override
    public void delete(String namespace, String name) {
        if (!checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        client.genericKubernetesResources(CRD).inNamespace(namespace)
                .withName(name)
                .delete();
    }

    @Override
    public String yaml(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        GenericKubernetesResource res = client.genericKubernetesResources(CRD)
                .inNamespace(namespace)
                .withName(name)
                .get();
        if (res != null && res.getMetadata() != null) {
            res.getMetadata().setManagedFields(null);
        }
        return Serialization.asYaml(res);
    }

}
