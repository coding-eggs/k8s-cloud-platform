package com.coding.k8sserver.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.objectstorage.ObjectBucketDTO;
import com.coding.common.models.objectstorage.ObjectBucketStatDTO;
import com.coding.common.models.objectstorage.ObjectCredentialDTO;
import com.coding.common.models.objectstorage.ObjectListItemDTO;
import com.coding.common.models.objectstorage.ObjectListResultDTO;
import com.coding.common.models.objectstorage.ObjectStatDTO;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8score.operations.ServerSideApply;
import com.coding.k8sserver.components.SeaweedS3ConfigObjects;
import com.coding.k8sserver.components.SeaweedSigV4;
import com.coding.k8sserver.configs.ObjectStorageProperties;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;

/**
 * 对象存储（SeaweedFS）的 S3 控制面操作。
 *
 * <p><b>身份</b>：一律用配置里的 {@code platform-admin}（{@code ["Admin"]}）——平台自己的身份，
 * 不拿租户的 AK/SK 去做控制台查询。租户凭据只发给应用。
 *
 * <p><b>为什么 endpoint 是入参</b>：k8s-server 是按集群用 kubeconfig 访问的，**pod 网络不一定可达**
 * 目标集群的 ClusterIP（业务集群尤其如此）。所以地址由 platform-api 从组件快照带下来，
 * 默认是集群内地址；业务集群可以把 S3 暴露成 NodePort/LB 后在安装表单里改成外部地址。
 *
 * <p><b>改配置后的生效方式</b>：chart 把 Secret 内容算进 Pod 注解，但我们走的是「直接改 Secret」，
 * 所以改完必须滚动一次 {@code seaweedfs-s3}（{@link #rollS3}）。S3 网关无状态，数据不受影响。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeaweedS3Service {

    static final String ACCESS_KEY = "access_key";
    static final String SECRET_KEY = "secret_key";

    private final KubernetesClientFactory clientFactory;

    /** 命名空间 / 配置 Secret / Deployment 名 / 默认地址（见 application.yml 的 platform.object-storage） */
    private final ObjectStorageProperties properties;

    // ==================== 配置文件 ====================

    /** 组件安装前调用：确保配置 Secret 里有 platform-admin（已存在则原样保留，不轮换） */
    public void ensureConfigSecret(String clusterId) {
        ensureConfig(clientFactory.getAdminClient(clusterId));
    }

    private void ensureConfig(KubernetesClient client) {
        Secret existing = client.secrets().inNamespace(properties.getNamespace()).withName(properties.getConfigSecret()).get();
        if (existing != null && existing.getData() != null
                && existing.getData().containsKey(SeaweedS3ConfigObjects.CONFIG_KEY)) {
            return;
        }
        String json = SeaweedS3ConfigObjects.adminOnly(
                SeaweedS3ConfigObjects.randomAccessKey(), SeaweedS3ConfigObjects.randomSecretKey());
        applySecret(client, properties.getConfigSecret(), SeaweedS3ConfigObjects.CONFIG_KEY, json,
                "platform.coding/component", "seaweedfs");
        log.info("对象存储：已初始化平台 S3 配置文件 {}/{}", properties.getNamespace(), properties.getConfigSecret());
    }

    private String configJson(KubernetesClient client) {
        Secret secret = client.secrets().inNamespace(properties.getNamespace()).withName(properties.getConfigSecret()).get();
        if (secret == null || secret.getData() == null
                || !secret.getData().containsKey(SeaweedS3ConfigObjects.CONFIG_KEY)) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "对象存储的 S3 配置文件不存在（" + properties.getNamespace() + "/" + properties.getConfigSecret()
                            + "）：请先在「集群组件」里安装/重放对象存储");
        }
        return new String(Base64.getDecoder().decode(secret.getData().get(SeaweedS3ConfigObjects.CONFIG_KEY)),
                StandardCharsets.UTF_8);
    }

    /**
     * 确认运行中的 S3 网关**确实挂载了平台配置文件**。
     *
     * <p>升级前装的老部署挂的是 chart 自带的随机凭证 Secret，此时平台改配置不会生效 —— 必须先在
     * 组件页重放一次（recipe 已改成 {@code s3.existingConfigSecret}）。这里提前给出可执行的提示，
     * 而不是让用户看到「凭据发了但不生效」。
     */
    void assertPlatformConfigMounted(KubernetesClient client) {
        Deployment dep = client.apps().deployments().inNamespace(properties.getNamespace()).withName(properties.getS3Deployment()).get();
        if (dep == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "对象存储未安装（" + properties.getNamespace() + "/" + properties.getS3Deployment() + " 不存在）");
        }
        boolean mounted = dep.getSpec() != null && dep.getSpec().getTemplate() != null
                && dep.getSpec().getTemplate().getSpec() != null
                && dep.getSpec().getTemplate().getSpec().getVolumes() != null
                && dep.getSpec().getTemplate().getSpec().getVolumes().stream().anyMatch(v ->
                v.getSecret() != null && properties.getConfigSecret().equals(v.getSecret().getSecretName()));
        if (!mounted) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "对象存储仍在使用 chart 自带的 S3 配置；请先在「集群组件」里对对象存储执行一次"
                            + "「编辑/重放」，切换到平台管理的配置后再操作");
        }
    }

    // ==================== S3 客户端 ====================

    private MinioClient client(KubernetesClient k8s, String endpoint) {
        // 每次操作前确认网关挂的是平台配置文件：否则平台改配置不生效，只会得到一句没上下文的 403
        assertPlatformConfigMounted(k8s);
        String[] admin = SeaweedS3ConfigObjects.findCredentials(configJson(k8s),
                SeaweedS3ConfigObjects.ADMIN_IDENTITY);
        if (admin == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "S3 配置里缺少 " + SeaweedS3ConfigObjects.ADMIN_IDENTITY + " 身份");
        }
        if (endpoint == null || endpoint.isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "请求没有带对象存储地址：集群配置里的「对象存储地址」为空且平台未推导出地址");
        }
        String url = endpoint.trim();
        return MinioClient.builder().endpoint(url).credentials(admin[0], admin[1]).build();
    }

    // ==================== 桶 ====================

    public List<ObjectBucketDTO> listBuckets(String clusterId, String endpoint) {
        KubernetesClient k8s = clientFactory.getAdminClient(clusterId);
        try {
            return client(k8s, endpoint).listBuckets().stream()
                    .map(b -> new ObjectBucketDTO(b.name(),
                            b.creationDate() == null ? null : b.creationDate().toString()))
                    .toList();
        } catch (Exception e) {
            throw s3Error("列桶", e);
        }
    }

    /** 建桶；桶已存在且属于我们 → 视为成功（幂等） */
    public void createBucket(String clusterId, String endpoint, String bucket) {
        KubernetesClient k8s = clientFactory.getAdminClient(clusterId);
        MinioClient minio = client(k8s, endpoint);
        try {
            if (minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                return;
            }
            minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() == null ? "" : e.errorResponse().code();
            if ("BucketAlreadyOwnedByYou".equals(code) || "BucketAlreadyExists".equals(code)) {
                return;
            }
            throw s3Error("建桶", e);
        } catch (Exception e) {
            throw s3Error("建桶", e);
        }
    }

    /** 删桶；桶不存在视为成功（幂等）。非空桶会被 S3 拒绝，错误信息原样带回 */
    public void deleteBucket(String clusterId, String endpoint, String bucket) {
        KubernetesClient k8s = clientFactory.getAdminClient(clusterId);
        try {
            client(k8s, endpoint).removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() == null ? "" : e.errorResponse().code();
            if ("NoSuchBucket".equals(code)) {
                return;
            }
            throw s3Error("删桶", e);
        } catch (Exception e) {
            throw s3Error("删桶", e);
        }
    }

    /**
     * 单桶用量的列举上限：S3 **没有**"桶大小"这种 API，只能递归列举求和 ——
     * 对象多时很贵，所以到顶就停下并标 truncated（页面显示「≥」，不假装是精确值）。
     */
    static final int STATS_MAX_OBJECTS = 5000;

    /** 批量统计桶用量：每个桶一次递归列举（上限见 {@link #STATS_MAX_OBJECTS}） */
    public List<ObjectBucketStatDTO> bucketStats(String clusterId, String endpoint, List<String> buckets) {
        if (buckets == null || buckets.isEmpty()) {
            return List.of();
        }
        KubernetesClient k8s = clientFactory.getAdminClient(clusterId);
        MinioClient minio = client(k8s, endpoint);
        List<ObjectBucketStatDTO> out = new ArrayList<>();
        for (String bucket : buckets) {
            long bytes = 0;
            long count = 0;
            boolean truncated = false;
            try {
                Iterator<Result<Item>> it = minio.listObjects(ListObjectsArgs.builder()
                        .bucket(bucket).recursive(true).maxKeys(1000).build()).iterator();
                while (it.hasNext()) {
                    Item item = it.next().get();
                    bytes += item.size();
                    count++;
                    if (count >= STATS_MAX_OBJECTS) {
                        truncated = it.hasNext();
                        break;
                    }
                }
            } catch (Exception e) {
                throw s3Error("统计桶用量(" + bucket + ")", e);
            }
            out.add(new ObjectBucketStatDTO(bucket, bytes, count, truncated));
        }
        return out;
    }

    // ==================== 桶的匿名读 ====================

    /**
     * 开/关某个桶的匿名读：改 S3 配置里的 {@code anonymous} 身份，然后滚动网关（配置只在启动时读）。
     *
     * <p>只影响这一个桶：其它桶不在该身份的 actions 里，照旧要签名。
     * 只给 {@code Read}，不给 {@code List} —— 知道完整 key 才能下载，列不出目录。
     */
    public void setAnonymousRead(String clusterId, String bucket, boolean enabled) {
        KubernetesClient k8s = clientFactory.getAdminClient(clusterId);
        assertPlatformConfigMounted(k8s);
        String updated = SeaweedS3ConfigObjects.setAnonymousRead(configJson(k8s), bucket, enabled);
        applySecret(k8s, properties.getConfigSecret(), SeaweedS3ConfigObjects.CONFIG_KEY, updated,
                "platform.coding/component", "seaweedfs");
        rollS3(k8s);
        log.info("对象存储：桶 {} 匿名读 -> {}", bucket, enabled);
    }

    // ==================== 桶配额（SeaweedFS S3 扩展） ====================
    /**
     * 设置/清除桶容量上限：{@code PUT /{bucket}?seaweedfs-quota}（SeaweedFS 扩展，SigV4 签名）。
     *
     * <p>真正的强制执行在网关侧：它每分钟的 bucket-size 循环会把超限桶标成 readOnly
     * （超限写入 403，删除仍允许），回落以后自动恢复 —— 这里只负责把阈值写进去。
     *
     * @param quotaBytes &lt;= 0 表示清除配额
     */
    public void setBucketQuota(String clusterId, String endpoint, String bucket, long quotaBytes) {
        KubernetesClient k8s = clientFactory.getAdminClient(clusterId);
        assertPlatformConfigMounted(k8s);
        if (endpoint == null || endpoint.isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请求没有带对象存储地址");
        }
        String[] admin = SeaweedS3ConfigObjects.findCredentials(configJson(k8s),
                SeaweedS3ConfigObjects.ADMIN_IDENTITY);
        if (admin == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "S3 配置里缺少 " + SeaweedS3ConfigObjects.ADMIN_IDENTITY + " 身份");
        }
        String body = "{\"quota_size\":" + Math.max(quotaBytes, 0)
                + ",\"quota_unit\":\"B\",\"quota_enabled\":" + (quotaBytes > 0) + "}";
        try {
            int status = SeaweedSigV4.putJsonQuery(endpoint.trim(), "/" + bucket, "seaweedfs-quota",
                    body.getBytes(StandardCharsets.UTF_8), admin[0], admin[1]);
            if (status / 100 != 2) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "设置桶配额失败：S3 返回 HTTP " + status);
            }
        } catch (CloudPlatformException e) {
            throw e;
        } catch (Exception e) {
            throw s3Error("设置桶配额", e);
        }
    }

    // ==================== 对象（只读） ====================

    /**
     * 列对象。给了 {@code delimiter}（一般 "/"）= 按「目录」列：
     * 当前层的文件进 items、下一层目录进 folders（S3 CommonPrefixes），控制台据此逐层下钻；
     * 不给 = 递归列出所有 key（扁平）。
     *
     * <p>⚠️ minio SDK 的 {@code recursive(true)} 等价于「不要分隔符」，两者不能同时用：
     * 目录模式只设 {@code delimiter}，扁平模式才设 {@code recursive(true)}。
     */
    public ObjectListResultDTO listObjects(String clusterId, String endpoint, String bucket,
                                           String prefix, String after, Integer maxKeys, String delimiter) {
        KubernetesClient k8s = clientFactory.getAdminClient(clusterId);
        int limit = maxKeys == null ? 200 : Math.max(1, Math.min(1000, maxKeys));
        boolean folderMode = delimiter != null && !delimiter.isBlank();
        ListObjectsArgs.Builder args = ListObjectsArgs.builder()
                .bucket(bucket)
                .maxKeys(limit + 1);
        if (folderMode) {
            args.delimiter(delimiter);
        } else {
            args.recursive(true);
        }
        if (prefix != null && !prefix.isBlank()) {
            args.prefix(prefix);
        }
        if (after != null && !after.isBlank()) {
            args.startAfter(after);
        }
        try {
            Iterator<Result<Item>> it = client(k8s, endpoint).listObjects(args.build()).iterator();
            List<ObjectListItemDTO> items = new ArrayList<>();
            List<String> folders = new ArrayList<>();
            String lastKey = null;
            int taken = 0;
            while (it.hasNext() && taken < limit) {
                Item item = it.next().get();
                taken++;
                lastKey = item.objectName();
                if (item.isDir()) {
                    folders.add(item.objectName());
                } else {
                    items.add(new ObjectListItemDTO(item.objectName(), item.size(),
                            item.lastModified() == null ? null : item.lastModified().toString(), item.etag()));
                }
            }
            // 还能再读一条 → 说明后面还有，下一页从本页最后一个 key 之后开始
            String next = it.hasNext() ? lastKey : null;
            return new ObjectListResultDTO(items, folders, next);
        } catch (Exception e) {
            throw s3Error("列对象", e);
        }
    }

    public ObjectStatDTO statObject(String clusterId, String endpoint, String bucket, String key) {
        KubernetesClient k8s = clientFactory.getAdminClient(clusterId);
        try {
            StatObjectResponse s = client(k8s, endpoint).statObject(
                    StatObjectArgs.builder().bucket(bucket).object(key).build());
            return new ObjectStatDTO(key, s.size(), s.etag(), s.contentType(),
                    s.lastModified() == null ? null : s.lastModified().toString());
        } catch (Exception e) {
            throw s3Error("读取对象信息", e);
        }
    }

    /** 打开对象流；**调用方负责 close**（StreamingResponseBody 里 try-with-resources） */
    public GetObjectResponse openObject(String clusterId, String endpoint, String bucket, String key) {
        KubernetesClient k8s = clientFactory.getAdminClient(clusterId);
        try {
            return client(k8s, endpoint).getObject(
                    GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw s3Error("下载对象", e);
        }
    }

    /**
     * 上传对象：直接把输入流接到 S3（不落临时文件），{@code size} 由调用方给。
     * 同名 key 覆盖（S3 语义）。
     */
    public void putObject(String clusterId, String endpoint, String bucket, String key,
                          String contentType, long size, InputStream in) {
        KubernetesClient k8s = clientFactory.getAdminClient(clusterId);
        try {
            PutObjectArgs.Builder args = PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(in, size, -1);
            if (contentType != null && !contentType.isBlank()) {
                args.contentType(contentType);
            }
            client(k8s, endpoint).putObject(args.build());
        } catch (Exception e) {
            throw s3Error("上传对象", e);
        }
    }

    /** 删除单个对象；对象不存在也算成功（S3 语义） */
    public void removeObject(String clusterId, String endpoint, String bucket, String key) {
        KubernetesClient k8s = clientFactory.getAdminClient(clusterId);
        try {
            client(k8s, endpoint).removeObject(
                    RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw s3Error("删除对象", e);
        }
    }

    // ==================== 凭据 ====================

    /**
     * 发放凭据：写配置里的 bucket-scoped identity + 凭据 Secret，然后滚动 S3 网关。
     *
     * @return accessKey（secretKey 只在 Secret 里）
     */
    public String addCredential(String clusterId, String endpoint, String identityName,
                                String secretName, List<String> actions) {
        KubernetesClient k8s = clientFactory.getAdminClient(clusterId);
        assertPlatformConfigMounted(k8s);
        String accessKey = SeaweedS3ConfigObjects.randomAccessKey();
        String secretKey = SeaweedS3ConfigObjects.randomSecretKey();
        String updated = SeaweedS3ConfigObjects.upsertIdentity(configJson(k8s), identityName,
                accessKey, secretKey, actions);
        applySecret(k8s, properties.getConfigSecret(), SeaweedS3ConfigObjects.CONFIG_KEY, updated,
                "platform.coding/component", "seaweedfs");

        Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName(secretName)
                .withNamespace(properties.getNamespace())
                .addToLabels("platform.coding/managed", "true")
                .addToLabels("platform.coding/component", "seaweedfs")
                .endMetadata()
                .withType("Opaque")
                .addToStringData(ACCESS_KEY, accessKey)
                .addToStringData(SECRET_KEY, secretKey)
                .build();
        k8s.secrets().inNamespace(properties.getNamespace()).resource(secret)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();

        rollS3(k8s);
        return accessKey;
    }

    /** 读取凭据（AK/SK）；Secret 不存在 → 报错（说明已被清理） */
    public ObjectCredentialDTO readCredential(String clusterId, String secretName) {
        KubernetesClient k8s = clientFactory.getAdminClient(clusterId);
        Secret secret = k8s.secrets().inNamespace(properties.getNamespace()).withName(secretName).get();
        if (secret == null || secret.getData() == null
                || !secret.getData().containsKey(ACCESS_KEY) || !secret.getData().containsKey(SECRET_KEY)) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "凭据 Secret 不存在或已被清理：" + properties.getNamespace() + "/" + secretName);
        }
        return new ObjectCredentialDTO(decode(secret, ACCESS_KEY), decode(secret, SECRET_KEY));
    }

    /** 吊销：删 identity + 删 Secret + 滚动 */
    public void revokeCredential(String clusterId, String endpoint, String identityName, String secretName) {
        KubernetesClient k8s = clientFactory.getAdminClient(clusterId);
        // 配置可能本来就缺失（组件被重装过）：不因此阻止吊销，Secret 仍要清掉
        try {
            String updated = SeaweedS3ConfigObjects.removeIdentity(configJson(k8s), identityName);
            applySecret(k8s, properties.getConfigSecret(), SeaweedS3ConfigObjects.CONFIG_KEY, updated,
                    "platform.coding/component", "seaweedfs");
        } catch (CloudPlatformException e) {
            log.warn("吊销凭据时读取 S3 配置失败，仅清理 Secret: {}", e.getMessage());
        }
        k8s.secrets().inNamespace(properties.getNamespace()).withName(secretName).delete();
        rollS3(k8s);
    }

    // ==================== 内部 ====================

    private void applySecret(KubernetesClient client, String name, String key, String value,
                             String labelKey, String labelValue) {
        Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(properties.getNamespace())
                .addToLabels("platform.coding/managed", "true")
                .addToLabels(labelKey, labelValue)
                .endMetadata()
                .withType("Opaque")
                .addToStringData(key, value)
                .build();
        client.secrets().inNamespace(properties.getNamespace()).resource(secret)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
    }

    /** 滚动 S3 网关：容器只在启动时读配置，所以改完必须重启（无状态，秒级） */
    private void rollS3(KubernetesClient client) {
        Deployment dep = client.apps().deployments().inNamespace(properties.getNamespace()).withName(properties.getS3Deployment()).get();
        if (dep == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "对象存储未安装（" + properties.getNamespace() + "/" + properties.getS3Deployment() + " 不存在）");
        }
        String stamp = String.valueOf(System.currentTimeMillis());
        client.apps().deployments().inNamespace(properties.getNamespace()).withName(properties.getS3Deployment())
                .edit(d -> new DeploymentBuilder(d)
                        .editSpec().editTemplate().editMetadata()
                        .addToAnnotations("platform.coding/restartedAt", stamp)
                        .endMetadata().endTemplate().endSpec().build());
    }

    private static String decode(Secret secret, String key) {
        return new String(Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8);
    }

    private static CloudPlatformException s3Error(String action, Exception e) {
        String message = e.getMessage();
        if (e instanceof ErrorResponseException ere && ere.errorResponse() != null) {
            String code = ere.errorResponse().code();
            String detail = ere.errorResponse().message();
            // 常见可执行提示：非空桶、无此桶、无权限
            if ("BucketNotEmpty".equals(code)) {
                message = "桶里还有对象，请先清空（S3 不允许删除非空桶）";
            } else if ("NoSuchBucket".equals(code)) {
                message = "桶不存在（可能已被外部删除）";
            } else if ("NoSuchKey".equals(code)) {
                message = "对象不存在";
            } else if ("AccessDenied".equals(code)) {
                message = "S3 拒绝访问：" + detail;
            } else {
                message = code + (detail == null || detail.isBlank() ? "" : "：" + detail);
            }
        }
        return new CloudPlatformException(EnumResponseType.ERROR, action + "失败：" + message);
    }
}
