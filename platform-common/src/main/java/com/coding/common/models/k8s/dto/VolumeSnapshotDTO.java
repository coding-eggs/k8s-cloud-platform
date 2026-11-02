package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * VolumeSnapshot DTO（snapshot.storage.k8s.io/v1，命名空间级）。
 * <p>写入只用 spec 两项（来源 PVC、快照类）；其余字段只在查询时返回，取自 status / metadata。
 * 快照创建后 spec 不可变，所以没有更新。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class VolumeSnapshotDTO extends BaseResources {

    /** spec.source.persistentVolumeClaimName */
    private String sourcePvcName;

    /** spec.volumeSnapshotClassName；创建时由 platform-api 解析后写入 */
    private String volumeSnapshotClassName;

    /** status.readyToUse；status 还没出现时为 null */
    private Boolean readyToUse;

    /** status.restoreSize，字节；驱动可能不报（null）或报 0 */
    private BigDecimal restoreSize;

    /** status.error.message */
    private String errorMessage;

    /** status.error.time */
    private String errorTime;

    /** status.boundVolumeSnapshotContentName */
    private String boundContentName;

    /** status.creationTime：存储后端真正打下快照的时间 */
    private String snapshotTime;

    /** metadata.creationTimestamp */
    private String creationTime;

    /** metadata.deletionTimestamp：非空表示正在删除 */
    private String deletionTimestamp;

    @Override
    public String getApiPath() {
        return "/volumesnapshots";
    }

}
