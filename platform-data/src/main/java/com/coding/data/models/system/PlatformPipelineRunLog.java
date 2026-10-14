package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/**
 * 一次运行里**一个节点**的日志（表 {@code platform_pipeline_run_log}）。
 *
 * <p>抓取的时机只有一个：**归档时**（以及取消前的那一次抢抓）。集群里的 Pod 随后就被删，
 * 所以这行是这份日志唯一的存续形式（spec §4.4）。
 *
 * <p>⚠️ 一行对应画布上的**一个节点**，不是 Tekton 的一个 step —— 本批每个节点只产出一个 step，
 * 所以两者一一对应；将来一个节点产出多个 step 时要重新想这个表。
 */
@Data
public class PlatformPipelineRunLog {

    /** ULIDGenerator.generateULID() */
    private String id;

    /** platform_pipeline_run.id */
    private String runId;

    /** 画布上的节点 id（= Tekton task 名 = Pod 里容器名的一部分） */
    private String nodeName;

    /** **单步上限见 platform.pipeline.log.max-bytes**，超出截断 */
    private String content;

    /** 1 = 内容被截断过（UI 上要标出来，别让用户以为日志就这么短） */
    private Integer truncated;

    private Integer sizeBytes;

    private Date createdAt;
}
