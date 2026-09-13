package com.coding.k8score.operations.workload;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.ReplicaSetDTO;
import com.coding.k8score.converter.CommonConverter;
import com.coding.k8score.operations.NamespacedOperations;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * apps/v1 ReplicaSet 操作（只读：列表 + 查询 + YAML）。ReplicaSet 由 Deployment 控制器管理，
 * 平台侧不提供创建/更新/删除——本资源仅用于按 labelSelector 列出某 Deployment 名下的 RS。
 */
@RequiredArgsConstructor
public class AppsV1ReplicaSetOperations implements NamespacedOperations<ReplicaSetDTO> {

    public final static String apiVersion = "apps/v1";

    private final KubernetesClient client;

    private final CommonConverter<ReplicaSet, ReplicaSetDTO> converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    /** 列出命名空间内 ReplicaSet，支持 labelSelector（原样透传）过滤 */
    @Override
    public List<ReplicaSetDTO> list(String namespace, String labelSelector, String fieldSelector) {
        String ns = StringUtils.hasText(namespace) ? namespace : "";
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<ReplicaSet> items = client.resources(ReplicaSet.class).inNamespace(ns).list(options).getItems();
        return items.stream().map(converter::revert).toList();
    }

    @Override
    public ReplicaSetDTO get(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        ReplicaSet replicaSet = client.resources(ReplicaSet.class)
                .inNamespace(namespace)
                .withName(name)
                .get();
        return converter.revert(replicaSet);
    }

    @Override
    public String yaml(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        ReplicaSet replicaSet = client.resources(ReplicaSet.class)
                .inNamespace(namespace)
                .withName(name)
                .get();
        if (replicaSet != null && replicaSet.getMetadata() != null) {
            replicaSet.getMetadata().setManagedFields(null);
        }
        return Serialization.asYaml(replicaSet);
    }

    @Override
    public ReplicaSetDTO create(ReplicaSetDTO replicaSet) {
        throw new CloudPlatformException(EnumResponseType.OPERATION_NOT_SUPPORTED, "ReplicaSet 由 Deployment 管理，不支持平台侧创建");
    }

    @Override
    public ReplicaSetDTO update(ReplicaSetDTO replicaSet) {
        throw new CloudPlatformException(EnumResponseType.OPERATION_NOT_SUPPORTED, "ReplicaSet 由 Deployment 管理，不支持平台侧更新");
    }

    @Override
    public void delete(String namespace, String name) {
        throw new CloudPlatformException(EnumResponseType.OPERATION_NOT_SUPPORTED, "ReplicaSet 由 Deployment 管理，不支持平台侧删除");
    }

}
