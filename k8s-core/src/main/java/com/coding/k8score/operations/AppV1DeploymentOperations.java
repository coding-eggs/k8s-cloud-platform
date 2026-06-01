package com.coding.k8score.operations;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.k8score.converter.CommonConverter;
import com.coding.common.models.k8s.dto.DeploymentDTO;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class AppV1DeploymentOperations implements CommonOperations<DeploymentDTO> {

    public final static String apiVersion = "apps/v1";

    private final KubernetesClient client;

    private final CommonConverter<Deployment, DeploymentDTO> converter;

    @Override
    public String apiVersion() {
        return apiVersion;
    }

    /**
     * 查询deployment 列表，支持 labels，namespace 过滤
     * @param labels
     * @param namespace
     * @return
     */
    @Override
    public List<DeploymentDTO> list(String namespace, Map<String, String> labels) {
        String ns = StringUtils.hasText(namespace) ? namespace : "";
        Map<String, String> lb = labels == null ? new HashMap<>() : labels;

        List<Deployment> deploymentList = client.apps().deployments()
                .inNamespace(ns)
                .withLabels(lb)
                .list().getItems();
        return deploymentList.stream().map(converter::revert).toList();
    }


    /**
     * 查询deployment
     * @param name 工作负载名称
     * @param namespace 命名空间
     * @return deployment
     */
    @Override
    public DeploymentDTO get( String namespace, String name) {
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
     * @param deployment 要更新的deployment
     * @return 更新后的 deployment
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
                .inNamespace(name)
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





}
