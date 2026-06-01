package com.coding.k8score.factory;


import com.coding.k8score.config.ResourceCapability;
import com.coding.common.models.k8s.ResourceType;
import com.coding.k8score.operations.CommonOperations;
import io.fabric8.kubernetes.client.KubernetesClient;

public interface CommonOperationsFactory<T> {

    ResourceType type();


    CommonOperations<T> get(KubernetesClient client, ResourceCapability capability, String apiVersion);

}
