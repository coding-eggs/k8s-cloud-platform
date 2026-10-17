package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/**
 * 一次组件安装/升级操作（表 platform_component_operation，S3.6）。
 *
 * <p>它不是"组件安装状态"表 —— 装没装仍由 S1.2 的探测回答；这张表只回答
 * "这次动作为什么失败 / 做到哪一步"。
 */
@Data
public class PlatformComponentOperation {

    private Long id;

    private String clusterId;

    private String componentCode;

    /** INSTALL / UPGRADE */
    private String action;

    /** 本次使用的画像（MANAGEMENT / BUSINESS） */
    private String profile;

    private String fromVersion;

    /** 目标 chart 版本 */
    private String toVersion;

    /** 本次涉及的 release 名（JSON 数组） */
    private String releaseNames;

    /** 最终合并后的 values（升级时复用） */
    private String valuesSnapshot;

    /** PENDING / RUNNING / SUCCESS / FAILED / UNKNOWN */
    private String status;

    private String message;

    /** helm 输出去敏后的尾部 */
    private String logTail;

    private String operator;

    private Date startedAt;

    private Date finishedAt;
}
