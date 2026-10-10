package com.coding.k8score.operations.gateway;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.HttpRouteDTO;
import com.coding.k8score.converter.impl.gateway.HttpRouteConverter;
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
 * HTTPRoute 操作（gateway.networking.k8s.io/v1 CRD，命名空间级，fabric8 通用 CRD API）。
 * <p>租户域资源，边界同 Gateway（分配表三元组）。
 * <p><b>本类的 update 是最需要 fetch-overlay 的一处</b>：{@code spec.rules} 及其内部的
 * {@code matches}/{@code filters}/{@code backendRefs} 都是 atomic list，SSA 会把整列表当一个值覆盖
 * —— 详见 {@link HttpRouteConverter} 的类注释。故这里必须先 get(live) 再 convertForUpdate。
 * <p><b>版本</b>：HTTPRoute 自 Gateway API v1 起 GA —— 单版本，无需 capability 分派。
 */
@Slf4j
@RequiredArgsConstructor
public class HttpRouteOperations implements NamespacedOperations<HttpRouteDTO> {

    public final static String apiVersion = "gateway.networking.k8s.io/v1";

    private static final CustomResourceDefinitionContext CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup("gateway.networking.k8s.io")
            .withVersion("v1")
            .withKind("HTTPRoute")
            .withPlural("httproutes")
            .withScope("Namespaced")
            .build();

    private final KubernetesClient client;

    private final HttpRouteConverter converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    @Override
    public List<HttpRouteDTO> list(String namespace, String labelSelector, String fieldSelector) {
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

    /**
     * 跨全部命名空间列举（平台侧；本实例必须是 admin client，见 {@link NamespacedOperations#listAll}）。
     * <p>返回的每个 item 自带其所在 namespace —— converter 从 metadata 取，本方法<b>不做</b>统一回填。
     */
    @Override
    public List<HttpRouteDTO> listAll(String labelSelector, String fieldSelector) {
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<GenericKubernetesResource> items = client.genericKubernetesResources(CRD)
                .inAnyNamespace().list(options).getItems();
        return items.stream().map(converter::revert).toList();
    }

    @Override
    public HttpRouteDTO get(String namespace, String name) {
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
    public HttpRouteDTO create(HttpRouteDTO route) {
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
    public HttpRouteDTO update(HttpRouteDTO route) {
        String namespace = route.getNamespace();
        String name = route.getName();
        // fetch-overlay：rules 是 atomic list，未建模的 timeouts/retry/sessionPersistence 与
        // CORS/ExternalAuth filter 必须从线上带回，否则 SSA 一次 apply 就抹掉
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
