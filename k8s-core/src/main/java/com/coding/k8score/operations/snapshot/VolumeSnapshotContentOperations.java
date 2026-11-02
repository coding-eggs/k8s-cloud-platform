package com.coding.k8score.operations.snapshot;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.VolumeSnapshotContentDTO;
import com.coding.k8score.converter.impl.snapshot.VolumeSnapshotContentConverter;
import com.coding.k8score.operations.ClusterOperations;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * VolumeSnapshotContent 操作（snapshot.storage.k8s.io/v1，集群级，只读）。
 * <p>快照内容随 VolumeSnapshot 由 snapshot-controller 维护；平台不删它（删孤儿内容会动存储后端，本批不做）。
 */
@RequiredArgsConstructor
public class VolumeSnapshotContentOperations implements ClusterOperations<VolumeSnapshotContentDTO> {

    public final static String apiVersion = "snapshot.storage.k8s.io/v1";

    private static final CustomResourceDefinitionContext CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup("snapshot.storage.k8s.io")
            .withVersion("v1")
            .withKind("VolumeSnapshotContent")
            .withPlural("volumesnapshotcontents")
            .withScope("Cluster")
            .build();

    private final KubernetesClient client;

    private final VolumeSnapshotContentConverter converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    @Override
    public List<VolumeSnapshotContentDTO> list(String labelSelector, String fieldSelector) {
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        List<GenericKubernetesResource> items = client.genericKubernetesResources(CRD)
                .list(options).getItems();
        return items.stream().map(converter::revert).toList();
    }

    @Override
    public VolumeSnapshotContentDTO get(String name) {
        if (!StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        GenericKubernetesResource res = client.genericKubernetesResources(CRD).withName(name).get();
        return res == null ? null : converter.revert(res);
    }

    @Override
    public VolumeSnapshotContentDTO create(VolumeSnapshotContentDTO resource) {
        throw readOnly();
    }

    @Override
    public VolumeSnapshotContentDTO update(VolumeSnapshotContentDTO resource) {
        throw readOnly();
    }

    @Override
    public void delete(String name) {
        throw readOnly();
    }

    @Override
    public String yaml(String name) {
        if (!StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        GenericKubernetesResource res = client.genericKubernetesResources(CRD).withName(name).get();
        if (res != null && res.getMetadata() != null) {
            res.getMetadata().setManagedFields(null);
        }
        return Serialization.asYaml(res);
    }

    private static CloudPlatformException readOnly() {
        return new CloudPlatformException(EnumResponseType.OPERATION_NOT_SUPPORTED, "快照内容在平台上只读");
    }

}
