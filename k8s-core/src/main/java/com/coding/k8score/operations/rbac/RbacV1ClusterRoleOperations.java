package com.coding.k8score.operations.rbac;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.k8score.converter.CommonConverter;
import com.coding.common.models.k8s.dto.ClusterRoleDTO;
import com.coding.k8score.operations.ClusterOperations;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.rbac.ClusterRole;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class RbacV1ClusterRoleOperations implements ClusterOperations<ClusterRoleDTO> {

    public final static String apiVersion = "rbac.authorization.k8s.io/v1";

    private final KubernetesClient client;
    private final CommonConverter<ClusterRole, ClusterRoleDTO> converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    /**
     * 查询 ClusterRole 列表（集群级资源，不支持 namespace 过滤）；labelSelector 原样透传
     */
    @Override
    public List<ClusterRoleDTO> list(String labelSelector) {
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        List<ClusterRole> clusterRoles = client.rbac().clusterRoles().list(options).getItems();
        return clusterRoles.stream().map(converter::revert).toList();
    }

    /**
     * 查询单个 ClusterRole（集群级资源）
     */
    @Override
    public ClusterRoleDTO get(String name) {
        if (!StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        ClusterRole clusterRole = client.rbac().clusterRoles().withName(name).get();
        return converter.revert(clusterRole);
    }

    @Override
    public ClusterRoleDTO create(ClusterRoleDTO dto) {
        String name = dto.getName();
        if (checkExist(name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
        }
        ClusterRole in = converter.convert(dto);
        ClusterRole created = client.rbac().clusterRoles().resource(in).create();
        return converter.revert(created);
    }

    @Override
    public ClusterRoleDTO update(ClusterRoleDTO dto) {
        String name = dto.getName();
        if (!checkExist(name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        ClusterRole in = converter.convert(dto);
        ClusterRole updated = client.rbac().clusterRoles().resource(in).update();
        return converter.revert(updated);
    }

    @Override
    public void delete(String name) {
        if (!checkExist(name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        client.rbac().clusterRoles().withName(name).delete();
    }

    @Override
    public String yaml(String name) {
        if (!StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        ClusterRole clusterRole = client.rbac().clusterRoles().withName(name).get();
        return Serialization.asYaml(clusterRole);
    }

    private boolean checkExist(String name) {
        ClusterRole existing = client.rbac().clusterRoles().withName(name).get();
        return existing != null;
    }

}