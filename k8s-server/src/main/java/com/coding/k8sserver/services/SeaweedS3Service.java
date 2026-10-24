package com.coding.k8sserver.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.objectstorage.ObjectBucketDTO;
import com.coding.common.models.objectstorage.ObjectCredentialDTO;
import com.coding.common.models.objectstorage.ObjectListItemDTO;
import com.coding.common.models.objectstorage.ObjectListResultDTO;
import com.coding.common.models.objectstorage.ObjectStatDTO;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8score.operations.ServerSideApply;
import com.coding.k8sserver.components.SeaweedS3ConfigObjects;
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
import io.minio.RemoveBucketArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    /** 组件安装的命名空间（与 ComponentRecipeCatalog.SEAWEEDFS_RELEASES 一致） */
    public static final String NAMESPACE = "platform-storage";
    /** 平台接管的 S3 identities 配置文件（chart 的 s3.existingConfigSecret） */
    public static final String CONFIG_SECRET = "platform-seaweedfs-s3-config";
    /** chart 里 S3 网关的 Deployment 名（fullnameOverride=seaweedfs） */
    static final String S3_DEPLOYMENT = "seaweedfs-s3";
    /** 集群内默认地址 */
    public static final String DEFAULT_ENDPOINT = "http://seaweedfs-s3.platform-storage:8333";

    static final String ACCESS_KEY = "access_key";
    static final String SECRET_KEY = "secret_key";

    private final KubernetesClientFactory clientFactory;

    // ==================== 配置文件 ====================

    /** 组件安装前调用：确保配置 Secret 里有 platform-admin（已存在则原样保留，不轮换） */
    public void ensureConfigSecret(String clusterId) {
        ensureConfig(clientFactory.getAdminClient(clusterId));
    }

    private void ensureConfig(KubernetesClient client) {
        Secret existing = client.secrets().inNamespace(NAMESPACE).withName(CONFIG_SECRET).get();
        if (existing != null && existing.getData() != null
                && existing.getData().containsKey(SeaweedS3ConfigObjects.CONFIG_KEY)) {
            return;
        }
        String json = SeaweedS3ConfigObjects.adminOnly(
                SeaweedS3ConfigObjects.randomAccessKey(), SeaweedS3ConfigObjects.randomSecretKey());
        applySecret(client, CONFIG_SECRET, SeaweedS3ConfigObjects.CONFIG_KEY, json,
                "platform.coding/component", "seaweedfs");
        log.info("对象存储：已初始化平台 S3 配置文件 {}/{}", NAMESPACE, CONFIG_SECRET);
    }

    private String configJson(KubernetesClient client) {
        Secret secret = client.secrets().inNamespace(NAMESPACE).withName(CONFIG_SECRET).get();
        if (secret == null || secret.getData() == null
                || !secret.getData().containsKey(SeaweedS3ConfigObjects.CONFIG_KEY)) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "对象存储的 S3 配置文件不存在（" + NAMESPACE + "/" + CONFIG_SECRET
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
        Deployment dep = client.apps().deployments().inNamespace(NAMESPACE).withName(S3_DEPLOYMENT).get();
        if (dep == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "对象存储未安装（" + NAMESPACE + "/" + S3_DEPLOYMENT + " 不存在）");
        }
        boolean mounted = dep.getSpec() != null && dep.getSpec().getTemplate() != null
                && dep.getSpec().getTemplate().getSpec() != null
                && dep.getSpec().getTemplate().getSpec().getVolumes() != null
                && dep.getSpec().getTemplate().getSpec().getVolumes().stream().anyMatch(v ->
                v.getSecret() != null && CONFIG_SECRET.equals(v.getSecret().getSecretName()));
        if (!mounted) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "对象存储仍在使用 chart 自带的 S3 配置；请先在「集群组件」里对对象存储执行一次"
                            + "「编辑/重放」，切换到平台管理的配置后再操作");
        }
    }

    // ==================== S3 客户端 ====================

    private MinioClient client(KubernetesClient k8s, String endpoint) {
        String[] admin = SeaweedS3ConfigObjects.findCredentials(configJson(k8s),
                SeaweedS3ConfigObjects.ADMIN_IDENTITY);
        if (admin == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "S3 配置里缺少 " + SeaweedS3ConfigObjects.ADMIN_IDENTITY + " 身份");
        }
        String url = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.trim();
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

    // ==================== 对象（只读） ====================

    public ObjectListResultDTO listObjects(String clusterId, String endpoint, String bucket,
                                           String prefix, String after, Integer maxKeys) {
        KubernetesClient k8s = clientFactory.getAdminClient(clusterId);
        int limit = maxKeys == null ? 200 : Math.max(1, Math.min(1000, maxKeys));
        ListObjectsArgs.Builder args = ListObjectsArgs.builder()
                .bucket(bucket)
                .recursive(true)
                .maxKeys(limit + 1);
        if (prefix != null && !prefix.isBlank()) {
            args.prefix(prefix);
        }
        if (after != null && !after.isBlank()) {
            args.startAfter(after);
        }
        try {
            Iterator<Result<Item>> it = client(k8s, endpoint).listObjects(args.build()).iterator();
            List<ObjectListItemDTO> items = new ArrayList<>();
            while (it.hasNext() && items.size() < limit) {
                Item item = it.next().get();
                items.add(new ObjectListItemDTO(item.objectName(), item.size(),
                        item.lastModified() == null ? null : item.lastModified().toString(), item.etag()));
            }
            // 还能再读一条 → 说明后面还有，下一页从本页最后一个 key 之后开始
            String next = it.hasNext() && !items.isEmpty()
                    ? items.get(items.size() - 1).getKey() : null;
            return new ObjectListResultDTO(items, next);
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
        applySecret(k8s, CONFIG_SECRET, SeaweedS3ConfigObjects.CONFIG_KEY, updated,
                "platform.coding/component", "seaweedfs");

        Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName(secretName)
                .withNamespace(NAMESPACE)
                .addToLabels("platform.coding/managed", "true")
                .addToLabels("platform.coding/component", "seaweedfs")
                .endMetadata()
                .withType("Opaque")
                .addToStringData(ACCESS_KEY, accessKey)
                .addToStringData(SECRET_KEY, secretKey)
                .build();
        k8s.secrets().inNamespace(NAMESPACE).resource(secret)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();

        rollS3(k8s);
        return accessKey;
    }

    /** 读取凭据（AK/SK）；Secret 不存在 → 报错（说明已被清理） */
    public ObjectCredentialDTO readCredential(String clusterId, String secretName) {
        KubernetesClient k8s = clientFactory.getAdminClient(clusterId);
        Secret secret = k8s.secrets().inNamespace(NAMESPACE).withName(secretName).get();
        if (secret == null || secret.getData() == null
                || !secret.getData().containsKey(ACCESS_KEY) || !secret.getData().containsKey(SECRET_KEY)) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "凭据 Secret 不存在或已被清理：" + NAMESPACE + "/" + secretName);
        }
        return new ObjectCredentialDTO(decode(secret, ACCESS_KEY), decode(secret, SECRET_KEY));
    }

    /** 吊销：删 identity + 删 Secret + 滚动 */
    public void revokeCredential(String clusterId, String endpoint, String identityName, String secretName) {
        KubernetesClient k8s = clientFactory.getAdminClient(clusterId);
        // 配置可能本来就缺失（组件被重装过）：不因此阻止吊销，Secret 仍要清掉
        try {
            String updated = SeaweedS3ConfigObjects.removeIdentity(configJson(k8s), identityName);
            applySecret(k8s, CONFIG_SECRET, SeaweedS3ConfigObjects.CONFIG_KEY, updated,
                    "platform.coding/component", "seaweedfs");
        } catch (CloudPlatformException e) {
            log.warn("吊销凭据时读取 S3 配置失败，仅清理 Secret: {}", e.getMessage());
        }
        k8s.secrets().inNamespace(NAMESPACE).withName(secretName).delete();
        rollS3(k8s);
    }

    // ==================== 内部 ====================

    private void applySecret(KubernetesClient client, String name, String key, String value,
                             String labelKey, String labelValue) {
        Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(NAMESPACE)
                .addToLabels("platform.coding/managed", "true")
                .addToLabels(labelKey, labelValue)
                .endMetadata()
                .withType("Opaque")
                .addToStringData(key, value)
                .build();
        client.secrets().inNamespace(NAMESPACE).resource(secret)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
    }

    /** 滚动 S3 网关：容器只在启动时读配置，所以改完必须重启（无状态，秒级） */
    private void rollS3(KubernetesClient client) {
        Deployment dep = client.apps().deployments().inNamespace(NAMESPACE).withName(S3_DEPLOYMENT).get();
        if (dep == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "对象存储未安装（" + NAMESPACE + "/" + S3_DEPLOYMENT + " 不存在）");
        }
        String stamp = String.valueOf(System.currentTimeMillis());
        client.apps().deployments().inNamespace(NAMESPACE).withName(S3_DEPLOYMENT)
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
