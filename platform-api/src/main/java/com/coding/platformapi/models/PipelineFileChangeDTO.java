package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 一个变更文件 —— {@code git diff --name-status} 的等价物加增删行数。
 *
 * <p>⚠️ <b>不落库</b>：文件清单体积不可控（一次格式化能改几千个文件），
 * 历史运行要看时按 {@code sha/baseSha} 现算（见设计文档 §4）。这里只是预览/详情的一次性出参。
 *
 * @param path      文件路径（删除的是被删的那个路径）
 * @param status    {@code A} 新增 / {@code M} 修改 / {@code D} 删除 / {@code R} 改名 / {@code C} 复制
 * @param oldPath   改名/复制时的原路径，其余为 null
 * @param additions 新增行数
 * @param deletions 删除行数
 * @param binary    二进制文件：数不出行数（{@code additions/deletions} 都是 0，界面显示「二进制」）
 */
@Schema(description = "一个变更文件（状态 + 增删行数）")
public record PipelineFileChangeDTO(
        String path,
        String status,
        String oldPath,
        int additions,
        int deletions,
        boolean binary) {
}
