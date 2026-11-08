package com.coding.platformapi.models;

import java.util.Map;

/**
 * 一个服务的期望配置；kind 由端口推导（有端口 SERVICE，无端口 WORKER）。
 * ingressPort：网关方式分到的对外端口（锁在分配表里，不在 desired 里）；没有为 null。
 */
public record AppServiceView(String key, String kind, Map<String, Object> desired,
                             boolean pendingDelete, Integer version, Integer ingressPort) {
}
