package com.coding.k8score.operations.rbac;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.k8score.converter.CommonConverter;
import com.coding.common.models.k8s.dto.ServiceAccountDTO;
import com.coding.k8score.operations.NamespacedOperations;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class CoreV1ServiceAccountOperations implements NamespacedOperations<ServiceAccountDTO> {

    public final static String apiVersion = "v1";

    private final KubernetesClient client;
    private final CommonConverter<ServiceAccount, ServiceAccountDTO> converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    /**
     * 查询 ServiceAccount 列表，支持 namespace、labelSelector（原样透传）过滤（namespace 为空则查询所有）
     */
    @Override
    public List<ServiceAccountDTO> list(String namespace, String labelSelector) {
        String ns = StringUtils.hasText(namespace) ? namespace : "";
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        List<ServiceAccount> sas = client.serviceAccounts().inNamespace(ns).list(options).getItems();
        return sas.stream().map(converter::revert).toList();
    }

    /**
     * 查询单个 ServiceAccount
     */
    @Override
    public ServiceAccountDTO get(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        ServiceAccount sa = client.serviceAccounts()
                .inNamespace(namespace)
                .withName(name)
                .get();
        return converter.revert(sa);
    }

    @Override
    public ServiceAccountDTO create(ServiceAccountDTO dto) {
        String namespace = dto.getNamespace();
        String name = dto.getName();
        if (checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
        }
        ServiceAccount in = converter.convert(dto);
        ServiceAccount created = client.serviceAccounts()
                .inNamespace(namespace)
                .resource(in)
                .create();
        return converter.revert(created);
    }

    @Override
    public ServiceAccountDTO update(ServiceAccountDTO dto) {
        String namespace = dto.getNamespace();
        String name = dto.getName();
        if (!checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        ServiceAccount in = converter.convert(dto);
        ServiceAccount updated = client.serviceAccounts()
                .inNamespace(namespace)
                .resource(in)
                .update();
        return converter.revert(updated);
    }

    private boolean checkExist(String namespace, String name) {
        ServiceAccount existing = client.serviceAccounts().inNamespace(namespace).withName(name).get();
        return existing != null;
    }

    @Override
    public void delete(String namespace, String name) {
        if (!checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        client.serviceAccounts()
                .inNamespace(namespace)
                .withName(name)
                .delete();
    }

    @Override
    public String yaml(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        ServiceAccount sa = client.serviceAccounts()
                .inNamespace(namespace)
                .withName(name)
                .get();
        return Serialization.asYaml(sa);
    }

}