package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/** 应用的一个服务（表 platform_app_service）。serviceKey 创建后不可改；version 是乐观锁。 */
@Data
public class PlatformAppService {

    private Long id;

    private String appId;

    private String serviceKey;

    /** 期望配置（对齐 AppWorkloadValues，镜像拆为 imageRepo / imageTag） */
    private String desiredJson;

    /** 下次发布时移除 */
    private Boolean pendingDelete;

    private Integer version;

    private Date createdAt;

    private Date updatedAt;
}
