package com.coding.platformapi.models;

import lombok.Data;

import java.util.List;
import java.util.Map;

/** 发布相关接口的入参。 */
@Data
public class AppReleaseRequest {

    private String appId;

    private String tenantId;

    /** 勾选发布的服务 key（含待删除的） */
    private List<String> serviceKeys;

    /** 服务 key → 本次要用的镜像 tag（可空） */
    private Map<String, String> images;

    private String note;

    private Integer releaseNo;

    private Long executionId;
}
