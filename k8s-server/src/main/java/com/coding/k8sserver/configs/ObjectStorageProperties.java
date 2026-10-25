package com.coding.k8sserver.configs;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储（SeaweedFS 组件）在集群侧的定位配置，见 application.yml 的 {@code platform.object-storage}。
 *
 * <p>命名空间 / Secret 名 / Deployment 名与 chart 的 recipe 一一对应；抽到 YAML 是为了
 * 换命名空间或做多套并存时不用改代码。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "platform.object-storage")
public class ObjectStorageProperties {

    /** SeaweedFS 组件安装的命名空间（与 ComponentRecipeCatalog.SEAWEEDFS_RELEASES 一致） */
    private String namespace = "platform-storage";

    /** 平台接管的 S3 identities 配置文件 Secret（chart 的 s3.existingConfigSecret） */
    private String configSecret = "platform-seaweedfs-s3-config";

    /** chart 里 S3 网关的 Deployment 名（fullnameOverride=seaweedfs） */
    private String s3Deployment = "seaweedfs-s3";

    // 注意：S3 的**地址**不在这里。它是调用方（platform-api）按集群配置下发的参数；
    // 本服务不自己决定连哪个 S3（k8s-server 是按 kubeconfig 干活的适配层，零业务语义）。
}
