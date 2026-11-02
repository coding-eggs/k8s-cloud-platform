package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * VolumeSnapshotClass DTO（snapshot.storage.k8s.io/v1，集群级，平台只读）。
 * <p>{@link #parameters} 可能引用驱动的密钥，只在平台侧返回；租户侧走窄投影
 * {@link VolumeSnapshotClassOptionDTO}，那里没有这个字段。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class VolumeSnapshotClassDTO extends BaseResources {

    /** driver：与存储类的 provisioner 相同才能给该存储类的 PVC 打快照 */
    private String driver;

    /** deletionPolicy：Delete / Retain */
    private String deletionPolicy;

    /** 注解 snapshot.storage.kubernetes.io/is-default-class=true */
    private Boolean isDefault;

    private Map<String, String> parameters;

    /** metadata.creationTimestamp */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/volumesnapshotclasses";
    }

}
