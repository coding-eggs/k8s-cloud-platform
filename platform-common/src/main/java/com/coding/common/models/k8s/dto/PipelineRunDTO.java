package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 一次流水线运行（集群里的 {@code PipelineRun}，**命名空间级、租户域**）。
 * 端点走 k8s-server {@code /pipelineruns}（分配表三元组边界）。
 *
 * <p>⚠️ 本 DTO **只建模读出与取消需要的东西**。真正喂给集群的 {@code PipelineSpec} 是
 * {@code PipelineRunOperations} 用渲染器现渲染、**内联**进 {@code spec.pipelineSpec} 的，
 * 不走这个 DTO（spec §3.2：集群里不留 {@code Pipeline} 对象）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PipelineRunDTO extends BaseResources {

    /** create 时用：内联的 PipelineSpec 的 JSON（由渲染器产出后序列化） */
    @Schema(description = "内联的 PipelineSpec（JSON）。create 时必填")
    private String pipelineSpecJson;

    /** 平台的流水线 id —— 用于运行历史列表按流水线过滤 */
    @Schema(description = "平台侧流水线 id")
    private String pipelineId;

    /** RUNNING / SUCCEEDED / FAILED / CANCELLED / TIMEOUT（只读，converter 填） */
    private String status;

    private String reason;

    private String startTime;

    private String completionTime;

    @Override
    public String getApiPath() {
        return "/pipelineruns";
    }
}
