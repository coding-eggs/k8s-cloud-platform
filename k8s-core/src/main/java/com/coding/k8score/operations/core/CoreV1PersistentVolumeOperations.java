package com.coding.k8score.operations.core;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.PersistentVolumeDTO;
import com.coding.k8score.converter.CommonConverter;
import com.coding.k8score.operations.ClusterOperations;
import com.coding.k8score.operations.ServerSideApply;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.PersistentVolume;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * PersistentVolume 集群级资源操作（core/v1）。平台侧只读：供 PVC 详情查看绑定的 PV；写操作同样透传。
 */
@Slf4j
@RequiredArgsConstructor
public class CoreV1PersistentVolumeOperations implements ClusterOperations<PersistentVolumeDTO> {

    public final static String apiVersion = "v1";

    private final KubernetesClient client;
    private final CommonConverter<PersistentVolume, PersistentVolumeDTO> converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    /** 查询 PV 列表（集群级资源，不支持 namespace 过滤）；labelSelector 原样透传 */
    @Override
    public List<PersistentVolumeDTO> list(String labelSelector, String fieldSelector) {
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        return client.persistentVolumes().list(options).getItems().stream()
                .map(converter::revert).toList();
    }

    @Override
    public PersistentVolumeDTO get(String name) {
        if (!StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        return converter.revert(client.persistentVolumes().withName(name).get());
    }

    @Override
    public PersistentVolumeDTO create(PersistentVolumeDTO dto) {
        String name = dto.getName();
        if (checkExist(name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
        }
        PersistentVolume in = converter.convert(dto);
        return converter.revert(client.persistentVolumes().resource(in).create());
    }

    @Override
    public PersistentVolumeDTO update(PersistentVolumeDTO dto) {
        String name = dto.getName();
        if (!checkExist(name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        PersistentVolume in = converter.convert(dto);
        return converter.revert(client.persistentVolumes().resource(in)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply());
    }

    @Override
    public void delete(String name) {
        if (!checkExist(name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        client.persistentVolumes().withName(name).delete();
    }

    @Override
    public String yaml(String name) {
        if (!StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        PersistentVolume pv = client.persistentVolumes().withName(name).get();
        if (pv != null && pv.getMetadata() != null) {
            pv.getMetadata().setManagedFields(null);
        }
        return Serialization.asYaml(pv);
    }

    private boolean checkExist(String name) {
        return client.persistentVolumes().withName(name).get() != null;
    }

}
