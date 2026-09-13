package com.coding.k8score.operations.core;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.StorageClassDTO;
import com.coding.k8score.converter.CommonConverter;
import com.coding.k8score.operations.ClusterOperations;
import com.coding.k8score.operations.ServerSideApply;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.storage.StorageClass;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * StorageClass 集群级资源操作（storage/v1）。平台侧主要用 list/get/yaml 供下拉选择；写操作同样透传。
 */
@Slf4j
@RequiredArgsConstructor
public class CoreV1StorageClassOperations implements ClusterOperations<StorageClassDTO> {

    public final static String apiVersion = "storage.k8s.io/v1";

    private final KubernetesClient client;
    private final CommonConverter<StorageClass, StorageClassDTO> converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    /** 查询 StorageClass 列表（集群级资源，不支持 namespace 过滤）；labelSelector 原样透传 */
    @Override
    public List<StorageClassDTO> list(String labelSelector, String fieldSelector) {
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            options.setFieldSelector(fieldSelector);
        }
        return client.storage().v1().storageClasses().list(options).getItems().stream()
                .map(converter::revert).toList();
    }

    @Override
    public StorageClassDTO get(String name) {
        if (!StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        return converter.revert(client.storage().v1().storageClasses().withName(name).get());
    }

    @Override
    public StorageClassDTO create(StorageClassDTO dto) {
        String name = dto.getName();
        if (checkExist(name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
        }
        StorageClass in = converter.convert(dto);
        return converter.revert(client.storage().v1().storageClasses().resource(in).create());
    }

    @Override
    public StorageClassDTO update(StorageClassDTO dto) {
        String name = dto.getName();
        if (!checkExist(name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        StorageClass in = converter.convert(dto);
        return converter.revert(client.storage().v1().storageClasses().resource(in)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply());
    }

    @Override
    public void delete(String name) {
        if (!checkExist(name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        client.storage().v1().storageClasses().withName(name).delete();
    }

    @Override
    public String yaml(String name) {
        if (!StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        StorageClass sc = client.storage().v1().storageClasses().withName(name).get();
        if (sc != null && sc.getMetadata() != null) {
            sc.getMetadata().setManagedFields(null);
        }
        return Serialization.asYaml(sc);
    }

    private boolean checkExist(String name) {
        return client.storage().v1().storageClasses().withName(name).get() != null;
    }

}
