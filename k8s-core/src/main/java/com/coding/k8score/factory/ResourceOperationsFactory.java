package com.coding.k8score.factory;

import com.coding.k8score.config.ResourceCapability;
import com.coding.common.models.k8s.ResourceType;
import com.coding.k8score.operations.ResourceOperations;
import io.fabric8.kubernetes.client.KubernetesClient;

public interface ResourceOperationsFactory<T> {

    ResourceType type();

    ResourceOperations<T> get(KubernetesClient client, ResourceCapability capability, String apiVersion);

}