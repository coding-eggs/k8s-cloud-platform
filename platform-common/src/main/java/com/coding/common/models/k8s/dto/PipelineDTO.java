package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * 平台自己的**流水线定义**（表 {@code platform_pipeline}）。
 *
 * <p>⚠️ <b>它不是一个 K8s 对象</b> —— 集群里不留 {@code Pipeline}（spec §3.2）：
 * 每次运行才把 {@link #canvasJson} 渲染成 {@code PipelineSpec} 内联进 {@code PipelineRun}。
 * 所以 {@link #getApiPath()} 返回 null（本类没有 k8s-server 端点），它只是 CRUD 的载体。
 *
 * <p>继承 {@link BaseResources} 是为了白拿 clusterId / namespace / name 三个身份字段 ——
 * 流水线是**命名空间级**资源，与 Deployment/Service 同一个归属。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PipelineDTO extends BaseResources {

    @Schema(description = "ULIDGenerator 生成；create 时忽略")
    private String id;

    private String description;

    /** 画布：节点 + 连线。**流水线的唯一真相源**（含环检测在内的校验在保存时就做） */
    @Schema(description = "画布 JSON：{nodes:[{id,type,config}], edges:[{from,to}]}")
    private String canvasJson;

    @Schema(description = "只读")
    private String createdBy;

    @Schema(description = "只读")
    private Date createdAt;

    @Schema(description = "只读")
    private Date updatedAt;

    @Override
    public String getApiPath() {
        return null;               // 不是 K8s 资源：没有 k8s-server 端点
    }
}
