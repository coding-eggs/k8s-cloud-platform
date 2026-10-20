package com.coding.common.models.pipeline;

/**
 * 一块「待重试工作区」（命名空间里保留中的平台工作区 PVC）。
 *
 * @param name         PVC 名（`platform-pipeline-ws-<runName>`）；清理时按它删
 * @param runName      这块卷属于哪条运行（来自标签）
 * @param createdAt    PVC 创建时间
 * @param retainUntil  保留到什么时候（注解原文，RFC3339 UTC）
 * @param sizeBytes    请求容量（基础单位：字节）
 */
public record PipelineRetryWorkspaceDTO(String name, String runName, String createdAt,
                                        String retainUntil, long sizeBytes) {
}
