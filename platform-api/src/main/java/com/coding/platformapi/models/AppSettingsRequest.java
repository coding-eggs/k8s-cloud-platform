package com.coding.platformapi.models;

import lombok.Data;

import java.util.Map;

/** 应用设置的入参：链路追踪 / 服务网格，见 AppSettings */
@Data
public class AppSettingsRequest {

    private String appId;

    private String tenantId;

    /** {tracing, sampling, mesh:{mode: none|l4|l7, waypoint}}；null / 空 = 全部跟随 */
    private Map<String, Object> settings;

    /** 读到的 settingsVersion，必须一致（乐观锁） */
    private Integer version;
}
