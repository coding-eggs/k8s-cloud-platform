package com.coding.common.models.app;

import lombok.Data;

import java.util.Map;

/** 图上的一个节点。{@code key} 在应用内唯一，决定 K8s 对象名（{@code <slug>-<key>}）。 */
@Data
public class AppNodeDTO {

    private String key;

    /** SERVICE / WORKER（平台不支持 CronJob）。 */
    private String kind;

    private Map<String, Object> params;
}
