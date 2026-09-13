package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "ClusterTrustBundle 投影（把集群信任根的 CA bundle 以自动更新文件形式注入；name 与 signerName+labelSelector 互斥）")
public class ClusterTrustBundleProjectionDTO {

    @Schema(description = "按对象名选择单个 ClusterTrustBundle（与 signerName/labelSelector 互斥）")
    private String name;

    @Schema(description = "按签名者名选择全部匹配的 ClusterTrustBundle（与 name 互斥）")
    private String signerName;

    @Schema(description = "标签选择器，仅在设置 signerName 时生效（与 name 互斥）")
    private LabelSelectorDTO labelSelector;

    @Schema(description = "引用的 ClusterTrustBundle 不可用时是否不阻塞 Pod 启动")
    private Boolean optional;

    @Schema(description = "卷根目录下的相对写入路径")
    private String path;

}
