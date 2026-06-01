package com.coding.common.models.k8s;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Data
public class BaseResources {

    @Schema(name = "集群id")
    private String clusterId;

    @Schema(name = "租户id")
    private String tenantId;

    @Schema(name = "apiVersion")
    private String apiVersion = "app/v1";

    @Schema(name = "kind")
    private String kind = "Deployment";

    @Schema(name = "名称")
    private String name;

    @Schema(name = "命名空间")
    private String namespace;

    @Schema(name = "labels")
    private Map<String, String> labels;

}
