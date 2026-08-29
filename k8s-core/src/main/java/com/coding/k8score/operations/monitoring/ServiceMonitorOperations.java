package com.coding.k8score.operations.monitoring;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.ServiceMonitorDTO;
import com.coding.k8score.converter.impl.monitoring.ServiceMonitorConverter;
import com.coding.k8score.operations.NamespacedOperations;
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
 * ServiceMonitor 操作（monitoring.coreos.com/v1 CRD，fabric8 通用 CRD API）。
 * 集群未装 Prometheus Operator 时 apiserver 返回 404，由上层异常体系透出。
 */
@Slf4j
@RequiredArgsConstructor
public class ServiceMonitorOperations implements NamespacedOperations<ServiceMonitorDTO> {

    public final static String apiVersion = "monitoring.coreos.com/v1";

    private static final CustomResourceDefinitionContext CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup("monitoring.coreos.com")
            .withVersion("v1")
            .withKind("ServiceMonitor")
            .withPlural("servicemonitors")
            .withScope("Namespaced")
            .build();

    private final KubernetesClient client;

    private final ServiceMonitorConverter converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    @Override
    public List<ServiceMonitorDTO> list(String namespace, String labelSelector) {
        String ns = StringUtils.hasText(namespace) ? namespace : "";
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        List<GenericKubernetesResource> items = client.genericKubernetesResources(CRD)
                .inNamespace(ns).list(options).getItems();
        return items.stream().map(converter::revert).toList();
    }

    @Override
    public ServiceMonitorDTO get(String namespace, String name) {
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
    public ServiceMonitorDTO create(ServiceMonitorDTO sm) {
        String namespace = sm.getNamespace();
        String name = sm.getName();
        if (checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
        }
        GenericKubernetesResource in = converter.convert(sm);

        GenericKubernetesResource created = client.genericKubernetesResources(CRD)
                .inNamespace(namespace)
                .resource(in)
                .create();
        return converter.revert(created);
    }

    @Override
    public ServiceMonitorDTO update(ServiceMonitorDTO sm) {
        String namespace = sm.getNamespace();
        String name = sm.getName();
        if (!checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        GenericKubernetesResource in = converter.convert(sm);
        GenericKubernetesResource updated = client.genericKubernetesResources(CRD)
                .inNamespace(namespace)
                .resource(in)
                .update();
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
        return Serialization.asYaml(res);
    }

}
