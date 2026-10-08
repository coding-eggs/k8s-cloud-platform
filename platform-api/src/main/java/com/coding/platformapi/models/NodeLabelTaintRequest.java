package com.coding.platformapi.models;

import com.coding.common.models.k8s.dto.NodeTaintDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**节点标签 + Taint 整体替换请求体（平台侧请求模型，不下发 DTO、不入库）。 */
@Data
@Schema(description = "节点标签 + Taint 更新请求")
public class NodeLabelTaintRequest {

    @Schema(description = "节点标签（整体替换；null = 不改）")
    private Map<String, String> labels;

    @Schema(description = "节点 Taint（整体替换；null = 不改）")
    private List<NodeTaintDTO> taints;
}
