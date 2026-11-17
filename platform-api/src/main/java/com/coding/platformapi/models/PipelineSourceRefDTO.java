package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 一个 git 节点在**某次预览/某次运行**里的源码信息。
 *
 * <p>两个用处，同一份形状：
 * <ul>
 *   <li>触发前预览（{@code POST /pipeline/run/preview}）—— 给用户看「这次会构建哪个提交」；</li>
 *   <li>触发时落库（{@code platform_pipeline_run.source_json}）—— 归档之后仍能回答
 *       「这次/上次构建的是哪个提交」。归档会把集群里的 PipelineRun / TaskRun / Pod 全删掉，
 *       所以这份元数据必须在触发那一刻就写进库里。</li>
 * </ul>
 *
 * <p>⚠️ <b>落库的只有元数据，没有文件清单</b>：清单体积不可控，历史运行要看时按
 * {@code sha/baseSha} 现算（见设计文档 §4）。{@code files} 只在「查看变更」那一次请求里带回来。
 *
 * @param nodeId      画布节点 id（= Tekton task 名）
 * @param repo        用户填的仓库地址
 * @param revision    用户填的分支/标签；空 = 默认分支
 * @param kind        {@code branch} / {@code tag} / {@code default} / {@code pinned}（钉住的 sha）
 * @param sha         完整 commit sha（解析成功才有）
 * @param shortSha    前 7 位，界面展示用
 * @param subject     提交标题（浅取到 commit 对象才有）
 * @param author      提交作者
 * @param committedAt 提交时间（UTC ISO-8601）
 * @param baseSha     对比基线：最近一次**成功**运行用的同一个节点的 sha，没有则为父提交
 * @param baseKind    {@code last-success} / {@code parent} / {@code none}
 * @param baseRunName 基线来自哪次运行（{@code last-success} 时有）
 * @param files       变更文件清单（只有 {@code withDiff} 的请求才有；受 {@code truncated} 限制）
 * @param fileCount   变更文件**总数**（可能大于 {@code files.size()}）
 * @param additions   全部变更的新增行数
 * @param deletions   全部变更的删除行数
 * @param truncated   文件太多，{@code files} 只给了前 N 个
 * @param warning     sha 拿到了、但附加信息（提交标题/变更清单）不可用时的原因
 * @param error       这条节点**根本没解析出来**的原因（仓库不可达 / 分支不存在 / 没填地址）
 */
@Schema(description = "流水线 git 节点的源码信息（预览与运行详情共用）")
public record PipelineSourceRefDTO(
        String nodeId,
        String repo,
        String revision,
        String kind,
        String sha,
        String shortSha,
        String subject,
        String author,
        String committedAt,
        String baseSha,
        String baseKind,
        String baseRunName,
        List<PipelineFileChangeDTO> files,
        int fileCount,
        int additions,
        int deletions,
        boolean truncated,
        String warning,
        String error) {

    /** 解析失败的形状：只有身份与原因，没有 sha。 */
    public static PipelineSourceRefDTO failed(String nodeId, String repo, String revision, String error) {
        return new PipelineSourceRefDTO(nodeId, repo, revision, null, null, null, null, null,
                null, null, "none", null, null, 0, 0, 0, false, null, error);
    }

    /**
     * 知道 sha、但拿不到提交信息/变更清单时的形状 —— 元数据照常给，原因进 {@code warning}。
     *
     * <p>与 {@link #failed} 的区别很重要：这条节点**能跑**（sha 是真实的），只是缺少展示用的细节。
     */
    public static PipelineSourceRefDTO resolved(String nodeId, String repo, String revision,
                                                   String kind, String sha, String shortSha,
                                                   String baseSha, String baseKind, String baseRunName,
                                                   String warning) {
        return new PipelineSourceRefDTO(nodeId, repo, revision, kind, sha, shortSha, null, null,
                null, baseSha, baseKind, baseRunName, null, 0, 0, 0, false, warning, null);
    }
}
