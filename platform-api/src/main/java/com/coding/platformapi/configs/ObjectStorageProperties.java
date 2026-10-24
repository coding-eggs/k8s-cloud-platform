package com.coding.platformapi.configs;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储（SeaweedFS 组件）的平台侧配置，见 application.yaml 的 {@code platform.object-storage}。
 *
 * <p>这些值以前硬编码在 Service 里，集群/环境不同就得改代码；现在统一放 YAML，
 * 可用环境变量覆盖（组件安装表单里还能再按集群覆盖 S3 地址）。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "platform.object-storage")
public class ObjectStorageProperties {

    /**
     * 平台后端访问 S3 的默认地址（组件安装参数 {@code s3Endpoint} 的默认值）。
     *
     * <p>平台与 SeaweedFS 同集群时用集群内地址；业务集群 / 平台跑在集群外时，
     * 在组件表单里填暴露后的地址（NodePort / LB / port-forward）。
     */
    private String defaultEndpoint = "http://seaweedfs-s3.platform-storage:8333";

    /** 未给某租户单独设置时，每租户的桶数上限 */
    private int defaultMaxBuckets = 10;

    /** 控制台单次下载上限（MB）；超过提示用 SDK 直连 */
    private int maxDownloadMb = 100;
}
