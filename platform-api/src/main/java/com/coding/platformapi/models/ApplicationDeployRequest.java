package com.coding.platformapi.models;

import lombok.Data;

/** 部署/升级：revision 为空 = 用当前 spec 生成新版本；非空 = 重放某个历史版本（回滚/重试）。 */
@Data
public class ApplicationDeployRequest {

    private String appId;

    private Integer revision;
}
