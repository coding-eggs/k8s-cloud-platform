package com.coding.k8score.operations.snapshot;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.VolumeSnapshotDTO;
import com.coding.k8score.converter.impl.snapshot.VolumeSnapshotConverter;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.ServerSideApply;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * VolumeSnapshot 操作（snapshot.storage.k8s.io/v1 CRD，命名空间级）。
 * <p>集群没装快照 CRD 时 apiserver 返回 404，由上层异常体系透出；门控在 platform-api 侧先探测。
 * <p>快照的 spec 创建后不可变，所以 {@link #update} 明确不支持。
 */
@RequiredArgsConstructor
public class VolumeSnapshotOperations implements NamespacedOperations<VolumeSnapshotDTO> {

    public final static String apiVersion = "snapshot.storage.k8s.io/v1";

    private static final CustomResourceDefinitionContext CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup("snapshot.storage.k8s.io")
            .withVersion("v1")
            .withKind("VolumeSnapshot")
            .withPlural("volumesnapshots")
            .withScope("Namespaced")
            .build();

    private final KubernetesClient client;

    private final VolumeSnapshotConverter converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    @Override
    public List<VolumeSnapshotDTO> list(String namespace, String labelSelector, String fieldSelector) {
        String ns = StringUtils.hasText(namespace) ? namespace : "";
        List<GenericKubernetesResource> items = client.genericKubernetesResources(CRD)
                .inNamespace(ns).list(options(labelSelector, fieldSelector)).getItems();
        return items.stream().map(converter::revert).toList();
    }

    /** 跨全部命名空间列举（admin client，见 {@link NamespacedOperations#listAll}）。 */
    @Override
    public List<VolumeSnapshotDTO> listAll(String labelSelector, String fieldSelector) {
        List<GenericKubernetesResource> items = client.genericKubernetesResources(CRD)
                .inAnyNamespace().list(options(labelSelector, fieldSelector)).getItems();
        return items.stream().map(converter::revert).toList();
    }

    @Override
    public VolumeSnapshotDTO get(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        GenericKubernetesResource res = client.genericKubernetesResources(CRD)
                .inNamespace(namespace)
                .withName(name)
                .get();
        return res == null ? null : converter.revert(res);
    }

    @Override
    public VolumeSnapshotDTO create(VolumeSnapshotDTO snapshot) {
        String namespace = snapshot.getNamespace();
        String name = snapshot.getName();
        if (checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
        }
        GenericKubernetesResource created = client.genericKubernetesResources(CRD)
                .inNamespace(namespace)
                .resource(converter.convert(snapshot))
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return converter.revert(created);
    }

    @Override
    public VolumeSnapshotDTO update(VolumeSnapshotDTO snapshot) {
        throw new CloudPlatformException(EnumResponseType.OPERATION_NOT_SUPPORTED,
                "快照创建后不能修改");
    }

    public boolean checkExist(String namespace, String name) {
        return client.genericKubernetesResources(CRD)
                .inNamespace(namespace)
                .withName(name)
                .get() != null;
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
