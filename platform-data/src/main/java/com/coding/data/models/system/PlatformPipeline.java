package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/**
 * 流水线定义（表 {@code platform_pipeline}）。
 *
 * <p>设计与迁移见 docs/superpowers/specs/2026-10-15-ci-tekton-design.md 与
 * {@code V2026_10_15_1__pipeline.sql}。
 *
 * <p><b>没有 tenant_id</b>：归属由「cluster_id + namespace」决定，租户身份走分配表
 * （{@code platform_tenant_namespace}）反查 —— 与 Deployment/Service 一样（spec §5.2）。
 *
 * <p><b>没有 deletedAt</b>：硬删除，与 {@link PlatformCredential} 同一条判断。
 */
@Data
public class PlatformPipeline {

    /** ULIDGenerator.generateULID() */
    private String id;

    private String clusterId;

    private String namespace;

    /** 同命名空间内唯一（唯一索引 uk_pipeline 保证） */
    private String name;

    private String description;

    /** 画布 JSON —— **流水线的唯一真相源** */
    private String canvasJson;

    private String createdBy;

    private Date createdAt;

    private Date updatedAt;
}
