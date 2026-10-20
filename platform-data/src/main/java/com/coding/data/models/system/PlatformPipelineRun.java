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

    /**
     * 触发那一刻的画布 JSON。**只写一次**（触发时），归档回写不碰它。
     *
     * <p>存在的理由：流水线定义**之后还会被改**（改名字、换镜像、删节点），而集群里的
     * {@code PipelineRun} 归档后就被删了。没有这一份，历史运行详情画出来的是**今天的**流水线 ——
     * 用户看到的名字与当年跑的对不上（2026-10-15 加）。
     */
    private String canvasJson;

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

    /**
     * 归档那一刻抓下来的 {@code PipelineRun} YAML。**只写一次**（归档时），之后不再变。
     *
     * <p>为什么必须在那一刻抓：同一时刻 k8s-server 就把集群里的对象删了（spec §4.1），
     * 而「当年跑的到底是什么」只有那一瞬间能拿到。事后想补，只能拿画布重渲染 ——
     * 那对引用了连接的流水线根本渲染不出来（节点拒绝在没有凭据的情况下渲染），
     * 而且会画出一份**当年没跑过**的东西。运行详情的 YAML 就是从这里读的（2026-10-15 加）。
     */
    private String runYaml;

    /** 重试来源运行 id；NULL = 首跑。 */
    private String parentRunId;

    /** 同一条重试链的第几次尝试；首跑 = 1。 */
    private Integer attemptNo;

    /** 用户点的那个断点（画布节点 id）。范围不存这里 —— 由 steps_json 与画布共同还原。 */
    private String retryFromNode;

    /** 本次运行**实际不跑**的节点集合（JSON 数组，闭包展开后）。首跑与重试各一份。 */
    private String maskedNodesJson;

    /** 重试时沿用的节点（JSON 数组，父运行里已成功）。首跑为 NULL。 */
    private String reusedNodesJson;

    /** 本次运行（链）使用的共享工作区 PVC 名。成功即删；失败保留到 TTL。 */
    private String workspacePvc;

    private Date createdAt;
}
