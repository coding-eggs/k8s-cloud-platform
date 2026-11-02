package com.coding.platformapi.models;

import lombok.Data;

import java.util.Map;

/** 服务配置的入参（create / update / remove 共用）。serviceKey 创建后不可改，update 只用它定位。 */
@Data
public class AppServiceRequest {

    private String appId;

    private String tenantId;

    private String serviceKey;

    /** 期望配置：imageRepo、imageTag、port、replicas、env 等，见 AppServiceDesired */
    private Map<String, Object> desired;

    /** 读到的 version，更新时必须一致（乐观锁） */
    private Integer version;
}
