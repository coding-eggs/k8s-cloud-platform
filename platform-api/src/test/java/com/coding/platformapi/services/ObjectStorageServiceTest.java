package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.data.mapper.system.PlatformObjectBucketMapper;
import com.coding.data.mapper.system.PlatformObjectCredentialMapper;
import com.coding.data.mapper.system.PlatformObjectQuotaMapper;
import com.coding.data.models.system.PlatformObjectBucket;
import com.coding.data.models.system.PlatformObjectQuota;
import com.coding.platformapi.configs.ObjectStorageProperties;
import com.coding.platformapi.k8s.K8sObjectStorageClient;
import com.coding.platformapi.models.ObjectStorageCreateBucket;
import com.coding.platformapi.models.ObjectStorageQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObjectStorageServiceTest {

    private PlatformObjectBucketMapper buckets;
    private PlatformObjectCredentialMapper credentials;
    private PlatformObjectQuotaMapper quotas;
    private K8sObjectStorageClient s3;
    private ClusterComponentService components;
    private ClusterService clusters;
    private ObjectStorageProperties properties;
    private ObjectStorageService service;

    @BeforeEach
    void setUp() {
        buckets = mock(PlatformObjectBucketMapper.class);
        credentials = mock(PlatformObjectCredentialMapper.class);
        quotas = mock(PlatformObjectQuotaMapper.class);
        s3 = mock(K8sObjectStorageClient.class);
        components = mock(ClusterComponentService.class);
        when(components.seaweedInstalled("c1")).thenReturn(true);
        clusters = mock(ClusterService.class);
        // 默认：集群没单独配地址 → 按组件安装位置推导（集群内 DNS）
        when(clusters.objectStorageUrl("c1")).thenReturn(null);
        properties = new ObjectStorageProperties();
        service = new ObjectStorageService(buckets, credentials, quotas, s3, components, clusters, properties);
    }

    @Test
    void bucketNameCarriesTheTenantPrefixSoTenantsCannotCollide() {
        String a = ObjectStorageService.bucketName("t_acme", "logs");
        String b = ObjectStorageService.bucketName("t_beta", "logs");
        assertThat(a).startsWith("t-").endsWith("-logs");
        assertThat(a).isNotEqualTo(b);
        assertThat(a.length()).isLessThanOrEqualTo(63);
    }

    @Test
    void createBucketRejectsWhenTheTenantHitsItsQuota() {
        when(buckets.countActiveByTenant("c1", "t_acme")).thenReturn(2);
        when(quotas.selectByClusterTenant("c1", "t_acme")).thenReturn(quota("t_acme", 2));

        assertThatThrownBy(() -> service.createBucket(
                new ObjectStorageCreateBucket("c1", "t_acme", "logs", null, null), "admin"))
                .hasMessageContaining("上限");
        verify(s3, never()).createBucket(anyString(), anyString(), anyString());
        verify(buckets, never()).insert(any());
    }

    @Test
    void createBucketUsesTheDefaultQuotaWhenNoRowExists() {
        // 没有配额行 = 默认 10；用量 10 时拒绝
        when(buckets.countActiveByTenant("c1", "t_acme"))
                .thenReturn(properties.getDefaultMaxBuckets());

        assertThatThrownBy(() -> service.createBucket(
                new ObjectStorageCreateBucket("c1", "t_acme", "logs", null, null), "admin"))
                .hasMessageContaining("上限");
    }

    @Test
    void createBucketCreatesRemotelyThenWritesTheLedger() {
        when(buckets.countActiveByTenant("c1", "t_acme")).thenReturn(0);
        service.createBucket(new ObjectStorageCreateBucket("c1", "t_acme", "logs", "日志", null), "admin");

        verify(s3).createBucket("c1", "http://seaweedfs-s3.platform-storage:8333",
                ObjectStorageService.bucketName("t_acme", "logs"));
        ArgumentCaptor<PlatformObjectBucket> captor = ArgumentCaptor.forClass(PlatformObjectBucket.class);
        verify(buckets).insert(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo("t_acme");
        assertThat(captor.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(captor.getValue().getBucket()).endsWith("-logs");
    }

    @Test
    void platformDialsTheClustersOwnObjectStorageAddress() {
        // 集群管理里填了地址 → 用它（平台在集群外、或业务集群时的暴露地址）
        when(clusters.objectStorageUrl("c1")).thenReturn("http://192.168.31.80:38333");
        when(buckets.countActiveByTenant("c1", "t_acme")).thenReturn(0);

        service.createBucket(new ObjectStorageCreateBucket("c1", "t_acme", "logs", null, null), "admin");

        verify(s3).createBucket("c1", "http://192.168.31.80:38333",
                ObjectStorageService.bucketName("t_acme", "logs"));
    }

    @Test
    void platformFallsBackToTheDerivedInClusterAddress() {
        // 没配 → 按组件安装位置推导（release 的 fullnameOverride + 命名空间）
        when(clusters.objectStorageUrl("c1")).thenReturn("  ");
        when(buckets.countActiveByTenant("c1", "t_acme")).thenReturn(0);

        service.createBucket(new ObjectStorageCreateBucket("c1", "t_acme", "logs", null, null), "admin");

        verify(s3).createBucket("c1",
                com.coding.platformapi.components.ComponentRecipeCatalog.seaweedS3InClusterEndpoint(),
                ObjectStorageService.bucketName("t_acme", "logs"));
    }

    @Test
    void createBucketNeedsAClusterNotInstalledErrorIsActionable() {
        when(components.seaweedInstalled("c1")).thenReturn(false);
        when(buckets.countActiveByTenant("c1", "t_acme")).thenReturn(0);

        assertThatThrownBy(() -> service.createBucket(
                new ObjectStorageCreateBucket("c1", "t_acme", "logs", null, null), "admin"))
                .hasMessageContaining("集群组件");
    }

    @Test
    void tenantsOnlySeeTheirOwnBuckets() {
        PlatformObjectBucket mine = row("obk1", "t_acme", "t-aaaa-logs");
        when(buckets.selectByTenant("c1", "t_acme")).thenReturn(List.of(mine));

        var views = service.listBuckets("c1", "t_acme");
        assertThat(views).hasSize(1);
        assertThat(views.get(0).getBucket()).isEqualTo("t-aaaa-logs");
        verify(buckets, never()).selectByCluster(anyString());
    }

    @Test
    void adminWithoutTenantSeesEverything() {
        when(buckets.selectByCluster("c1")).thenReturn(List.of(
                row("obk1", "t_acme", "t-aaaa-logs"), row("obk2", "t_beta", "t-bbbb-logs")));
        assertThat(service.listBuckets("c1", null)).hasSize(2);
        verify(buckets, never()).selectByTenant(anyString(), anyString());
    }

    @Test
    void aTenantCannotReadObjectsOfSomeoneElsesBucket() {
        PlatformObjectBucket other = row("obk1", "t_beta", "t-bbbb-logs");
        when(buckets.selectById("obk1")).thenReturn(other);

        assertThatThrownBy(() -> service.listObjects(
                new com.coding.platformapi.models.ObjectStorageObjectQuery(
                        "c1", "t_acme", "obk1", null, null, 50, null),
                "t_acme"))
                .hasMessageContaining("无权");
        verify(s3, never()).listObjects(anyString(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void adminCanPublishABucketForAnonymousRead() {
        when(buckets.selectById("obk1")).thenReturn(row("obk1", "t_acme", "t-aaaa-logs"));

        service.setAnonymousRead(new com.coding.platformapi.models.ObjectStorageBucketAnonymous(
                "obk1", null, true), null, "admin");

        verify(s3).setAnonymousRead("c1", "t-aaaa-logs", true);
        verify(buckets).updateAnonymousRead(eq("obk1"), eq(true), any());
    }

    @Test
    void deletingABucketAlsoDropsItsAnonymousRead() {
        PlatformObjectBucket row = row("obk1", "t_acme", "t-aaaa-logs");
        row.setAnonymousRead(true);
        when(buckets.selectById("obk1")).thenReturn(row);
        when(credentials.selectActiveByBucket("obk1")).thenReturn(List.of());

        service.deleteBucket(new com.coding.platformapi.models.ObjectStorageBucketRef(
                null, null, "obk1"), "admin");

        // 桶删了还留着匿名读的话，同名桶重建后会莫名其妙是公开的
        verify(s3).setAnonymousRead("c1", "t-aaaa-logs", false);
    }

    @Test
    void adminCanChangeAnExistingBucketsQuota() {
        when(buckets.selectById("obk1")).thenReturn(row("obk1", "t_acme", "t-aaaa-logs"));

        service.setBucketQuota(new com.coding.platformapi.models.ObjectStorageBucketQuota(
                "obk1", null, 2147483648L), null, "admin");

        verify(s3).setBucketQuota("c1",
                com.coding.platformapi.components.ComponentRecipeCatalog.seaweedS3InClusterEndpoint(),
                "t-aaaa-logs", 2147483648L);
        verify(buckets).updateQuota(eq("obk1"), eq(2147483648L), any());
    }

    @Test
    void clearingTheQuotaSendsZeroToSeaweedfsAndNullToTheLedger() {
        when(buckets.selectById("obk1")).thenReturn(row("obk1", "t_acme", "t-aaaa-logs"));

        service.setBucketQuota(new com.coding.platformapi.models.ObjectStorageBucketQuota(
                "obk1", null, null), null, "admin");

        verify(s3).setBucketQuota("c1",
                com.coding.platformapi.components.ComponentRecipeCatalog.seaweedS3InClusterEndpoint(),
                "t-aaaa-logs", 0L);
        verify(buckets).updateQuota(eq("obk1"), isNull(), any());
    }

    @Test
    void createBucketPushesTheQuotaToSeaweedfsAndStoresItInTheLedger() {
        when(buckets.countActiveByTenant("c1", "t_acme")).thenReturn(0);

        service.createBucket(new ObjectStorageCreateBucket(
                "c1", "t_acme", "logs", null, 1073741824L), "admin");

        String remote = ObjectStorageService.bucketName("t_acme", "logs");
        String ep = com.coding.platformapi.components.ComponentRecipeCatalog.seaweedS3InClusterEndpoint();
        verify(s3).setBucketQuota("c1", ep, remote, 1073741824L);
        ArgumentCaptor<PlatformObjectBucket> captor = ArgumentCaptor.forClass(PlatformObjectBucket.class);
        verify(buckets).insert(captor.capture());
        assertThat(captor.getValue().getQuotaBytes()).isEqualTo(1073741824L);
    }

    @Test
    void createBucketRollsBackWhenTheQuotaCannotBeApplied() {
        when(buckets.countActiveByTenant("c1", "t_acme")).thenReturn(0);
        doThrow(new RuntimeException("boom")).when(s3)
                .setBucketQuota(anyString(), anyString(), anyString(), anyLong());

        assertThatThrownBy(() -> service.createBucket(
                new ObjectStorageCreateBucket("c1", "t_acme", "logs", null, 1024L), "admin"))
                .hasMessageContaining("已回滚");
        verify(s3).deleteBucket("c1",
                com.coding.platformapi.components.ComponentRecipeCatalog.seaweedS3InClusterEndpoint(),
                ObjectStorageService.bucketName("t_acme", "logs"));
        verify(buckets, never()).insert(any());
    }

    @Test
    void uploadRejectsOversizeBeforeReadingTheStream() {
        when(buckets.selectById("obk1")).thenReturn(row("obk1", "t_acme", "t-aaaa-logs"));

        assertThatThrownBy(() -> service.uploadObject(
                new com.coding.platformapi.models.ObjectStorageObjectUpload(
                        "obk1", "big.bin", 200L * 1024 * 1024, null),
                "t_acme", 0L, java.io.InputStream.nullInputStream()))
                .hasMessageContaining("100MB");
        verify(s3, never()).uploadObject(anyString(), anyString(), anyString(), anyString(),
                any(), anyLong(), any());
    }

    @Test
    void uploadRejectsWhenTheHttpBodyItselfExceedsTheLimit() {
        // 声明 10 字节、实际 Content-Length 很大：两者任一超限都拒
        when(buckets.selectById("obk1")).thenReturn(row("obk1", "t_acme", "t-aaaa-logs"));

        assertThatThrownBy(() -> service.uploadObject(
                new com.coding.platformapi.models.ObjectStorageObjectUpload("obk1", "x.bin", 10L, null),
                "t_acme", 200L * 1024 * 1024, java.io.InputStream.nullInputStream()))
                .hasMessageContaining("100MB");
    }

    @Test
    void uploadNormalisesTheKeyAndPassesTheDeclaredSize() {
        when(buckets.selectById("obk1")).thenReturn(row("obk1", "t_acme", "t-aaaa-logs"));
        java.io.InputStream in = java.io.InputStream.nullInputStream();

        service.uploadObject(new com.coding.platformapi.models.ObjectStorageObjectUpload(
                "obk1", "/logs/a.txt", 10L, "text/plain"), "t_acme", 10L, in);

        verify(s3).uploadObject("c1",
                com.coding.platformapi.components.ComponentRecipeCatalog.seaweedS3InClusterEndpoint(),
                "t-aaaa-logs", "logs/a.txt", "text/plain", 10L, in);
    }

    @Test
    void tenantsCanDeleteObjectsInTheirOwnBucket() {
        when(buckets.selectById("obk1")).thenReturn(row("obk1", "t_acme", "t-aaaa-logs"));

        service.deleteObject(new com.coding.platformapi.models.ObjectStorageObjectRef(
                null, "t_acme", "obk1", "logs/a.txt"), "t_acme");

        verify(s3).deleteObject("c1",
                com.coding.platformapi.components.ComponentRecipeCatalog.seaweedS3InClusterEndpoint(),
                "t-aaaa-logs", "logs/a.txt");
    }

    @Test
    void tenantsCannotDeleteObjectsInSomeoneElsesBucket() {
        when(buckets.selectById("obk1")).thenReturn(row("obk1", "t_beta", "t-bbbb-logs"));

        assertThatThrownBy(() -> service.deleteObject(
                new com.coding.platformapi.models.ObjectStorageObjectRef(null, "t_acme", "obk1", "a.txt"),
                "t_acme"))
                .hasMessageContaining("无权");
        verify(s3, never()).deleteObject(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void bucketStatsOnlyCountsBucketsTheTenantOwns() {
        // 桶名可猜（t-<租户短码>-<名字>）：前端传了别人的桶名也不查
        when(buckets.selectByTenant("c1", "t_acme"))
                .thenReturn(List.of(row("obk1", "t_acme", "t-aaaa-logs")));
        when(s3.bucketStats(anyString(), anyString(), any())).thenReturn(List.of());

        service.bucketStats("c1", "t_acme", List.of("t-aaaa-logs", "t-bbbb-evil"));

        verify(s3).bucketStats("c1",
                com.coding.platformapi.components.ComponentRecipeCatalog.seaweedS3InClusterEndpoint(),
                List.of("t-aaaa-logs"));
    }

    @Test
    void objectListingPassesTheDelimiterThroughSoTheConsoleCanDrillIntoFolders() {
        PlatformObjectBucket mine = row("obk1", "t_acme", "t-aaaa-logs");
        when(buckets.selectById("obk1")).thenReturn(mine);
        when(s3.listObjects(anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(new com.coding.common.models.objectstorage.ObjectListResultDTO(
                        java.util.Collections.emptyList(), List.of("logs/"), null));

        service.listObjects(new com.coding.platformapi.models.ObjectStorageObjectQuery(
                "c1", "t_acme", "obk1", "", null, 200, "/"), "t_acme");

        verify(s3).listObjects("c1", com.coding.platformapi.components.ComponentRecipeCatalog
                        .seaweedS3InClusterEndpoint(),
                "t-aaaa-logs", "", null, 200, "/");
    }

    @Test
    void quotaViewFallsBackToTheDefaultAndCountsUsage() {
        when(buckets.countActiveByTenant("c1", "t_acme")).thenReturn(3);
        var view = service.quota("c1", "t_acme");
        assertThat(view.getUsed()).isEqualTo(3);
        assertThat(view.getMaxBuckets()).isEqualTo(properties.getDefaultMaxBuckets());
    }

    @Test
    void invalidBucketNamesAreRejectedBeforeTouchingS3() {
        assertThatThrownBy(() -> service.createBucket(
                new ObjectStorageCreateBucket("c1", "t_acme", "Bad_Name", null, null), "admin"))
                .hasMessageContaining("小写字母");
        verify(s3, never()).createBucket(anyString(), anyString(), anyString());
    }

    @Test
    void quotaMustBeProvidedWithATenant() {
        assertThatThrownBy(() -> service.quota("c1", null)).hasMessageContaining("指定租户");
        assertThatThrownBy(() -> service.listBuckets(" ", "t_acme")).hasMessageContaining("选择集群");
    }

    private static PlatformObjectBucket row(String id, String tenantId, String bucket) {
        PlatformObjectBucket b = new PlatformObjectBucket();
        b.setId(id);
        b.setClusterId("c1");
        b.setTenantId(tenantId);
        b.setBucket(bucket);
        b.setStatus("ACTIVE");
        return b;
    }

    private static PlatformObjectQuota quota(String tenantId, int max) {
        PlatformObjectQuota q = new PlatformObjectQuota();
        q.setClusterId("c1");
        q.setTenantId(tenantId);
        q.setMaxBuckets(max);
        return q;
    }
}
