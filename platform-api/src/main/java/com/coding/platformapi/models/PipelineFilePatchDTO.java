package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 单个文件的**补丁正文**（点开变更清单里某一行时按需取）。
 *
 * <p>与 {@link PipelineFileChangeDTO} 的分工：那个是「改了哪些文件、各多少行」（一次请求拿全），
 * 这个是「这个文件具体改了哪几行」（按文件按需取）。补丁可能很大，所以**永远不跟着清单一起回**。
 *
 * @param path      文件路径
 * @param text      unified diff 正文（{@code @@ ... @@} 开头）；二进制文件为 null
 * @param truncated 行数超出上限，只给了前 N 行
 * @param binary    二进制文件：没有可读的补丁
 * @param error     取不到补丁的原因（仓库不可达 / 连接不可用 / 自建源不许按 sha 取）
 */
@Schema(description = "单个文件的补丁正文")
public record PipelineFilePatchDTO(
        String path,
        String text,
        boolean truncated,
        boolean binary,
        String error) {

    public static PipelineFilePatchDTO failed(String path, String error) {
        return new PipelineFilePatchDTO(path, null, false, false, error);
    }
}
