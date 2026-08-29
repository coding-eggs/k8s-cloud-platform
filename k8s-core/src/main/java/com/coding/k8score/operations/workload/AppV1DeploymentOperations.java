package com.coding.k8score.operations.workload;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.k8score.converter.CommonConverter;
import com.coding.common.models.k8s.dto.DeploymentDTO;
import com.coding.k8score.operations.NamespacedOperations;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.Yaml;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class AppV1DeploymentOperations implements NamespacedOperations<DeploymentDTO> {

    public final static String apiVersion = "apps/v1";

    private final KubernetesClient client;

    private final CommonConverter<Deployment, DeploymentDTO> converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    /**
     * 查询deployment 列表，支持 namespace、labelSelector（原样透传）过滤
     */
    @Override
    public List<DeploymentDTO> list(String namespace, String labelSelector) {
        String ns = StringUtils.hasText(namespace) ? namespace : "";
        ListOptions options = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            options.setLabelSelector(labelSelector);
        }
        List<Deployment> deploymentList = client.apps().deployments().inNamespace(ns).list(options).getItems();
        return deploymentList.stream().map(converter::revert).toList();
    }


    /**
     * 查询deployment
     */
    @Override
    public DeploymentDTO get(String namespace, String name) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        Deployment deployment = client.apps().deployments()
                .inNamespace(namespace)
                .withName(name)
                .get();
        return converter.revert(deployment);
    }

    @Override
    public DeploymentDTO create(DeploymentDTO deployment) {
        String namespace = deployment.getNamespace();
        String name = deployment.getName();
        if (checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST);
        }
        Deployment in = converter.convert(deployment);

        Deployment created = client.apps().deployments()
                .inNamespace(namespace)
                .resource(in)
                .create();
        return converter.revert(created);
    }


    /**
     * 更新deployment
     */
    @Override
    public DeploymentDTO update(DeploymentDTO deployment) {
        String namespace = deployment.getNamespace();
        String name = deployment.getName();
        if (!checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        Deployment in = converter.convert(deployment);
        Deployment update = client.apps().deployments()
                .inNamespace(namespace)
                .resource(in)
                .update();
        return converter.revert(update);
    }


    public boolean checkExist(String namespace, String name) {
        Deployment existing = client.apps().deployments().inNamespace(namespace).withName(name).get();
        return existing != null;
    }


    @Override
    public void delete(String namespace, String name) {
        if (!checkExist(namespace, name)) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        client.apps().deployments().inNamespace(namespace)
                .withName(name)
                .delete();
    }

    @Override
    public String yaml(String namespace, String name) {

        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        Deployment deployment = client.apps().deployments()
                .inNamespace(namespace)
                .withName(name)
                .get();

        return Serialization.asYaml(deployment);
    }

}