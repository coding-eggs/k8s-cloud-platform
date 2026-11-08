package com.coding.k8score.operations.gateway;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.ListenerSetDTO;
import com.coding.k8score.converter.impl.gateway.ListenerSetConverter;
import com.coding.k8score.operations.NamespacedOperations;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * ListenerSet 操作（gateway.networking.k8s.io/v1 CRD，命名空间级）—— <b>只读</b>。
 * <p>平台自己的 {@code platform-ingress} 由应用发布按端口分配表整份重算（k8s-server {@code AppIngressService}），
 * 页面写了也会被下一次同步覆盖；用户自建的 ListenerSet 平台只展示。所以 create / update / delete 一律拒绝。
 * <p>集群没有 ListenerSet CRD 时 apiserver 返回 404，由上层异常体系透出（前端显示「未安装」）。
 * <p>租户侧读取依赖 RBAC 模板里 {@code listenersets} 的只读规则（V2026_11_08_2）。
 */
@RequiredArgsConstructor
public class ListenerSetOperations implements NamespacedOperations<ListenerSetDTO> {

    public static final String apiVersion = ListenerSetConverter.API_VERSION;

    private static final CustomResourceDefinitionContext CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup("gateway.networking.k8s.io")
            .withVersion("v1")
            .withKind("ListenerSet")
            .withPlural("listenersets")
            .withScope("Namespaced")
            .build();

    private final KubernetesClient client;

    private final ListenerSetConverter converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    @Override
    public List<ListenerSetDTO> list(String namespace, String labelSelector, String fieldSelector) {
        String ns = StringUtils.hasText(namespace) ? namespace : "";
        return client.genericKubernetesResources(CRD).inNamespace(ns)
                .list(options(labelSelector, fieldSelector)).getItems().stream().map(converter::revert).toList();
    }

    /** 跨全部命名空间列举（平台侧）；每项自带其 namespace */
    @Override
    public List<ListenerSetDTO> listAll(String labelSelector, String fieldSelector) {
        return client.genericKubernetesResources(CRD).inAnyNamespace()
                .list(options(labelSelector, fieldSelector)).getItems().stream().map(converter::revert).toList();
    }

    @Override
    public ListenerSetDTO get(String namespace, String name) {
        requireNameAndNamespace(namespace, name);
        return converter.revert(client.genericKubernetesResources(CRD).inNamespace(namespace).withName(name).get());
    }

    @Override
    public String yaml(String namespace, String name) {
        requireNameAndNamespace(namespace, name);
        GenericKubernetesResource res = client.genericKubernetesResources(CRD)
                .inNamespace(namespace).withName(name).get();
        if (res != null && res.getMetadata() != null) {
            res.getMetadata().setManagedFields(null);
        }
        return Serialization.asYaml(res);
    }

    @Override
    public ListenerSetDTO create(ListenerSetDTO resource) {
        throw readOnly();
    }

    @Override
    public ListenerSetDTO update(ListenerSetDTO resource) {
        throw readOnly();
    }

    @Override
    public void delete(String namespace, String name) {
        throw readOnly();
    }

    private static CloudPlatformException readOnly() {
        return new CloudPlatformException(EnumResponseType.OPERATION_NOT_SUPPORTED);
    }

    private static void requireNameAndNamespace(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
    }

    private static ListOptions options(String labelSelector, String fieldSelector) {
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        return options;
    }
}
