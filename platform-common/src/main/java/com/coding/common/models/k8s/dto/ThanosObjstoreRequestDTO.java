package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * Thanos 对象存储配置的下发参数（S3.6）。
 *
 * <p>凭证不进这个请求：{@code secretName} 指向集群里已有的凭证 Secret，
 * k8s-server 在集群内读它、拼出完整的 {@code objstore.yml}、再写成 chart 挂载的 Secret。
 * 凭证数据不会经过 platform-api，也不会进操作记录的 values 快照。
 */
@Data
public class ThanosObjstoreRequestDTO {
    private String clusterId;
    /** **凭证** Secret 所在的命名空间（可以在任意 ns） */
    private String namespace;
    /** 已有凭证 Secret 的名字（含 access key / secret key） */
    private String secretName;
    /** MinIO endpoint（host:port，可带 http(s):// 前缀，后端会归一化） */
    private String endpoint;
    /** 桶名 */
    private String bucket;
    /** 自签证书时跳过校验 */
    private boolean insecureSkipVerify;
    /** 生成的 objstore Secret 落在哪个命名空间 —— 必须与 Thanos 的部署命名空间一致（Pod 只能挂本 ns 的 Secret） */
    private String targetNamespace;
    /** 要写入的 Secret 名（chart 的 global.objstore.secretName 指向它） */
    private String targetSecretName;
}
