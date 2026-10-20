package com.coding.common.models.pipeline;

import java.util.List;

/**
 * 待重试工作区的列表结果。
 *
 * @param count      块数
 * @param totalBytes 总请求容量（基础单位：字节）—— 前端格式化成 GiB 显示
 */
public record PipelineRetryWorkspacesDTO(int count, long totalBytes,
                                         List<PipelineRetryWorkspaceDTO> items) {
}
