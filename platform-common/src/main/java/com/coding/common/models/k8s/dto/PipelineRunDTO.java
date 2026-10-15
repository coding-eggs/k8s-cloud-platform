package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 一次流水线运行（集群里的 {@code PipelineRun}，**命名空间级、租户域**）。
 * 端点走 k8s-server {@code /pipelineruns}（分配表三元组边界）。
 *
 * <p>⚠️ 本 DTO 只建模读出、创建与取消需要的东西。
 *
 * <p>⚠️ <b>创建时带的是「画布」，不是渲染结果</b>（spec §7 的改定块）：
 * platform-api 不依赖 k8s-core/fabric8，所以「画布 → PipelineSpec」由 **k8s-server** 做
 * （{@code PipelineRunOperations.create} 里调渲染器），内联进 {@code spec.pipelineSpec}。
 * 集群里仍然不留 {@code Pipeline} 对象。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PipelineRunDTO extends BaseResources {

    /** create 时用：平台自己的**画布 JSON**。由 k8s-server 渲染成内联的 PipelineSpec */
    @Schema(description = "画布 JSON。create 时必填")
    private String pipelineCanvasJson;

    /**
     * 本次运行可用的连接（**已授权、已解密**）。只有 create 那条路径会带。
     *
     * <p>⚠️ 它们是给 k8s-server 建 Secret 用的（凭据以文件形式挂进 Pod），
     * **绝不进渲染结果** —— 预览页会把渲染结果原样给用户看。
     */
    @Schema(description = "画布里引用到的连接（含凭据）。仅 create 时传")
    private java.util.List<com.coding.common.models.pipeline.PipelineConnectionRef> connections;

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
