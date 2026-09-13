package com.coding.k8score.operations.core;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.PodDTO;
import com.coding.k8score.converter.CommonConverter;
import com.coding.k8score.operations.NamespacedOperations;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * corev1 Pod 操作（只读列表 + 查询 + 删除 + YAML；日志/exec 属阶段 3）。
 * Pod 由工作负载控制器管理，平台侧不提供创建/更新。
 */
@Slf4j
@RequiredArgsConstructor
public class CoreV1PodOperations implements NamespacedOperations<PodDTO> {

    public final static String apiVersion = "v1";

    private final KubernetesClient client;

    private final CommonConverter<Pod, PodDTO> converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    @Override
    public List<PodDTO> list(String namespace, String labelSelector, String fieldSelector) {
        String ns = StringUtils.hasText(namespace) ? namespace : "";
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<Pod> items = client.pods().inNamespace(ns).list(options).getItems();
        return items.stream().map(converter::revert).toList();
    }

    @Override
    public PodDTO get(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        Pod pod = client.pods()
                .inNamespace(namespace)
                .withName(name)
                .get();
        return converter.revert(pod);
    }

    @Override
    public PodDTO create(PodDTO pod) {
        throw new CloudPlatformException(EnumResponseType.ERROR, "Pod 由工作负载控制器管理，不支持平台侧创建");
    }

    @Override
    public PodDTO update(PodDTO pod) {
        throw new CloudPlatformException(EnumResponseType.ERROR, "Pod 由工作负载控制器管理，不支持平台侧更新");
    }

    public boolean checkExist(String namespace, String name) {
        Pod existing = client.pods().inNamespace(namespace).withName(name).get();
        return existing != null;
    }

    @Override
    public void delete(String namespace, String name) {
        if (!checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        client.pods().inNamespace(namespace)
                .withName(name)
                .delete();
    }

    @Override
    public String yaml(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        Pod pod = client.pods()
                .inNamespace(namespace)
                .withName(name)
                .get();
        if (pod != null && pod.getMetadata() != null) {
            pod.getMetadata().setManagedFields(null);
        }
        return Serialization.asYaml(pod);
    }

}
