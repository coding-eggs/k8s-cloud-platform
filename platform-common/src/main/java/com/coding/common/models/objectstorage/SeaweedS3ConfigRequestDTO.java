package com.coding.common.models.objectstorage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 确保平台的 S3 identities 配置文件存在（含 platform-admin 身份）。
 *
 * <p>组件安装前由 platform-api 调用：这是把 chart 自带的随机凭证换成「平台可管理」配置的入口。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeaweedS3ConfigRequestDTO {
    private String clusterId;
}
