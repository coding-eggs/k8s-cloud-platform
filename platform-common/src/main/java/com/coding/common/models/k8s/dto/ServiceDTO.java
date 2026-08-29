package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * Service DTO（基础表单字段）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ServiceDTO extends BaseResources {

    /** ClusterIP / NodePort / LoadBalancer / ExternalName，缺省 ClusterIP */
    private String type = "ClusterIP";

    private List<ServicePortDTO> ports;

    /** 集群 IP（仅查询返回） */
    private String clusterIp;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/resources/services";
    }

}
