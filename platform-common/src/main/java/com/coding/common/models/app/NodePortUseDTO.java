package com.coding.common.models.app;

import lombok.Data;

/** 集群里一个已占用的 nodePort：哪个 Service（命名空间 / 名称）占着哪个端口。 */
@Data
public class NodePortUseDTO {

    private String namespace;

    private String name;

    private Integer nodePort;
}
