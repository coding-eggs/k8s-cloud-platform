package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code POST /volumesnapshotclasses/options} 的返回：快照 CRD 是否已装 + 快照类窄投影。
 *
 * <p>{@code crdInstalled=false} 只表示 apiserver 对快照类回了 404；其它失败照常报错，
 * 不会伪装成「没装」。
 */
@Data
@Schema(description = "快照类候选（含 CRD 是否已装）")
public class VolumeSnapshotClassOptionsDTO {

    private boolean crdInstalled;

    private List<VolumeSnapshotClassOptionDTO> classes = new ArrayList<>();
}
