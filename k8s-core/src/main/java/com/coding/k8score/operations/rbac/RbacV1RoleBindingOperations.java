package com.coding.k8score.operations.rbac;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.k8score.converter.CommonConverter;
import com.coding.common.models.k8s.dto.RoleBindingDTO;
import com.coding.k8score.operations.NamespacedOperations;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class RbacV1RoleBindingOperations implements NamespacedOperations<RoleBindingDTO> {

    public final static String apiVersion = "rbac.authorization.k8s.io/v1";

    private final KubernetesClient client;
    private final CommonConverter<RoleBinding, RoleBindingDTO> converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    /**
     * 查询 RoleBinding 列表，支持 namespace、labelSelector（原样透传）过滤（namespace 为空则查询所有）
     */
    @Override
    public List<RoleBindingDTO> list(String namespace, String labelSelector) {
        String ns = StringUtils.hasText(namespace) ? namespace : "";
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        List<RoleBinding> roleBindings = client.rbac().roleBindings()
                .inNamespace(ns).list(options).getItems();
        return roleBindings.stream().map(converter::revert).toList();
    }

    /**
     * 查询单个 RoleBinding
     */
    @Override
    public RoleBindingDTO get(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        RoleBinding roleBinding = client.rbac().roleBindings()
                .inNamespace(namespace)
                .withName(name)
                .get();
        return converter.revert(roleBinding);
    }

    @Override
    public RoleBindingDTO create(RoleBindingDTO dto) {
        String namespace = dto.getNamespace();
        String name = dto.getName();
        if (checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
        }
        RoleBinding in = converter.convert(dto);
        RoleBinding created = client.rbac().roleBindings()
                .inNamespace(namespace)
                .resource(in)
                .create();
        return converter.revert(created);
    }

    @Override
    public RoleBindingDTO update(RoleBindingDTO dto) {
        String namespace = dto.getNamespace();
        String name = dto.getName();
        if (!checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        RoleBinding in = converter.convert(dto);
        RoleBinding updated = client.rbac().roleBindings()
                .inNamespace(namespace)
                .resource(in)
                .update();
        return converter.revert(updated);
    }

    private boolean checkExist(String namespace, String name) {
        RoleBinding existing = client.rbac().roleBindings().inNamespace(namespace).withName(name).get();
        return existing != null;
    }

    @Override
    public void delete(String namespace, String name) {
        if (!checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        client.rbac().roleBindings()
                .inNamespace(namespace)
                .withName(name)
                .delete();
    }

    @Override
    public String yaml(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        RoleBinding roleBinding = client.rbac().roleBindings()
                .inNamespace(namespace)
                .withName(name)
                .get();
        return Serialization.asYaml(roleBinding);
    }

}