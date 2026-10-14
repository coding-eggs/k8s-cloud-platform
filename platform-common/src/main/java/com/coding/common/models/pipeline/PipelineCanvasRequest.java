package com.coding.common.models.pipeline;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 「把这张画布渲染一下」的入参。k8s-server 的 {@code POST /pipelineruns/render} 收它。
 *
 * <p>⚠️ 住在 platform-common 是因为**两端都要看得见**：platform-api 发它（触发前的校验与预览），
 * k8s-server 收它。渲染本身在 k8s-server —— platform-api 不碰 fabric8（spec §7 的改定块）。
 */
public record PipelineCanvasRequest(
        @Schema(description = "画布 JSON：{nodes:[{id,type,config}], edges:[{from,to}]}")
        String canvasJson) {
}
