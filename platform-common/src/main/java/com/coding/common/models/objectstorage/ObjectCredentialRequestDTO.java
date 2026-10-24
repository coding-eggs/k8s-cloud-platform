package com.coding.common.models.objectstorage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发放凭据：在 S3 配置里新增一个 identity（bucket-scoped actions），并把 AK/SK 写进 Secret。
 *
 * <p>AK/SK 由 k8s-server 生成，只返回 accessKey；secretKey 留在 Secret 里按权限现取。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectCredentialRequestDTO {
    private String clusterId;
    /** S3 网关地址（用于改完配置后滚动就绪判断；真正写配置不依赖它） */
    private String endpoint;
    /** identity 名（平台生成 bucket-<桶名>-<8 位>） */
    private String identityName;
    /** 存 AK/SK 的 Secret 名（platform-storage 命名空间） */
    private String secretName;
    /** bucket-scoped actions：Read:桶 / Write:桶 / List:桶 / Tagging:桶 */
    private java.util.List<String> actions;
}
