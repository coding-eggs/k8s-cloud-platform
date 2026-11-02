package com.coding.platformapi.models;

import com.coding.common.models.k8s.dto.VolumeSnapshotClassOptionDTO;

import java.util.List;

/**
 * 卷快照门控 + 表单候选（{@code POST /volumesnapshots/capability}）。
 *
 * @param available      CRD 已装且至少有一个快照类
 * @param reason         不可用的原因：{@code NO_CRD} / {@code NO_CLASS}；可用时为 null
 * @param classes        快照类窄投影
 * @param storageClasses 每个存储类按 {@code provisioner == driver} 匹配到的快照类
 */
public record VolumeSnapshotCapability(boolean available,
                                       String reason,
                                       List<VolumeSnapshotClassOptionDTO> classes,
                                       List<StorageClassEntry> storageClasses) {

    public static final String NO_CRD = "NO_CRD";

    public static final String NO_CLASS = "NO_CLASS";

    /** 一个存储类及能给它的 PVC 打快照的快照类名（为空 = 这个存储类不支持快照） */
    public record StorageClassEntry(String name, String provisioner, List<String> snapshotClasses) {
    }
}
