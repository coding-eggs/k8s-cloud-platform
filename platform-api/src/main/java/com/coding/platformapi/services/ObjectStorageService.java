package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.objectstorage.ObjectBucketStatDTO;
import com.coding.common.models.objectstorage.ObjectCredentialDTO;
import com.coding.common.models.objectstorage.ObjectListItemDTO;
import com.coding.common.models.objectstorage.ObjectListResultDTO;
import com.coding.common.models.objectstorage.ObjectStatDTO;
import com.coding.data.mapper.system.PlatformObjectBucketMapper;
import com.coding.data.mapper.system.PlatformObjectCredentialMapper;
import com.coding.data.mapper.system.PlatformObjectQuotaMapper;
import com.coding.data.models.system.PlatformObjectBucket;
import com.coding.data.models.system.PlatformObjectCredential;
import com.coding.data.models.system.PlatformObjectQuota;
import com.coding.platformapi.components.ComponentRecipeCatalog;
import com.coding.platformapi.configs.ObjectStorageProperties;
import com.coding.platformapi.k8s.K8sObjectStorageClient;
import com.coding.platformapi.models.ObjectStorageBucketAnonymous;
import com.coding.platformapi.models.ObjectStorageBucketRef;
import com.coding.platformapi.models.ObjectStorageBucketQuota;
import com.coding.platformapi.models.ObjectStorageBucketView;
import com.coding.platformapi.models.ObjectStorageCreateBucket;
import com.coding.platformapi.models.ObjectStorageCredentialRef;
import com.coding.platformapi.models.ObjectStorageCredentialView;
import com.coding.platformapi.models.ObjectStorageObjectQuery;
import com.coding.platformapi.models.ObjectStorageObjectRef;
import com.coding.platformapi.models.ObjectStorageObjectUpload;
import com.coding.platformapi.models.ObjectStorageQuotaSet;
import com.coding.platformapi.models.ObjectStorageQuotaView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 对象存储（SeaweedFS）的业务编排。
 *
 * <h2>职责边界</h2>
 * <ul>
 *   <li>台账（谁拥有哪个桶、发过哪些凭据、配额多少）——本层 + platform-data；</li>
 *   <li>S3 侧 I/O（建删桶、列对象、写 identities 配置）——k8s-server（{@link K8sObjectStorageClient}）；</li>
 *   <li>鉴权：桶的归属看台账，**不信任前端传的桶名**；凭据吊销/发放只有管理员能调。</li>
 * </ul>
 *
 * <h2>为什么列表不查 S3</h2>
 * 桶级身份连 {@code ListBuckets} 都没有权限（这正是隔离），所以「某个租户有哪些桶」只能由台账回答。
 * 台账与 S3 的漂移用 {@link #reconcile}（管理员手动对账）处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ObjectStorageService {

    /** 用户可读的名字部分：小写字母/数字/连字符，3~30 位 */
    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{1,28}[a-z0-9]$");

    private final PlatformObjectBucketMapper buckets;
    private final PlatformObjectCredentialMapper credentials;
    private final PlatformObjectQuotaMapper quotas;
    private final K8sObjectStorageClient s3;
    private final ClusterComponentService components;
    /** 每集群的对象存储地址（没配则推导，见 {@link #endpoint(String)}） */
    private final ClusterService clusterService;
    /** 默认上限 / 下载上限（见 application.yaml 的 platform.object-storage） */
    private final ObjectStorageProperties properties;

    // ==================== 桶 ====================

    /** tenantId 为 null = 管理员平台视图（全部租户） */
    public List<ObjectStorageBucketView> listBuckets(String clusterId, String tenantId) {
        requireCluster(clusterId);
        List<PlatformObjectBucket> rows = tenantId == null || tenantId.isBlank()
                ? buckets.selectByCluster(clusterId)
                : buckets.selectByTenant(clusterId, tenantId);
        return rows.stream().map(b -> toView(b, credentials.selectActiveByBucket(b.getId()).size())).toList();
    }

    public ObjectStorageBucketView createBucket(ObjectStorageCreateBucket req, String operator) {
        requireCluster(req.getClusterId());
        if (isBlank(req.getTenantId())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请选择桶归属的租户");
        }
        String name = req.getName() == null ? "" : req.getName().trim().toLowerCase();
        if (!NAME.matcher(name).matches()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "桶名只能用 3~30 位小写字母/数字/连字符，且以字母或数字开头结尾");
        }
        String bucket = bucketName(req.getTenantId(), name);
        if (buckets.selectActiveByBucket(req.getClusterId(), bucket) != null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "桶已存在：" + bucket);
        }
        int used = buckets.countActiveByTenant(req.getClusterId(), req.getTenantId());
        int max = maxBuckets(req.getClusterId(), req.getTenantId());
        if (used >= max) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "该租户的桶数已达上限（" + used + "/" + max + "），请先删除不用的桶或调整上限");
        }

        if (req.getQuotaBytes() != null && req.getQuotaBytes() <= 0) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "容量上限必须大于 0（留空 = 不限）");
        }

        s3.createBucket(req.getClusterId(), endpoint(req.getClusterId()), bucket);

        // 容量上限由 SeaweedFS 自己执行（每桶 quota，超限写入 403）：建桶后立刻写进去。
        // 写失败就回滚刚建的空桶 —— 不要留一个"说了有限额、实际没限"的桶。
        if (req.getQuotaBytes() != null) {
            try {
                s3.setBucketQuota(req.getClusterId(), endpoint(req.getClusterId()), bucket, req.getQuotaBytes());
            } catch (RuntimeException e) {
                try {
                    s3.deleteBucket(req.getClusterId(), endpoint(req.getClusterId()), bucket);
                } catch (RuntimeException cleanup) {
                    log.warn("回滚刚建的桶 {} 失败（需人工清理）：{}", bucket, cleanup.getMessage());
                }
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "容量上限设置失败，已回滚本次建桶：" + e.getMessage());
            }
        }

        PlatformObjectBucket row = new PlatformObjectBucket();
        row.setId("obk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        row.setClusterId(req.getClusterId());
        row.setBucket(bucket);
        row.setTenantId(req.getTenantId());
        row.setDisplayName(isBlank(req.getDisplayName()) ? name : req.getDisplayName().trim());
        row.setStatus("ACTIVE");
        row.setCreatedBy(operator);
        row.setCreatedAt(new Date());
        row.setUpdatedAt(new Date());
        row.setQuotaBytes(req.getQuotaBytes());
        row.setAnonymousRead(false);
        buckets.insert(row);
        log.info("对象存储：租户 {} 在集群 {} 建桶 {}（操作者 {}）",
                req.getTenantId(), req.getClusterId(), bucket, operator);
        return toView(row, 0);
    }

    /**
     * 桶的当前用量。只统计**调用方有权看到**的桶 —— 桶名是可猜的（`t-<租户短码>-<名字>`），
     * 不能前端传什么就查什么。
     *
     * <p>S3 没有"桶大小"API，k8s-server 会递归列举求和，并有对象数上限（到顶 truncated → 页面显示「≥」）。
     */
    public List<ObjectBucketStatDTO> bucketStats(String clusterId, String tenantId, List<String> bucketNames) {
        requireCluster(clusterId);
        if (bucketNames == null || bucketNames.isEmpty()) {
            return List.of();
        }
        List<PlatformObjectBucket> rows = tenantId == null || tenantId.isBlank()
                ? buckets.selectByCluster(clusterId)
                : buckets.selectByTenant(clusterId, tenantId);
        Set<String> allowed = rows.stream()
                .filter(b -> b.getDeletedAt() == null && "ACTIVE".equals(b.getStatus()))
                .map(PlatformObjectBucket::getBucket)
                .collect(Collectors.toSet());
        // 单次最多统计 50 个桶：每桶是一次递归列举，别让一个请求把网关拖死
        List<String> target = bucketNames.stream().distinct().filter(allowed::contains).limit(50).toList();
        return target.isEmpty() ? List.of() : s3.bucketStats(clusterId, endpoint(clusterId), target);
    }

    public void deleteBucket(ObjectStorageBucketRef req, String operator) {
        PlatformObjectBucket bucket = requireBucket(req.getBucketId(), null);
        String endpoint = endpoint(bucket.getClusterId());
        // 先删 S3 桶：非空会被拒，错误信息由 k8s-server 归一（"桶里还有对象"）
        s3.deleteBucket(bucket.getClusterId(), endpoint, bucket.getBucket());
        // 桶没了，桶上的凭据也一并吊销（配置里删 identity + 删 Secret）
        for (PlatformObjectCredential credential : credentials.selectActiveByBucket(bucket.getId())) {
            try {
                s3.revokeCredential(bucket.getClusterId(), endpoint,
                        credential.getIdentityName(), credential.getSecretName());
            } catch (Exception e) {
                // 凭据清理失败不阻断删桶（桶已经删了），但要留痕
                log.warn("删桶 {} 时吊销凭据 {} 失败: {}", bucket.getBucket(), credential.getId(), e.getMessage());
            }
            credentials.revoke(credential.getId(), new Date());
        }
        // 桶删了就把匿名读也摘掉：否则同名桶重建后会莫名其妙地是公开的
        if (Boolean.TRUE.equals(bucket.getAnonymousRead())) {
            try {
                s3.setAnonymousRead(bucket.getClusterId(), bucket.getBucket(), false);
            } catch (Exception e) {
                log.warn("删桶 {} 时关闭匿名读失败: {}", bucket.getBucket(), e.getMessage());
            }
        }
        buckets.softDelete(bucket.getId(), new Date());
        log.info("对象存储：删除桶 {}（操作者 {}）", bucket.getBucket(), operator);
    }

    // ==================== 配额 ====================

    public ObjectStorageQuotaView quota(String clusterId, String tenantId) {
        requireCluster(clusterId);
        if (isBlank(tenantId)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请指定租户");
        }
        PlatformObjectQuota row = quotas.selectByClusterTenant(clusterId, tenantId);
        int used = buckets.countActiveByTenant(clusterId, tenantId);
        return new ObjectStorageQuotaView(tenantId, used,
                row == null ? properties.getDefaultMaxBuckets() : row.getMaxBuckets(),
                row == null ? null : row.getUpdatedBy(),
                row == null ? null : row.getUpdatedAt());
    }

    public void setQuota(ObjectStorageQuotaSet req, String operator) {
        requireCluster(req.getClusterId());
        if (isBlank(req.getTenantId())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请指定租户");
        }
        Integer max = req.getMaxBuckets();
        if (max == null || max < 0 || max > 100) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "桶数上限必须在 0~100 之间");
        }
        PlatformObjectQuota row = new PlatformObjectQuota();
        row.setClusterId(req.getClusterId());
        row.setTenantId(req.getTenantId());
        row.setMaxBuckets(max);
        row.setUpdatedBy(operator);
        row.setUpdatedAt(new Date());
        quotas.upsert(row);
    }

    // ==================== 凭据 ====================

    public List<ObjectStorageCredentialView> listCredentials(String bucketId, String tenantId) {
        PlatformObjectBucket bucket = requireBucket(bucketId, tenantId);
        return credentials.selectActiveByBucket(bucket.getId()).stream()
                .map(c -> new ObjectStorageCredentialView(c.getId(), bucket.getId(), c.getIdentityName(),
                        c.getAccessKey(), c.getStatus(), c.getCreatedBy(), c.getCreatedAt()))
                .toList();
    }

    /**
     * 发放凭据：每个桶一组，权限是 bucket-scoped（Read/Write/List/Tagging）。
     *
     * <p>返回里带 secretKey —— **只在发放这一刻返回一次给管理员**；台账只存 accessKey。
     */
    public ObjectCredentialDTO issueCredential(ObjectStorageBucketRef req, String operator) {
        PlatformObjectBucket bucket = requireBucket(req.getBucketId(), null);
        String clusterId = bucket.getClusterId();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String identity = "bucket-" + bucket.getBucket() + "-" + suffix;
        if (identity.length() > 120) {
            identity = identity.substring(0, 120);
        }
        String secretName = "platform-seaweedfs-cred-" + suffix;
        List<String> actions = List.of(
                "Read:" + bucket.getBucket(),
                "Write:" + bucket.getBucket(),
                "List:" + bucket.getBucket(),
                "Tagging:" + bucket.getBucket());

        String accessKey = s3.createCredential(clusterId, endpoint(clusterId), identity, secretName, actions);

        PlatformObjectCredential row = new PlatformObjectCredential();
        row.setId("ocr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        row.setClusterId(clusterId);
        row.setBucketId(bucket.getId());
        row.setTenantId(bucket.getTenantId());
        row.setIdentityName(identity);
        row.setAccessKey(accessKey);
        row.setSecretName(secretName);
        row.setStatus("ACTIVE");
        row.setCreatedBy(operator);
        row.setCreatedAt(new Date());
        credentials.insert(row);
        log.info("对象存储：为桶 {} 发放凭据 {}（操作者 {}）", bucket.getBucket(), identity, operator);

        ObjectCredentialDTO all = s3.getCredential(clusterId, secretName);
        return new ObjectCredentialDTO(accessKey, all == null ? null : all.getSecretKey());
    }

    public ObjectCredentialDTO viewCredential(ObjectStorageCredentialRef req, String tenantId) {
        PlatformObjectCredential row = requireCredential(req.getCredentialId(), tenantId);
        return s3.getCredential(row.getClusterId(), row.getSecretName());
    }

    public void revokeCredential(ObjectStorageCredentialRef req, String operator) {
        PlatformObjectCredential row = requireCredential(req.getCredentialId(), null);
        s3.revokeCredential(row.getClusterId(), endpoint(row.getClusterId()),
                row.getIdentityName(), row.getSecretName());
        credentials.revoke(row.getId(), new Date());
        log.info("对象存储：吊销凭据 {}（操作者 {}）", row.getIdentityName(), operator);
    }

    // ==================== 对象（只读） ====================

    public ObjectListResultDTO listObjects(ObjectStorageObjectQuery req, String tenantId) {
        PlatformObjectBucket bucket = requireBucket(req.getBucketId(), tenantId);
        return s3.listObjects(bucket.getClusterId(), endpoint(bucket.getClusterId()), bucket.getBucket(),
                req.getPrefix(), req.getAfter(), req.getMaxKeys(), req.getDelimiter());
    }

    /**
     * 上传单个对象。**大小与归属校验在读流之前完成**：超限直接拒，不会写进去半个文件。
     *
     * <p>{@code size} 是调用方声明的对象长度（MinIO 需要）；{@code contentLength} 是 HTTP 实际长度
     * —— 两者任一超限都拒，避免"声明 1 字节、实际灌 1GB"这种绕过。
     * 同名 key 直接覆盖（S3 语义），前端负责提示。
     */
    public void uploadObject(ObjectStorageObjectUpload req, String tenantId,
                             long contentLength, InputStream in) {
        PlatformObjectBucket bucket = requireBucket(req.getBucketId(), tenantId);
        long size = req.getSize() == null ? -1 : req.getSize();
        if (size <= 0) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "文件为空或大小未知，无法上传");
        }
        if (size > maxUploadBytes() || contentLength > maxUploadBytes()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "文件超过 " + properties.getMaxUploadMb() + "MB，控制台不支持上传，请用 SDK/客户端直连");
        }
        s3.uploadObject(bucket.getClusterId(), endpoint(bucket.getClusterId()), bucket.getBucket(),
                requireKey(req.getKey()), req.getContentType(), size, in);
    }

    /**
     * 开/关某个桶的匿名读。**先改 S3 成功，再更新台账**（同配额）。
     *
     * <p>只影响该桶：S3 配置里那条 {@code anonymous} 身份只认 actions 里列出的桶；
     * 只给 Read，知道完整 key 才能下载，列不出目录。
     */
    public void setAnonymousRead(ObjectStorageBucketAnonymous req, String tenantId, String operator) {
        PlatformObjectBucket bucket = requireBucket(req.getBucketId(), tenantId);
        boolean enabled = Boolean.TRUE.equals(req.getAnonymousRead());
        s3.setAnonymousRead(bucket.getClusterId(), bucket.getBucket(), enabled);
        buckets.updateAnonymousRead(bucket.getId(), enabled, new Date());
        log.info("对象存储：桶 {} 匿名读 -> {}（操作者 {}）", bucket.getBucket(), enabled, operator);
    }

    /**
     * 改某个已有桶的容量上限（null / &lt;= 0 = 清除）。
     *
     * <p>顺序很重要：**先改 S3**（真正生效的地方，加配额/清除 readOnly 都在它那侧），
     * 成功后再更新台账；S3 失败就不动台账，避免页面显示一个并未生效的上限。
     */
    public void setBucketQuota(ObjectStorageBucketQuota req, String tenantId, String operator) {
        PlatformObjectBucket bucket = requireBucket(req.getBucketId(), tenantId);
        Long quotaBytes = req.getQuotaBytes() != null && req.getQuotaBytes() > 0 ? req.getQuotaBytes() : null;
        s3.setBucketQuota(bucket.getClusterId(), endpoint(bucket.getClusterId()),
                bucket.getBucket(), quotaBytes == null ? 0 : quotaBytes);
        buckets.updateQuota(bucket.getId(), quotaBytes, new Date());
        log.info("对象存储：桶 {} 的容量上限改为 {}（操作者 {}）",
                bucket.getBucket(), quotaBytes == null ? "不限" : quotaBytes + " 字节", operator);
    }

    /** 删除单个对象（v1 不做按前缀批量删：S3 的"目录"只是前缀，误点会删一片） */
    public void deleteObject(ObjectStorageObjectRef req, String tenantId) {
        PlatformObjectBucket bucket = requireBucket(req.getBucketId(), tenantId);
        s3.deleteObject(bucket.getClusterId(), endpoint(bucket.getClusterId()),
                bucket.getBucket(), requireKey(req.getKey()));
    }

    public ObjectStatDTO statObject(ObjectStorageObjectRef req, String tenantId) {
        PlatformObjectBucket bucket = requireBucket(req.getBucketId(), tenantId);
        return s3.statObject(bucket.getClusterId(), endpoint(bucket.getClusterId()),
                bucket.getBucket(), req.getKey());
    }

    /**
     * 下载前的同步校验（返回对象元数据）：**必须在开始流式输出之前调用** ——
     * 一旦响应头发出，再抛错浏览器只会看到被截断的文件。
     */
    public ObjectStatDTO requireDownloadable(ObjectStorageObjectRef req, String tenantId) {
        PlatformObjectBucket bucket = requireBucket(req.getBucketId(), tenantId);
        ObjectStatDTO stat = s3.statObject(bucket.getClusterId(), endpoint(bucket.getClusterId()),
                bucket.getBucket(), req.getKey());
        if (stat != null && stat.getSize() > maxDownloadBytes()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "对象超过 " + properties.getMaxDownloadMb() + "MB，控制台不支持下载，请用 SDK/客户端直连");
        }
        return stat;
    }

    /** 控制台下载：把 k8s-server 的对象流原样透传给浏览器（权限/大小由 {@link #requireDownloadable} 先校验） */
    public void download(ObjectStorageObjectRef req, String tenantId, OutputStream out) {
        PlatformObjectBucket bucket = requireBucket(req.getBucketId(), tenantId);
        s3.download(bucket.getClusterId(), endpoint(bucket.getClusterId()), bucket.getBucket(), req.getKey(), out);
    }

    // ==================== 对账（管理员） ====================

    /**
     * 台账 ↔ S3 对账：返回 S3 里有、台账里没有的桶（外部创建的）。
     *
     * <p>v1 只做「看得见」——不自动接管外部桶（归属不明，接管反而制造错误真相）。
     */
    public List<String> reconcile(String clusterId) {
        requireCluster(clusterId);
        List<String> missing = new java.util.ArrayList<>();
        for (var remote : s3.listBuckets(clusterId, endpoint(clusterId))) {
            if (buckets.selectActiveByBucket(clusterId, remote.getName()) == null) {
                missing.add(remote.getName());
            }
        }
        return missing;
    }

    // ==================== 内部 ====================

    private PlatformObjectBucket requireBucket(String bucketId, String tenantId) {
        if (isBlank(bucketId)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请指定桶");
        }
        PlatformObjectBucket bucket = buckets.selectById(bucketId);
        if (bucket == null || bucket.getDeletedAt() != null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "桶不存在（可能已被删除）");
        }
        if (tenantId != null && !tenantId.isBlank() && !tenantId.equals(bucket.getTenantId())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "无权访问该桶");
        }
        return bucket;
    }

    private PlatformObjectCredential requireCredential(String credentialId, String tenantId) {
        if (isBlank(credentialId)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请指定凭据");
        }
        PlatformObjectCredential row = credentials.selectById(credentialId);
        if (row == null || !"ACTIVE".equals(row.getStatus())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "凭据不存在或已吊销");
        }
        if (tenantId != null && !tenantId.isBlank() && !tenantId.equals(row.getTenantId())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "无权查看该凭据");
        }
        return row;
    }

    private int maxBuckets(String clusterId, String tenantId) {
        PlatformObjectQuota row = quotas.selectByClusterTenant(clusterId, tenantId);
        return row == null || row.getMaxBuckets() == null ? properties.getDefaultMaxBuckets() : row.getMaxBuckets();
    }

    /**
     * 平台后端访问 S3 的地址：**每集群一条连接配置**，没配就按组件安装位置推导。
     *
     * <ol>
     *   <li>{@code k8s_cluster.object_storage_url}（集群管理里填）—— 平台在集群外、业务集群时用；</li>
     *   <li>空 → {@link ComponentRecipeCatalog#seaweedS3InClusterEndpoint()}（集群内地址）。</li>
     * </ol>
     *
     * <p>为什么不在组件安装表单里：那个字段声明的是「这个 S3 对外的入口」，且绑在操作快照上
     * （改地址要重放、老快照缺字段还会把页面卡住）。连接地址是部署事实，按集群配置才说得清。
     */
    private String endpoint(String clusterId) {
        if (!components.seaweedInstalled(clusterId)) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "对象存储组件尚未安装：请先在「集群组件」里安装对象存储");
        }
        String configured = clusterService.objectStorageUrl(clusterId);
        return isBlank(configured)
                ? ComponentRecipeCatalog.seaweedS3InClusterEndpoint()
                : configured.trim();
    }

    private static void requireCluster(String clusterId) {
        if (isBlank(clusterId)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请选择集群");
        }
    }

    /** 桶名 = t-<租户短码>-<名字>：跨租户不会撞名，也不能被别的租户抢先占用 */
    static String bucketName(String tenantId, String name) {
        return "t-" + String.format("%08x", tenantId.hashCode()) + "-" + name;
    }

    private static ObjectStorageBucketView toView(PlatformObjectBucket row, int credentialCount) {
        return new ObjectStorageBucketView(row.getId(), row.getClusterId(), row.getBucket(),
                row.getTenantId(), row.getDisplayName(), row.getStatus(), row.getCreatedBy(),
                row.getCreatedAt(), credentialCount, row.getQuotaBytes(),
                Boolean.TRUE.equals(row.getAnonymousRead()));
    }

    private long maxDownloadBytes() {
        return properties.getMaxDownloadMb() * 1024L * 1024L;
    }

    private long maxUploadBytes() {
        return properties.getMaxUploadMb() * 1024L * 1024L;
    }

    /** 对象 key 合法性：不能空、不能以 / 结尾（那是"目录标记"），去掉开头的 / */
    static String requireKey(String key) {
        String k = key == null ? "" : key.trim();
        while (k.startsWith("/")) {
            k = k.substring(1);
        }
        if (k.isEmpty() || k.endsWith("/")) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "对象名不合法（不能为空或以 / 结尾）");
        }
        return k;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
