package com.coding.platformapi.models;

import java.util.Map;

/** 应用设置：settings 为期望值，live 为线上快照里的值（未发布过 / 没设置为空 Map），version 为乐观锁 */
public record AppSettingsView(Map<String, Object> settings, Map<String, Object> live, Integer version) {
}
