package com.coding.platformapi.models;

import java.util.Map;

/** 一个服务的期望配置；kind 由端口推导（有端口 SERVICE，无端口 WORKER）。 */
public record AppServiceView(String key, String kind, Map<String, Object> desired,
                             boolean pendingDelete, Integer version) {
}
