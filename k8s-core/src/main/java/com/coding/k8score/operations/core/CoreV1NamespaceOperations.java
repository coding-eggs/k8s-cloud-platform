package com.coding.k8score.operations.core;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.NamespaceDTO;
import com.coding.k8score.converter.impl.core.CoreV1NamespaceConverter;
import com.coding.k8score.operations.ClusterOperations;
import com.coding.k8score.operations.ServerSideApply;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 命名空间操作（core/v1，集群级资源）。平台级命名空间管理用，一律 admin client（由调用侧工厂决定）。
 */
@Slf4j
@RequiredArgsConstructor
public class CoreV1NamespaceOperations implements ClusterOperations<NamespaceDTO> {

    public final static String apiVersion = "v1";

    private final KubernetesClient client;

    private final CoreV1NamespaceConverter converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    @Override
    public List<NamespaceDTO> list(String labelSelector, String fieldSelector) {
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<Namespace> items = client.namespaces().list(options).getItems();
        return items.stream().map(converter::revert).toList();
    }

    @Override
    public NamespaceDTO get(String name) {
        if (!StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        return converter.revert(client.namespaces().withName(name).get());
    }

    @Override
    public NamespaceDTO create(NamespaceDTO dto) {
        if (checkExist(dto.getName())) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
        }
        Namespace created = client.namespaces().resource(converter.convert(dto)).create();
        return converter.revert(created);
    }

    @Override
    public NamespaceDTO update(NamespaceDTO dto) {
        Namespace live = client.namespaces().withName(dto.getName()).get();
        if (live == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        Namespace in = converter.convertForUpdate(dto, live);
        Namespace updated = client.namespaces().resource(in)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return converter.revert(updated);
    }

    @Override
    public void delete(String name) {
        if (!checkExist(name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        client.namespaces().withName(name).delete();
    }

    @Override
    public String yaml(String name) {
        if (!StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        Namespace ns = client.namespaces().withName(name).get();
        if (ns != null && ns.getMetadata() != null) {
            ns.getMetadata().setManagedFields(null);
        }
        return Serialization.asYaml(ns);
    }

    public boolean checkExist(String name) {
        return client.namespaces().withName(name).get() != null;
    }
}
