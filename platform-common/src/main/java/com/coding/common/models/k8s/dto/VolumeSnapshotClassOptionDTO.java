package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * VolumeSnapshotClass <b>窄投影</b>：租户创建/恢复快照时要用的四个字段。
 *
 * <p>与 {@link StorageClassProvisionerDTO} 同理：/volumesnapshotclasses/** 是 PLATFORM 边界，租户读不到；
 * 而完整的 {@link VolumeSnapshotClassDTO} 带 {@code parameters}（可能引用驱动的密钥），不能给租户。
 * <p>非 {@code BaseResources} 子类：没有 apiPath，不是资源 CRUD 的载体。
 */
@Data
@Schema(description = "快照类窄投影（name / driver / deletionPolicy / isDefault）")
public class VolumeSnapshotClassOptionDTO {

    private String name;

    /** 与存储类的 provisioner 相同才能给该存储类的 PVC 打快照 */
    private String driver;

    /** Delete / Retain：删快照时存储后端的数据去留 */
    private String deletionPolicy;

    private Boolean isDefault;
}
