package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * 平台告警联动的下发参数（S3.6 批② 补）。
 *
 * <p>为什么单独一个端点而不是塞进 helm values：token 是**凭据**，进 values 就会落进
 * {@code platform_component_operation.values_snapshot}（DB 明文）和 helm release secret。
 * 这里只在 platform-api → k8s-server 之间传一次，由 k8s-server 写成集群里的 Secret。
 *
 * <p>{@code token} 由 platform-api **剥掉 {@code Bearer } 前缀**后传入：
 * AlertmanagerConfig 的 {@code httpConfig.authorization.type=Bearer} 会自己拼前缀，
 * 两边都带就成了 {@code Authorization: Bearer Bearer xxx}，平台侧校验不过。
 */
@Data
public class PlatformAlertingRequestDTO {
    private String clusterId;
    /** 与 Alertmanager 同命名空间（prometheus-operator 的 recipe 是 monitor） */
    private String namespace;
    /** 完整 webhook 地址，已带 {@code ?clusterId=...} */
    private String webhookUrl;
    /** 共享密钥（不含 Bearer 前缀） */
    private String token;
}
