package com.coding.common.models.k8s;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ServiceType {

    ClusterIP("ClusterIP"),
    NodePort("NodePort"),
    LoadBalancer("LoadBalancer"),
    ExternalName("ExternalName"),

    ;

    private final String type;

}
