package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/**
 * 一次流水线运行（表 {@code platform_pipeline_run}）。
 *
 * <p>设计与迁移见 docs/superpowers/specs/2026-10-15-ci-tekton-design.md §4 与
 * {@code V2026_10_15_1__pipeline.sql}。
 *
 * <p><b>本表是运行历史的唯一来源</b>：集群里的 {@code PipelineRun} 归档后会被删掉
 * （连 TaskRun 与 Pod 一起），所以读历史一律读这里，不读集群（spec §4.3 的分界线就是
 * 「这个 run 归档了没有」）。
 *
 * <p><b>{@link #runName} 是归档幂等键</b>：集群里的 PipelineRun 名，**由平台先生成**
 * （spec §4.5①：先生成名字 → 幂等键从源头就有，也不会出现「集群里有对象、库里没记录」的孤儿）。
 * 它在库里有**全局唯一索引**（uk_run_name），所以生成时必须带时间戳 + 随机后缀。
 *
 * <p><b>{@link #startTime} / {@link #completionTime} 是字符串</b>：存 Tekton 给的 RFC3339 原文，
 * 便于排查；代价是字符串比较只在格式统一时才等价于时间比较（保留清理那一侧在 Java 里 parse）。
 */
@Data
public class PlatformPipelineRun {

    /** ULIDGenerator.generateULID() */
    private String id;

    private String pipelineId;

    private String clusterId;

    private String namespace;

    /** 集群里的 PipelineRun 名；**全局唯一**（归档幂等键） */
    private String runName;

    /** RUNNING / SUCCEEDED / FAILED / CANCELLED / TIMEOUT */
    private String status;

    private String reason;

    private String triggeredBy;

    /** RFC3339 原文 */
    private String startTime;

    /** RFC3339 原文 */
    private String completionTime;

    /** 每个节点的状态与耗时（画布节点 id → 状态），归档时抓下来的快照 */
    private String stepsJson;

    private Date createdAt;
}
