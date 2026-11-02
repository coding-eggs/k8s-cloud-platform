package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * VolumeSnapshotContent DTO（snapshot.storage.k8s.io/v1，集群级，平台只读）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class VolumeSnapshotContentDTO extends BaseResources {

    private String driver;

    /** deletionPolicy：Delete / Retain */
    private String deletionPolicy;

    private String volumeSnapshotClassName;

    /** spec.volumeSnapshotRef.name */
    private String snapshotName;

    /** spec.volumeSnapshotRef.namespace */
    private String snapshotNamespace;

    private Boolean readyToUse;

    /** status.restoreSize，字节 */
    private BigDecimal restoreSize;

    private String snapshotHandle;

    /**
     * 指向的快照已经不存在。k8s 层不填，由 platform-api 对照快照列表算出；
     * 对照失败时保持 null，不误标。
     */
    private Boolean orphan;

    /** metadata.creationTimestamp */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/volumesnapshotcontents";
    }

}
