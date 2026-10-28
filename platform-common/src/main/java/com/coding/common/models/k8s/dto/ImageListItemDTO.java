package com.coding.common.models.k8s.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 镜像清单的一行：某个内置 chart 用到的镜像，以及它在平台 Harbor 里的对应地址。
 *
 * <p>{@code mirrored} 与 {@code upstream} 相同 = 这个镜像不改写（harbor 组件本身、或未配置仓库）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageListItemDTO {
    /** 相对 charts 根目录的 chart 路径（含子 chart） */
    private String chart;
    /** 上游镜像（chart 默认值里的写法） */
    private String upstream;
    /** 平台 Harbor 里的地址（未配置仓库时同 upstream） */
    private String mirrored;
}
