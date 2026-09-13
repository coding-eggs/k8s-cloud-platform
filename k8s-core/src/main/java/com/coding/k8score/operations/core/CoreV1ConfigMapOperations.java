package com.coding.k8score.operations.core;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.ConfigMapDTO;
import com.coding.k8score.converter.CommonConverter;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.ServerSideApply;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * corev1 ConfigMap 操作（模式同 AppV1DeploymentOperations）
 */
@Slf4j
@RequiredArgsConstructor
public class CoreV1ConfigMapOperations implements NamespacedOperations<ConfigMapDTO> {

    public final static String apiVersion = "v1";

    private final KubernetesClient client;

    private final CommonConverter<ConfigMap, ConfigMapDTO> converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    /**
     * 查询 configmap 列表，支持 namespace、labelSelector（原样透传）过滤
     */
    @Override
    public List<ConfigMapDTO> list(String namespace, String labelSelector, String fieldSelector) {
        String ns = StringUtils.hasText(namespace) ? namespace : "";
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<ConfigMap> items = client.configMaps().inNamespace(ns).list(options).getItems();
        return items.stream().map(converter::revert).toList();
    }

    /**
     * 查询 configmap
     */
    @Override
    public ConfigMapDTO get(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        ConfigMap configMap = client.configMaps()
                .inNamespace(namespace)
                .withName(name)
                .get();
        return converter.revert(configMap);
    }

    @Override
    public ConfigMapDTO create(ConfigMapDTO configMap) {
        String namespace = configMap.getNamespace();
        String name = configMap.getName();
        if (checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
        }
        ConfigMap in = converter.convert(configMap);

        ConfigMap created = client.configMaps()
                .inNamespace(namespace)
                .resource(in)
                .create();
        return converter.revert(created);
    }

    /**
     * 更新 configmap
     */
    @Override
    public ConfigMapDTO update(ConfigMapDTO configMap) {
        String namespace = configMap.getNamespace();
        String name = configMap.getName();
        if (!checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        ConfigMap in = converter.convert(configMap);
        ConfigMap updated = client.configMaps()
                .inNamespace(namespace)
                .resource(in)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return converter.revert(updated);
    }

    public boolean checkExist(String namespace, String name) {
        ConfigMap existing = client.configMaps().inNamespace(namespace).withName(name).get();
        return existing != null;
    }

    @Override
    public void delete(String namespace, String name) {
        if (!checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        client.configMaps().inNamespace(namespace)
                .withName(name)
                .delete();
    }

    @Override
    public String yaml(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        ConfigMap configMap = client.configMaps()
                .inNamespace(namespace)
                .withName(name)
                .get();
        if (configMap != null && configMap.getMetadata() != null) {
            configMap.getMetadata().setManagedFields(null);
        }
        return Serialization.asYaml(configMap);
    }

}
