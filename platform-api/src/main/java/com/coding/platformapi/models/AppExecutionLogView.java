package com.coding.platformapi.models;

import java.util.Date;

/** 发布过程日志的一行；message 可能多行（健康检查逐对象 / 逐 Pod 列出）。 */
public record AppExecutionLogView(Long id, String stage, String level, String message, Date createdAt) {
}
