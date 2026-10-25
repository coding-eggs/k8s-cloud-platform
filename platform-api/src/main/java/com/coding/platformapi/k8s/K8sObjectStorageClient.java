package com.coding.platformapi.k8s;

import com.coding.common.models.objectstorage.ObjectBucketAnonymousRequestDTO;
import com.coding.common.models.objectstorage.ObjectBucketDTO;
import com.coding.common.models.objectstorage.ObjectBucketRequestDTO;
import com.coding.common.models.objectstorage.ObjectBucketQuotaRequestDTO;
import com.coding.common.models.objectstorage.ObjectBucketStatDTO;
import com.coding.common.models.objectstorage.ObjectBucketStatsRequestDTO;
import com.coding.common.models.objectstorage.ObjectCredentialDTO;
import com.coding.common.models.objectstorage.ObjectCredentialGetRequestDTO;
import com.coding.common.models.objectstorage.ObjectCredentialRequestDTO;
import com.coding.common.models.objectstorage.ObjectCredentialRevokeRequestDTO;
import com.coding.common.models.objectstorage.ObjectDeleteRequestDTO;
import com.coding.common.models.objectstorage.ObjectListRequestDTO;
import com.coding.common.models.objectstorage.ObjectListResultDTO;
import com.coding.common.models.objectstorage.ObjectStatDTO;
import com.coding.common.models.objectstorage.ObjectStatRequestDTO;
import com.coding.common.models.objectstorage.SeaweedS3ConfigRequestDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对象存储的集群侧调用（k8s-server {@code /object-storage/**}）。
 *
 * <p>k8s-server 用平台自己的 {@code platform-admin} 身份访问 S3；本层只传 clusterId + endpoint，
 * 不接触任何 AK/SK（凭据只在「查看密钥」时按需取回）。
 */
@Component
@RequiredArgsConstructor
public class K8sObjectStorageClient {

    private final K8sServerGateway gateway;

    /** 组件安装前：确保平台 S3 配置文件存在（含 platform-admin 身份） */
    public void ensureConfig(String clusterId) {
        SeaweedS3ConfigRequestDTO body = new SeaweedS3ConfigRequestDTO();
        body.setClusterId(clusterId);
        gateway.exchange(HttpMethod.POST, "/object-storage/config/ensure", null, body,
                gateway.responseType(Void.class));
    }

    public List<ObjectBucketDTO> listBuckets(String clusterId, String endpoint) {
        return gateway.exchange(HttpMethod.POST, "/object-storage/buckets/list", null,
                new ObjectBucketRequestDTO(clusterId, endpoint, null),
                gateway.listResponseType(ObjectBucketDTO.class));
    }

    /** 批量查桶用量：每个桶由 k8s-server 递归列举求和（有上限，到顶标 truncated） */
    public List<ObjectBucketStatDTO> bucketStats(String clusterId, String endpoint, List<String> buckets) {
        return gateway.exchange(HttpMethod.POST, "/object-storage/buckets/stats", null,
                new ObjectBucketStatsRequestDTO(clusterId, endpoint, buckets),
                gateway.listResponseType(ObjectBucketStatDTO.class));
    }

    /** 设置桶容量上限（SeaweedFS 的每桶 quota；null/<=0 = 清除） */
    /** 开/关桶的匿名读（改 S3 的 anonymous 身份；会滚动网关） */
    public void setAnonymousRead(String clusterId, String bucket, boolean enabled) {
        gateway.exchange(HttpMethod.POST, "/object-storage/buckets/anonymous", null,
                new ObjectBucketAnonymousRequestDTO(clusterId, bucket, enabled),
                gateway.responseType(Void.class));
    }

    public void setBucketQuota(String clusterId, String endpoint, String bucket, Long quotaBytes) {
        gateway.exchange(HttpMethod.POST, "/object-storage/buckets/quota", null,
                new ObjectBucketQuotaRequestDTO(clusterId, endpoint, bucket, quotaBytes),
                gateway.responseType(Void.class));
    }

    public void createBucket(String clusterId, String endpoint, String bucket) {
        gateway.exchange(HttpMethod.POST, "/object-storage/buckets/create", null,
                new ObjectBucketRequestDTO(clusterId, endpoint, bucket), gateway.responseType(Void.class));
    }

    public void deleteBucket(String clusterId, String endpoint, String bucket) {
        gateway.exchange(HttpMethod.POST, "/object-storage/buckets/delete", null,
                new ObjectBucketRequestDTO(clusterId, endpoint, bucket), gateway.responseType(Void.class));
    }

    public ObjectListResultDTO listObjects(String clusterId, String endpoint, String bucket,
                                           String prefix, String after, Integer maxKeys, String delimiter) {
        return gateway.exchange(HttpMethod.POST, "/object-storage/objects/list", null,
                new ObjectListRequestDTO(clusterId, endpoint, bucket, prefix, after, maxKeys, delimiter),
                gateway.responseType(ObjectListResultDTO.class));
    }

    public ObjectStatDTO statObject(String clusterId, String endpoint, String bucket, String key) {
        return gateway.exchange(HttpMethod.POST, "/object-storage/objects/stat", null,
                new ObjectStatRequestDTO(clusterId, endpoint, bucket, key),
                gateway.responseType(ObjectStatDTO.class));
    }

    /** 流式下载：k8s-server 直接吐对象字节，本层原样透传到浏览器的输出流 */
    public void download(String clusterId, String endpoint, String bucket, String key, OutputStream out) {
        Map<String, String> params = new HashMap<>();
        params.put("clusterId", clusterId);
        if (endpoint != null && !endpoint.isBlank()) {
            params.put("endpoint", endpoint);
        }
        params.put("bucket", bucket);
        params.put("key", key);
        gateway.streamGet("/object-storage/objects/download", params, out);
    }

    /**
     * 上传单个对象：请求体是**原始流**（不缓冲、不 multipart），{@code size} 是调用方给的字节数
     * （浏览器传的是 File.size），下游 MinIO putObject 需要它。
     */
    public void uploadObject(String clusterId, String endpoint, String bucket, String key,
                             String contentType, long size, InputStream in) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("clusterId", clusterId);
        params.put("endpoint", endpoint);
        params.put("bucket", bucket);
        params.put("key", key);
        params.put("size", String.valueOf(size));
        if (contentType != null && !contentType.isBlank()) {
            params.put("contentType", contentType);
        }
        gateway.exchangeStreamBody("/object-storage/objects/upload", params, in, size,
                gateway.responseType(Void.class));
    }

    /** 删除单个对象（v1 不做按前缀批量删） */
    public void deleteObject(String clusterId, String endpoint, String bucket, String key) {
        gateway.exchange(HttpMethod.POST, "/object-storage/objects/delete", null,
                new ObjectDeleteRequestDTO(clusterId, endpoint, bucket, key),
                gateway.responseType(Void.class));
    }

    public String createCredential(String clusterId, String endpoint, String identityName,
                                   String secretName, List<String> actions) {
        return gateway.exchange(HttpMethod.POST, "/object-storage/credentials/create", null,
                new ObjectCredentialRequestDTO(clusterId, endpoint, identityName, secretName, actions),
                gateway.responseType(String.class));
    }

    public ObjectCredentialDTO getCredential(String clusterId, String secretName) {
        return gateway.exchange(HttpMethod.POST, "/object-storage/credentials/get", null,
                new ObjectCredentialGetRequestDTO(clusterId, secretName),
                gateway.responseType(ObjectCredentialDTO.class));
    }

    public void revokeCredential(String clusterId, String endpoint, String identityName, String secretName) {
        gateway.exchange(HttpMethod.POST, "/object-storage/credentials/revoke", null,
                new ObjectCredentialRevokeRequestDTO(clusterId, endpoint, identityName, secretName),
                gateway.responseType(Void.class));
    }
}
