package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.data.mapper.system.PlatformObjectBucketMapper;
import com.coding.data.mapper.system.PlatformObjectCredentialMapper;
import com.coding.data.mapper.system.PlatformObjectQuotaMapper;
import com.coding.data.models.system.PlatformObjectBucket;
import com.coding.data.models.system.PlatformObjectQuota;
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
import static org.mockito.ArgumentMatchers.anyString;
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
    private ObjectStorageService service;

    @BeforeEach
    void setUp() {
        buckets = mock(PlatformObjectBucketMapper.class);
        credentials = mock(PlatformObjectCredentialMapper.class);
        quotas = mock(PlatformObjectQuotaMapper.class);
        s3 = mock(K8sObjectStorageClient.class);
        components = mock(ClusterComponentService.class);
        when(components.seaweedEndpoint("c1")).thenReturn("http://seaweedfs-s3.platform-storage:8333");
        service = new ObjectStorageService(buckets, credentials, quotas, s3, components);
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
                new ObjectStorageCreateBucket("c1", "t_acme", "logs", null), "admin"))
                .hasMessageContaining("上限");
        verify(s3, never()).createBucket(anyString(), anyString(), anyString());
        verify(buckets, never()).insert(any());
    }

    @Test
    void createBucketUsesTheDefaultQuotaWhenNoRowExists() {
        // 没有配额行 = 默认 10；用量 10 时拒绝
        when(buckets.countActiveByTenant("c1", "t_acme"))
                .thenReturn(ObjectStorageService.DEFAULT_MAX_BUCKETS);

        assertThatThrownBy(() -> service.createBucket(
                new ObjectStorageCreateBucket("c1", "t_acme", "logs", null), "admin"))
                .hasMessageContaining("上限");
    }

    @Test
    void createBucketCreatesRemotelyThenWritesTheLedger() {
        when(buckets.countActiveByTenant("c1", "t_acme")).thenReturn(0);
        service.createBucket(new ObjectStorageCreateBucket("c1", "t_acme", "logs", "日志"), "admin");

        verify(s3).createBucket("c1", "http://seaweedfs-s3.platform-storage:8333",
                ObjectStorageService.bucketName("t_acme", "logs"));
        ArgumentCaptor<PlatformObjectBucket> captor = ArgumentCaptor.forClass(PlatformObjectBucket.class);
        verify(buckets).insert(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo("t_acme");
        assertThat(captor.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(captor.getValue().getBucket()).endsWith("-logs");
    }

    @Test
    void createBucketNeedsAClusterNotInstalledErrorIsActionable() {
        when(components.seaweedEndpoint("c1")).thenReturn(null);
        when(buckets.countActiveByTenant("c1", "t_acme")).thenReturn(0);

        assertThatThrownBy(() -> service.createBucket(
                new ObjectStorageCreateBucket("c1", "t_acme", "logs", null), "admin"))
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
                new com.coding.platformapi.models.ObjectStorageObjectQuery("c1", "t_acme", "obk1", null, null, 50),
                "t_acme"))
                .hasMessageContaining("无权");
        verify(s3, never()).listObjects(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void quotaViewFallsBackToTheDefaultAndCountsUsage() {
        when(buckets.countActiveByTenant("c1", "t_acme")).thenReturn(3);
        var view = service.quota("c1", "t_acme");
        assertThat(view.getUsed()).isEqualTo(3);
        assertThat(view.getMaxBuckets()).isEqualTo(ObjectStorageService.DEFAULT_MAX_BUCKETS);
    }

    @Test
    void invalidBucketNamesAreRejectedBeforeTouchingS3() {
        assertThatThrownBy(() -> service.createBucket(
                new ObjectStorageCreateBucket("c1", "t_acme", "Bad_Name", null), "admin"))
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
