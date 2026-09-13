package com.coding.k8score.operations.core;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO;
import com.coding.k8score.converter.CommonConverter;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.ServerSideApply;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * corev1 PersistentVolumeClaim 操作。
 * 规格创建后不可变：update 仅同步标签，保留原 spec。
 */
@Slf4j
@RequiredArgsConstructor
public class CoreV1PersistentVolumeClaimOperations implements NamespacedOperations<PersistentVolumeClaimDTO> {

    public final static String apiVersion = "v1";

    private final KubernetesClient client;

    private final CommonConverter<PersistentVolumeClaim, PersistentVolumeClaimDTO> converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    @Override
    public List<PersistentVolumeClaimDTO> list(String namespace, String labelSelector, String fieldSelector) {
        String ns = StringUtils.hasText(namespace) ? namespace : "";
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<PersistentVolumeClaim> items = client.persistentVolumeClaims()
                .inNamespace(ns).list(options).getItems();
        return items.stream().map(converter::revert).toList();
    }

    @Override
    public PersistentVolumeClaimDTO get(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        PersistentVolumeClaim pvc = client.persistentVolumeClaims()
                .inNamespace(namespace)
                .withName(name)
                .get();
        return converter.revert(pvc);
    }

    @Override
    public PersistentVolumeClaimDTO create(PersistentVolumeClaimDTO pvc) {
        String namespace = pvc.getNamespace();
        String name = pvc.getName();
        if (checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
        }
        PersistentVolumeClaim in = converter.convert(pvc);

        PersistentVolumeClaim created = client.persistentVolumeClaims()
                .inNamespace(namespace)
                .resource(in)
                .create();
        return converter.revert(created);
    }

    /**
     * 更新 PVC：spec 不可变，仅同步 metadata 标签
     */
    @Override
    public PersistentVolumeClaimDTO update(PersistentVolumeClaimDTO pvc) {
        String namespace = pvc.getNamespace();
        String name = pvc.getName();
        PersistentVolumeClaim existing = client.persistentVolumeClaims()
                .inNamespace(namespace)
                .withName(name)
                .get();
        if (existing == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        existing.getMetadata().setLabels(pvc.getLabels());
        PersistentVolumeClaim updated = client.persistentVolumeClaims()
                .inNamespace(namespace)
                .resource(existing)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return converter.revert(updated);
    }

    public boolean checkExist(String namespace, String name) {
        PersistentVolumeClaim existing = client.persistentVolumeClaims().inNamespace(namespace).withName(name).get();
        return existing != null;
    }

    @Override
    public void delete(String namespace, String name) {
        if (!checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        client.persistentVolumeClaims().inNamespace(namespace)
                .withName(name)
                .delete();
    }

    @Override
    public String yaml(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        PersistentVolumeClaim pvc = client.persistentVolumeClaims()
                .inNamespace(namespace)
                .withName(name)
                .get();
        if (pvc != null && pvc.getMetadata() != null) {
            pvc.getMetadata().setManagedFields(null);
        }
        return Serialization.asYaml(pvc);
    }

}
