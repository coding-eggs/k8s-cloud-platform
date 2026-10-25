package com.coding.platformapi.configs;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储（SeaweedFS 组件）的平台侧配置，见 application.yaml 的 {@code platform.object-storage}。
 *
 * <p>这里只有租户配额与下载上限这类**平台策略**。
 * 平台访问某个集群 S3 的**地址**不在这里：它是每集群一条连接配置
 * （{@code k8s_cluster.object_storage_url}，集群管理里填），没配则按组件安装位置推导。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "platform.object-storage")
public class ObjectStorageProperties {

    /** 未给某租户单独设置时，每租户的桶数上限 */
    private int defaultMaxBuckets = 10;

    /** 控制台单次下载上限（MB）；超过提示用 SDK 直连 */
    private int maxDownloadMb = 100;

    /** 控制台单次上传上限（MB）；超过提示用 SDK 直连 */
    private int maxUploadMb = 100;
}
