package com.coding.k8score.pipeline;

import java.util.List;

/**
 * 一个内置节点暴露给前端的元数据。**前端靠它渲染属性面板** —— 加字段不用改前端。
 *
 * <p>⚠️ 它住在 k8s-core（而不是 platform-common）：字段与预设就是 {@link NodeField} /
 * {@link BuildPreset} 本身，重新声明一份等价的传输类型只会多出一处要同步的形状。
 * 端点在 k8s-server（`POST /pipelineruns/node-types`），platform-api 只做透传。
 */
public record PipelineNodeTypeView(String type, List<NodeField> fields, List<BuildPreset> presets) {
}
