package com.coding.k8score.operations.core;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.SecretDTO;
import com.coding.k8score.converter.CommonConverter;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.ServerSideApply;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * corev1 Secret 操作（模式同 CoreV1ConfigMapOperations）
 * <p><b>有意不覆写 {@link NamespacedOperations#listAll}（2026-10-10 决定）</b>：其它命名空间级资源本批都补齐了
 * 跨命名空间列举，Secret 单独保留 —— 现在平台管理员读 Secret 必须显式带<b>已分配的</b> (tenantId, ns) 三元组；
 * 一旦支持 list-all，就变成"读全集群每个命名空间的 Secret"，含 kube-system 里 SA token 那类。那不是能力补齐，
 * 是敏感面扩张，须单独评估后再放开（届时同时要配 {@code platform:secret:list-all} 权限行的显式角色绑定）。
 */
@Slf4j
@RequiredArgsConstructor
public class CoreV1SecretOperations implements NamespacedOperations<SecretDTO> {

    public final static String apiVersion = "v1";

    private final KubernetesClient client;

    private final CommonConverter<Secret, SecretDTO> converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    @Override
    public List<SecretDTO> list(String namespace, String labelSelector, String fieldSelector) {
        String ns = StringUtils.hasText(namespace) ? namespace : "";
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        client.secrets().inNamespace(ns).list(options);
        List<Secret> items = client.secrets().inNamespace(ns).list(options).getItems();
        return items.stream().map(converter::revert).toList();
    }

    @Override
    public SecretDTO get(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        Secret secret = client.secrets()
                .inNamespace(namespace)
                .withName(name)
                .get();
        return converter.revert(secret);
    }

    @Override
    public SecretDTO create(SecretDTO secret) {
        String namespace = secret.getNamespace();
        String name = secret.getName();
        if (checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
        }
        Secret in = converter.convert(secret);

        Secret created = client.secrets()
                .inNamespace(namespace)
                .resource(in)
                .create();
        return converter.revert(created);
    }

    @Override
    public SecretDTO update(SecretDTO secret) {
        String namespace = secret.getNamespace();
        String name = secret.getName();
        if (!checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        Secret in = converter.convert(secret);
        Secret updated = client.secrets()
                .inNamespace(namespace)
                .resource(in)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return converter.revert(updated);
    }

    public boolean checkExist(String namespace, String name) {
        Secret existing = client.secrets().inNamespace(namespace).withName(name).get();
        return existing != null;
    }

    @Override
    public void delete(String namespace, String name) {
        if (!checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        client.secrets().inNamespace(namespace)
                .withName(name)
                .delete();
    }

    @Override
    public String yaml(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        Secret secret = client.secrets()
                .inNamespace(namespace)
                .withName(name)
                .get();
        if (secret != null && secret.getMetadata() != null) {
            secret.getMetadata().setManagedFields(null);
        }
        return Serialization.asYaml(secret);
    }

}
