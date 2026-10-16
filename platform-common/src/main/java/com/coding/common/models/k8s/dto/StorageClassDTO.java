package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * StorageClass DTO（集群级资源，无命名空间）。
 * 平台侧只读：供工作负载编辑器下拉选择 storageClassName；存储类本身由集群/基础设施管理。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StorageClassDTO extends BaseResources {

    /** 供应方驱动（如 standard、kubernetes.io/aws-ebs） */
    private String provisioner;

    /** 回收策略：Retain / Delete */
    private String reclaimPolicy;

    /** 是否允许扩容（仅查询返回） */
    private Boolean allowVolumeExpansion;

    /**
     * **真能不能在线扩容**（仅查询返回）—— 三态，值是 {@code unsupported} / {@code csi} / {@code unbacked}。
     *
     * <p>⚠️ 为什么不直接看 {@link #allowVolumeExpansion}：那个字段只表示「**允许**扩」，
     * 而真正执行扩容的是 CSI 驱动的 {@code external-resizer}。非 CSI 的 provisioner
     * （nfs-subdir、local-path 这类）**不会有人执行** —— 改了 {@code spec} 也只是把请求改大，
     * {@code status.capacity} 一动不动，而且**连一个条件都不会挂**（没人在试，也就没人报错）。
     * 2026-10-16 真撞到：集群两个 SC 都写着 {@code allowVolumeExpansion: true}，两个都做不到。
     *
     * <p>判据见 {@code CoreV1StorageClassOperations} —— 那里是唯一算它的地方。
     */
    private String expansion;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/storageclasses";
    }

}
