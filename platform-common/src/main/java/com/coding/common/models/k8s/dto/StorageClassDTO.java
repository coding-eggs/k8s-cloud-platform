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

    /**
     * 供应参数（仅查询返回），如 NFS 的 {@code server} / {@code share}。
     * <p>与 provisioner、reclaimPolicy 一样创建后不可变 —— 组件编辑据此判断「改了会不会被 API server 拒」。
     */
    private java.util.Map<String, String> parameters;

    /** 是否允许扩容（仅查询返回） */
    private Boolean allowVolumeExpansion;

    /**
     * **真能不能在线扩容**（仅查询返回）—— 三态，值是 {@code unsupported} / {@code csi} / {@code unbacked}。
     *
     * <p>⚠️ 为什么不直接看 {@link #allowVolumeExpansion}：那个字段只表示「**允许**扩」，
     * 而真正执行扩容的是 CSI 驱动的 {@code external-resizer}。非 CSI 的 provisioner
     * （nfs-subdir、local-path 这类）**没有执行方** —— 改了 {@code spec} 也只是把请求改大，
     * {@code status.capacity} 一动不动，而且**连一个条件都不会挂**（没人在试，也就没人报错）。
     * 2026-10-16 真撞到：集群两个 SC 都写着 {@code allowVolumeExpansion: true}，两个都做不到。
     *
     * <p>判据见 {@code CoreV1StorageClassOperations} —— 那里是唯一算它的地方。
     */
    private String expansion;

    /**
     * 是否为集群默认存储类（仅查询返回）—— 注解 {@code storageclass.kubernetes.io/is-default-class} = "true"。
     * PVC 不写 {@code storageClassName} 时落到它；一个集群至多一个（平台在设置时清掉其它）。
     */
    private Boolean isDefault;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/storageclasses";
    }

}
