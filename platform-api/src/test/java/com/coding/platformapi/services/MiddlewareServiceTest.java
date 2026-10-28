package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareOperatorStatusDTO;
import com.coding.common.models.middleware.MiddlewarePasswordRequestDTO;
import com.coding.common.models.k8s.dto.StorageClassDTO;
import com.coding.common.models.middleware.MiddlewareConnectionDTO;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.system.PlatformMiddlewareInstanceMapper;
import com.coding.data.mapper.system.PlatformMiddlewareOperationMapper;
import com.coding.data.mapper.system.PlatformMiddlewareOperatorMapper;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.system.PlatformMiddlewareInstance;
import com.coding.data.models.system.PlatformMiddlewareOperation;
import com.coding.platformapi.cache.RedisJsonCache;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.k8s.K8sMiddlewareClient;
import com.coding.platformapi.middleware.MiddlewareCatalog;
import com.coding.platformapi.models.MiddlewareCreateRequest;
import com.coding.platformapi.models.MiddlewareDeleteRequest;
import com.coding.platformapi.models.MiddlewarePasswordChangeRequest;
import com.coding.platformapi.models.ObjectStorageBucketRef;
import com.coding.platformapi.models.ObjectStorageBucketView;
import com.coding.platformapi.models.ObjectStorageCreateBucket;
import com.coding.common.models.objectstorage.ObjectCredentialDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MiddlewareServiceTest {

    private final PlatformMiddlewareInstanceMapper instances = mock(PlatformMiddlewareInstanceMapper.class);
    private final PlatformMiddlewareOperationMapper operations = mock(PlatformMiddlewareOperationMapper.class);
    private final PlatformMiddlewareOperatorMapper operators = mock(PlatformMiddlewareOperatorMapper.class);
    private final K8sMiddlewareClient client = mock(K8sMiddlewareClient.class);
    private final K8sClient k8s = mock(K8sClient.class);
    private final PlatformTenantNamespaceMapper allocations = mock(PlatformTenantNamespaceMapper.class);
    private final RedisJsonCache cache = mock(RedisJsonCache.class);
    private final com.coding.platformapi.metrics.MetricsService metricsService =
            mock(com.coding.platformapi.metrics.MetricsService.class);
    private final ObjectStorageService objectStorage = mock(ObjectStorageService.class);
    private final ClusterComponentService components = mock(ClusterComponentService.class);

    private final MiddlewareCatalog catalog = new MiddlewareCatalog();

    private final MiddlewareService service = new MiddlewareService(
            instances, operations, operators, client, catalog,
            JsonMapper.builder().build(), k8s, allocations, cache, metricsService,
            objectStorage, components, mock(ApplicationReferenceGuard.class));

    @BeforeEach
    void opsGetIds() {
        // 缓存打桩成直通：测试里不关心 Redis
        org.mockito.Mockito.when(cache.get(anyString(), any(), any(), any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(3)).get());
        doAnswer(inv -> {
            inv.getArgument(0, PlatformMiddlewareOperation.class).setId(1L);
            return 1;
        }).when(operations).insert(any());
    }

    private MiddlewareCreateRequest createRequest() {
        MiddlewareCreateRequest r = new MiddlewareCreateRequest();
        r.setClusterId("c1");
        r.setCode("redis");
        r.setMode("STANDALONE");
        r.setNamespace("demo");
        r.setReplicas(1);
        r.setCpuRequest("100m");
        r.setCpuLimit("500m");
        r.setMemoryRequest("128Mi");
        r.setMemoryLimit("512Mi");
        r.setStorageSize("10Gi");
        return r;
    }

    private MiddlewareOperatorStatusDTO status(String code, boolean installed, boolean ready) {
        MiddlewareOperatorStatusDTO s = new MiddlewareOperatorStatusDTO();
        s.setCode(code);
        s.setInstalled(installed);
        s.setReady(ready);
        s.setChartVersion("0.27.0");
        s.setMessage(ready ? "运行中" : "CRD 未就绪");
        return s;
    }

    @Test
    void createRejectsWhenOperatorIsNotReady() {
        when(client.operatorStatus(any())).thenReturn(List.of(status("redis", false, false)));

        assertThatThrownBy(() -> service.create(createRequest(), "admin", null))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("Operator");
    }

    @Test
    void createGeneratesNameAndPasswordButStoresNoPassword() {
        when(client.operatorStatus(any())).thenReturn(List.of(status("redis", true, true)));
        when(client.create(anyString(), any())).thenReturn(true);

        Long opId = service.create(createRequest(), "admin", "t1");

        assertThat(opId).isEqualTo(1L);

        ArgumentCaptor<MiddlewareCreateRequestDTO> sent = ArgumentCaptor.forClass(MiddlewareCreateRequestDTO.class);
        verify(client).create(anyString(), sent.capture());
        assertThat(sent.getValue().getName()).startsWith("mw-redis-");
        assertThat(sent.getValue().getPassword()).isNotBlank();

        ArgumentCaptor<PlatformMiddlewareInstance> saved = ArgumentCaptor.forClass(PlatformMiddlewareInstance.class);
        verify(instances).insert(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("CREATING");
        assertThat(saved.getValue().getTenantId()).isEqualTo("t1");
        assertThat(saved.getValue().getSpecJson()).doesNotContain(sent.getValue().getPassword());
        assertThat(sent.getValue().getTenantId()).isEqualTo("t1");
    }

    @Test
    void createMarksInstanceFailedWhenK8sRejects() {
        when(client.operatorStatus(any())).thenReturn(List.of(status("redis", true, true)));
        when(client.create(anyString(), any())).thenThrow(new CloudPlatformException(
                com.coding.common.exception.EnumResponseType.ERROR, "命名空间配额不足"));

        assertThatThrownBy(() -> service.create(createRequest(), "admin", null))
                .hasMessageContaining("配额");

        ArgumentCaptor<PlatformMiddlewareInstance> saved = ArgumentCaptor.forClass(PlatformMiddlewareInstance.class);
        verify(instances).updateById(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("FAILED");
        // 失败即软删：否则失败后重试会在列表里多出一条幽灵实例
        assertThat(saved.getValue().getDeletedAt()).isNotNull();
    }

    @Test
    void catalogMergesOperatorStatusIntoItems() {
        when(client.operatorStatus(any())).thenReturn(List.of(status("redis", true, true)));
        when(instances.selectByCluster("c1", null)).thenReturn(List.of());

        var items = service.catalog("c1", null);

        assertThat(items).anyMatch(i -> i.getCode().equals("redis")
                && i.isOperatorReady() && "0.27.0".equals(i.getChartVersion()));
        assertThat(items).anyMatch(i -> i.getCode().equals("postgresql") && i.getModes().size() == 2);
    }

    @Test
    void catalogWithoutClusterIsRejectedWithAClearMessage() {
        // 缺 clusterId 原先会一路打到 k8s 调用，最终只回一个「服务端异常」
        assertThatThrownBy(() -> service.catalog(null, null))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("请选择集群");
    }

    @Test
    void staleOperationSweepIsScheduled() throws Exception {
        // create 请求被中断时 finish 不会执行，running_lock 会让实例永远"已有进行中的操作"；
        // 只有启动时回收不够，运行期必须也周期性扫
        var method = MiddlewareService.class.getMethod("reapStaleOperations");
        assertThat(method.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class))
                .isNotNull();
    }

    @Test
    void catalogExposesSupportedVersions() {
        when(client.operatorStatus(any())).thenReturn(List.of());
        when(instances.selectByCluster("c1", null)).thenReturn(List.of());

        var items = service.catalog("c1", null);

        assertThat(items).anyMatch(i -> i.getCode().equals("elasticsearch")
                && i.getVersions().contains("9.5.0"));
        assertThat(items).anyMatch(i -> i.getCode().equals("kafka")
                && i.getVersions().contains("4.3.1"));
        // 版本由实现固定的条目不出现在表单里
        assertThat(items).anyMatch(i -> i.getCode().equals("redis") && i.getVersions().isEmpty());
    }

    @Test
    void createRejectsAVersionThatIsNotOnTheSupportedList() {
        var r = createRequest();
        r.setCode("elasticsearch");
        r.setMode("CLUSTER");
        r.setVersion("8.0.0");
        when(client.operatorStatus(any())).thenReturn(List.of(status("elasticsearch", true, true)));

        assertThatThrownBy(() -> service.create(r, "coding", null))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("不支持版本");
    }

    @Test
    void createDefaultsToTheFirstSupportedVersion() {
        var r = createRequest();
        r.setCode("elasticsearch");
        r.setMode("CLUSTER");
        when(client.operatorStatus(any())).thenReturn(List.of(status("elasticsearch", true, true)));
        ArgumentCaptor<MiddlewareCreateRequestDTO> sent = ArgumentCaptor.forClass(MiddlewareCreateRequestDTO.class);

        service.create(r, "coding", null);

        verify(client).create(org.mockito.ArgumentMatchers.eq("elasticsearch"), sent.capture());
        assertThat(sent.getValue().getVersion()).isEqualTo("9.5.0");
    }

    @Test
    void createRecordsIkInTheSpecSoTheDetailPageCanEchoItBack() {
        // 创建时勾了 IK 就必须落进 spec_json：详情/编辑弹窗靠它回显「已安装」
        //（漏了这一步，创建时装的 IK 在编辑弹窗里永远显示「未安装」）
        var r = createRequest();
        r.setCode("elasticsearch");
        r.setMode("CLUSTER");
        r.setIk(true);
        when(client.operatorStatus(any())).thenReturn(List.of(status("elasticsearch", true, true)));

        service.create(r, "coding", null);

        ArgumentCaptor<PlatformMiddlewareInstance> saved = ArgumentCaptor.forClass(PlatformMiddlewareInstance.class);
        verify(instances).insert(saved.capture());
        assertThat(saved.getValue().getSpecJson()).contains("\"ik\":true");
    }

    @Test
    void formOptionsFilterNamespacesByTenantAndReadStorageClasses() {
        PlatformTenantNamespace mine = new PlatformTenantNamespace();
        mine.setTenantId("t1");
        mine.setNamespace("demo");
        PlatformTenantNamespace other = new PlatformTenantNamespace();
        other.setTenantId("t2");
        other.setNamespace("other-ns");
        when(allocations.listByCluster("c1")).thenReturn(List.of(mine, other));
        StorageClassDTO sc = new StorageClassDTO();
        sc.setName("nfs-client");
        when(k8s.list(any())).thenReturn(List.of(sc));

        var options = service.formOptions("c1", "t1");

        assertThat(options.getNamespaces()).containsExactly("demo");
        assertThat(options.getStorageClasses()).containsExactly("nfs-client");
    }

    @Test
    void formOptionsDegradesWhenStorageClassReadFails() {
        when(allocations.listByCluster("c1")).thenReturn(List.of());
        when(k8s.list(any())).thenThrow(new RuntimeException("cluster down"));

        assertThat(service.formOptions("c1", null).getStorageClasses()).isEmpty();
    }

    private PlatformMiddlewareInstance instance(String code, String mode) {
        PlatformMiddlewareInstance i = new PlatformMiddlewareInstance();
        i.setId("mwi_1");
        i.setClusterId("c1");
        i.setNamespace("demo");
        i.setCode(code);
        i.setMode(mode);
        i.setName("mw-" + code + "-abcdefgh");
        return i;
    }

    @Test
    void detailMergesLedgerSpecConnectionAndLiveObjects() {
        PlatformMiddlewareInstance i = instance("redis", "SENTINEL");
        i.setSpecJson("{\"replicas\":2,\"cpuRequest\":\"100m\"}");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);
        var live = new com.coding.common.models.middleware.MiddlewareInstanceLiveDTO();
        live.setKind("RedisReplication");
        live.setName(i.getName());
        when(client.detail(org.mockito.ArgumentMatchers.eq("redis"), any())).thenReturn(live);

        var detail = service.detail("mwi_1", null);

        assertThat(detail.getInstance().getName()).isEqualTo(i.getName());
        assertThat(detail.getSpec()).containsEntry("replicas", 2);
        assertThat(detail.getConnection().getEndpoints()).isNotEmpty();
        assertThat(detail.getLive().getKind()).isEqualTo("RedisReplication");
        assertThat(detail.getLiveError()).isNull();
    }

    @Test
    void detailRefreshesStatusFromClusterInsteadOfStaleLedgerValue() {
        // 台账的 status 是创建时写死的 CREATING，之后没人回写；详情页必须现场读一次，
        // 否则实例已经跑起来了还显示「创建中」（2026-10-23 用户实测）。
        PlatformMiddlewareInstance i = instance("redis", "STANDALONE");
        i.setStatus("CREATING");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);
        when(client.detail(org.mockito.ArgumentMatchers.eq("redis"), any()))
                .thenReturn(new com.coding.common.models.middleware.MiddlewareInstanceLiveDTO());
        var live = new com.coding.common.models.middleware.MiddlewareStatusResultDTO();
        live.setName(i.getName());
        live.setStatus("READY");
        live.setMessage("运行中");
        when(client.status(org.mockito.ArgumentMatchers.eq("redis"), any())).thenReturn(List.of(live));

        var detail = service.detail("mwi_1", null);

        assertThat(detail.getInstance().getStatus()).isEqualTo("READY");
        assertThat(detail.getInstance().getMessage()).isEqualTo("运行中");
    }

    @Test
    void detailKeepsLedgeDataWhenClusterReadFails() {        PlatformMiddlewareInstance i = instance("redis", "SENTINEL");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);
        when(client.detail(anyString(), any())).thenThrow(new RuntimeException("cluster down"));

        var detail = service.detail("mwi_1", null);

        assertThat(detail.getLive()).isNull();
        assertThat(detail.getLiveError()).contains("cluster down");
        assertThat(detail.getConnection()).isNotNull();
    }

    @Test
    void metricsAggregateAllInstancePodsIntoOneRegex() {
        PlatformMiddlewareInstance i = instance("elasticsearch", "CLUSTER");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);
        var live = new com.coding.common.models.middleware.MiddlewareInstanceLiveDTO();
        live.setPods(List.of(pod("mw-es-default-0"), pod("mw-es-default-1")));
        when(client.detail(org.mockito.ArgumentMatchers.eq("elasticsearch"), any())).thenReturn(live);
        when(metricsService.middlewareInstance(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(com.coding.platformapi.metrics.dto.MiddlewareMetricsResponse.empty());

        var req = new com.coding.platformapi.models.MiddlewareMetricsRequest();
        req.setInstanceId("mwi_1");
        req.setStart(1000L);
        req.setEnd(2000L);
        service.metrics(req);

        var regex = ArgumentCaptor.forClass(String.class);
        verify(metricsService).middlewareInstance(org.mockito.ArgumentMatchers.eq("c1"),
                org.mockito.ArgumentMatchers.eq("demo"), regex.capture(),
                org.mockito.ArgumentMatchers.eq(1000L), org.mockito.ArgumentMatchers.eq(2000L));
        assertThat(regex.getValue()).isEqualTo("^(?:mw-es-default-0|mw-es-default-1)$");
    }

    @Test
    void metricsIncludeDeclaredBusinessMetrics() {
        // 业务指标逐条按目录声明查：Pod 名单只解析一次，声明有几条就查几条
        PlatformMiddlewareInstance i = instance("rabbitmq", "SINGLE");
        i.setSpecJson("{\"metricsEnabled\":true}");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);
        var live = new com.coding.common.models.middleware.MiddlewareInstanceLiveDTO();
        live.setPods(List.of(pod("mw-rabbitmq-abc12345-server-0")));
        when(client.detail(org.mockito.ArgumentMatchers.eq("rabbitmq"), any())).thenReturn(live);
        when(metricsService.middlewareInstance(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(com.coding.platformapi.metrics.dto.MiddlewareMetricsResponse.empty());
        when(metricsService.businessMetric(anyString(), anyString(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new com.coding.platformapi.metrics.dto.MetricSeriesResponse("条", List.of()));

        var req = new com.coding.platformapi.models.MiddlewareMetricsRequest();
        req.setInstanceId("mwi_1");
        var out = service.metrics(req);

        // 断言与目录声明同源：以后增删指标不用改测试
        assertThat(out.business()).extracting(
                        com.coding.platformapi.metrics.dto.MiddlewareMetricsResponse.BusinessMetric::key)
                .containsExactlyElementsOf(catalog.require("rabbitmq").metrics().stream()
                        .map(MiddlewareCatalog.MetricDef::key).toList());
        assertThat(out.business()).hasSizeGreaterThan(4);
    }

    @Test
    void metricsSkipBusinessQueriesWhenTheSwitchIsOff() {
        // 业务指标默认不采：没开开关时只回资源用量四图，不去查 exporter 指标
        PlatformMiddlewareInstance i = instance("rabbitmq", "SINGLE");
        i.setSpecJson("{\"metricsEnabled\":false}");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);
        var live = new com.coding.common.models.middleware.MiddlewareInstanceLiveDTO();
        live.setPods(List.of(pod("mw-rabbitmq-abc12345-server-0")));
        when(client.detail(anyString(), any())).thenReturn(live);
        when(metricsService.middlewareInstance(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(com.coding.platformapi.metrics.dto.MiddlewareMetricsResponse.empty());

        var req = new com.coding.platformapi.models.MiddlewareMetricsRequest();
        req.setInstanceId("mwi_1");
        var out = service.metrics(req);

        assertThat(out.business()).isEmpty();
        org.mockito.Mockito.verify(metricsService, org.mockito.Mockito.never())
                .businessMetric(anyString(), anyString(), anyString(), anyString(), anyString(),
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void metricsSkipQueryWhenInstanceHasNoPods() {
        PlatformMiddlewareInstance i = instance("elasticsearch", "CLUSTER");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);
        when(client.detail(anyString(), any())).thenReturn(
                new com.coding.common.models.middleware.MiddlewareInstanceLiveDTO());

        var req = new com.coding.platformapi.models.MiddlewareMetricsRequest();
        req.setInstanceId("mwi_1");
        var out = service.metrics(req);

        assertThat(out.cpu().getSeries()).isEmpty();
        org.mockito.Mockito.verify(metricsService, org.mockito.Mockito.never())
                .middlewareInstance(anyString(), anyString(), anyString(),
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }

    private static com.coding.common.models.middleware.MiddlewareInstanceLiveDTO.PodView pod(String name) {
        var p = new com.coding.common.models.middleware.MiddlewareInstanceLiveDTO.PodView();
        p.setName(name);
        return p;
    }

    @Test
    void operationsAreScopedToTheInstanceOwner() {
        PlatformMiddlewareInstance i = instance("redis", "STANDALONE");
        i.setTenantId("t1");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);

        assertThatThrownBy(() -> service.operations("mwi_1", "t2"))
                .hasMessageContaining("无权操作该实例");
    }

    @Test
    void renameOnlyTouchesTheLedger() {
        PlatformMiddlewareInstance i = instance("redis", "STANDALONE");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);

        var updated = service.rename("mwi_1", "  缓存主库  ", null);

        assertThat(updated.getDisplayName()).isEqualTo("缓存主库");
        verify(instances).updateById(i);
        verify(client, org.mockito.Mockito.never()).scale(anyString(), any());
    }

    @Test
    void connectionForRedisSentinelListsMasterReplicaAndSentinel() {
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(instance("redis", "SENTINEL"));

        var c = service.connection("mwi_1", null);

        assertThat(c.getEndpoints()).extracting(MiddlewareConnectionDTO.Line::label)
                .containsExactly("主库（读写）", "从库（只读）", "哨兵");
        assertThat(c.getEndpoints().get(0).value())
                .isEqualTo("mw-redis-abcdefgh-master.demo.svc.cluster.local:6379");
        assertThat(c.getEndpoints().get(2).value())
                .isEqualTo("mw-redis-abcdefgh-sentinel.demo.svc.cluster.local:26379");
        assertThat(c.getCredentials()).extracting(MiddlewareConnectionDTO.Line::value)
                .anyMatch(v -> v.contains("-credentials"));
    }

    @Test
    void connectionForKafkaHasNoCredentials() {
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(instance("kafka", "CLUSTER"));

        var c = service.connection("mwi_1", null);

        assertThat(c.getEndpoints().get(0).value())
                .isEqualTo("mw-kafka-abcdefgh-kafka-bootstrap.demo.svc.cluster.local:9092");
        assertThat(c.getCredentials()).isEmpty();
    }

    @Test
    void connectionForElasticsearchShowsHttpsAndElasticUser() {
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(instance("elasticsearch", "CLUSTER"));

        var c = service.connection("mwi_1", null);

        assertThat(c.getEndpoints().get(0).value())
                .startsWith("https://mw-elasticsearch-abcdefgh-es-http.demo.svc.cluster.local:9200");
        assertThat(c.getCredentials()).extracting(MiddlewareConnectionDTO.Line::value)
                .anyMatch(v -> v.contains("es-elastic-user"));
    }

    @Test
    void connectionUsesHttpWhenTlsIsDisabled() {
        PlatformMiddlewareInstance i = instance("elasticsearch", "CLUSTER");
        i.setSpecJson("{\"tls\":false}");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);

        var c = service.connection("mwi_1", null);

        assertThat(c.getEndpoints().get(0).value()).startsWith("http://");
        assertThat(c.getEndpoints().get(1).value()).startsWith("http://");
    }

    @Test
    void connectionDefaultsToHttpsWhenTlsFlagIsAbsent() {
        PlatformMiddlewareInstance i = instance("elasticsearch", "CLUSTER");
        i.setSpecJson("{\"mode\":\"CLUSTER\"}");   // 老实例没有 tls 键
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);

        assertThat(service.connection("mwi_1", null).getEndpoints().get(0).value()).startsWith("https://");
    }

    @Test
    void connectionForPostgresShowsReadWriteAndReadOnly() {
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(instance("postgresql", "HA"));

        var c = service.connection("mwi_1", null);

        assertThat(c.getEndpoints()).extracting(MiddlewareConnectionDTO.Line::value)
                .containsExactly(
                        "mw-postgresql-abcdefgh-rw.demo.svc.cluster.local:5432",
                        "mw-postgresql-abcdefgh-ro.demo.svc.cluster.local:5432");
        assertThat(c.getCredentials().get(0).value()).contains("-app");
    }

    @Test
    void connectionIsScopedToTheInstancesTenant() {
        PlatformMiddlewareInstance i = instance("redis", "STANDALONE");
        i.setTenantId("t1");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);

        assertThatThrownBy(() -> service.connection("mwi_1", "t2"))
                .hasMessageContaining("无权操作该实例");
    }

    @Test
    void operationIsScopedToTheInstancesTenant() {
        PlatformMiddlewareOperation op = new PlatformMiddlewareOperation();
        op.setId(9L);
        op.setInstanceId("mwi_2");
        PlatformMiddlewareInstance other = new PlatformMiddlewareInstance();
        other.setId("mwi_2");
        other.setTenantId("t2");
        when(operations.selectByPrimaryKey(9L)).thenReturn(op);
        when(instances.selectByPrimaryKey("mwi_2")).thenReturn(other);

        assertThatThrownBy(() -> service.operation(9L, "t1"))
                .hasMessageContaining("无权");
    }

    @Test
    void catalogCountsOnlyTheCallersInstances() {
        when(client.operatorStatus(any())).thenReturn(List.of(status("redis", true, true)));
        PlatformMiddlewareInstance mine = new PlatformMiddlewareInstance();
        mine.setCode("redis");
        mine.setTenantId("t1");
        PlatformMiddlewareInstance other = new PlatformMiddlewareInstance();
        other.setCode("redis");
        other.setTenantId("t2");
        when(instances.selectByCluster("c1", null)).thenReturn(List.of(mine, other));

        var items = service.catalog("c1", "t1");

        assertThat(items).anyMatch(i -> i.getCode().equals("redis") && i.getInstanceCount() == 1);
    }

    @Test
    void catalogExposesModesAndChartBackedFlag() {
        when(client.operatorStatus(any())).thenReturn(List.of());
        when(instances.selectByCluster("c1", null)).thenReturn(List.of());

        var items = service.catalog("c1", null);

        assertThat(items).anyMatch(i -> i.getCode().equals("mysql") && i.getModes().size() == 2);
        assertThat(items).anyMatch(i -> i.getCode().equals("kafka") && i.getModes().size() == 1);
        assertThat(items).anyMatch(i -> i.getCode().equals("elasticsearch") && i.getModes().size() == 1);
        assertThat(items).anyMatch(i -> i.getCode().equals("nacos")
                && i.isChartBacked() && i.getModes().size() == 2);
        assertThat(items).anyMatch(i -> i.getCode().equals("postgresql")
                && i.getModes().size() == 2
                && i.getModes().stream().anyMatch(m -> m.getMode().equals("HA")));
        assertThat(items).anyMatch(i -> i.getCode().equals("rabbitmq") && i.getModes().size() == 2);
    }

    @Test
    void nacosCreateDoesNotRequireAnOperator() {
        var r = createRequest();
        r.setCode("nacos");
        r.setMode("STANDALONE");
        r.setReplicas(1);
        when(client.create(anyString(), any())).thenReturn(true);

        service.create(r, "admin", null);

        verify(client, org.mockito.Mockito.never()).operatorStatus(any());
    }

    @Test
    void tenantCanOnlySeeItsOwnInstances() {
        PlatformMiddlewareInstance mine = new PlatformMiddlewareInstance();
        mine.setId("mwi_1");
        mine.setTenantId("t1");
        PlatformMiddlewareInstance other = new PlatformMiddlewareInstance();
        other.setId("mwi_2");
        other.setTenantId("t2");
        when(instances.selectByCluster("c1", null)).thenReturn(List.of(mine, other));

        assertThat(service.list("c1", null, "t1", null))
                .extracting(PlatformMiddlewareInstance::getId).containsExactly("mwi_1");
    }

    @Test
    void listNarrowsToTheNamespaceChosenInTheTopBar() {
        PlatformMiddlewareInstance movie = new PlatformMiddlewareInstance();
        movie.setId("mwi_1");
        movie.setTenantId("t1");
        movie.setNamespace("movie");
        PlatformMiddlewareInstance demo = new PlatformMiddlewareInstance();
        demo.setId("mwi_2");
        demo.setTenantId("t1");
        demo.setNamespace("demo");
        when(instances.selectByCluster("c1", null)).thenReturn(List.of(movie, demo));

        // 顶栏的命名空间层：选了就只看这个 ns，清空 = 该集群下全部
        assertThat(service.list("c1", null, "t1", "demo"))
                .extracting(PlatformMiddlewareInstance::getId).containsExactly("mwi_2");
        assertThat(service.list("c1", null, "t1", null))
                .extracting(PlatformMiddlewareInstance::getId).containsExactly("mwi_1", "mwi_2");
    }

    @Test
    void getRejectsAnotherTenantsInstance() {
        PlatformMiddlewareInstance other = new PlatformMiddlewareInstance();
        other.setId("mwi_2");
        other.setTenantId("t2");
        when(instances.selectByPrimaryKey("mwi_2")).thenReturn(other);

        assertThatThrownBy(() -> service.get("mwi_2", "t1"))
                .hasMessageContaining("无权");
    }

    @Test
    void scaleEnablesMetricsAndPersistsTheFlag() {
        PlatformMiddlewareInstance i = instance("redis", "STANDALONE");
        // 台账里有 storageSize：开启指标时绝不能把它塞进 scale 请求（k8s-server 会当成扩存储直接拒）
        i.setSpecJson("{\"metricsEnabled\":false,\"storageSize\":\"10Gi\",\"cpuRequest\":\"100m\"}");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);

        var req = new com.coding.platformapi.models.MiddlewareScaleRequest();
        req.setInstanceId("mwi_1");
        req.setMetricsEnabled(true);
        service.scale(req, "admin", null);

        var dto = ArgumentCaptor.forClass(com.coding.common.models.middleware.MiddlewareScaleRequestDTO.class);
        verify(client).scale(org.mockito.ArgumentMatchers.eq("redis"), dto.capture());
        assertThat(dto.getValue().getMetricsEnabled()).isTrue();
        assertThat(dto.getValue().getInstanceId()).isEqualTo("mwi_1");
        assertThat(dto.getValue().getStorageSize()).isNull();
        assertThat(dto.getValue().getCpuRequest()).isNull();

        var saved = ArgumentCaptor.forClass(PlatformMiddlewareInstance.class);
        verify(instances).updateById(saved.capture());
        assertThat(saved.getValue().getSpecJson()).contains("\"metricsEnabled\":true");
    }

    @Test
    void scaleDoesNotTouchMetricsWhenAlreadyEnabled() {
        // 已开启：再提交不能重复改 CR（否则白白滚动重启一次）
        PlatformMiddlewareInstance i = instance("redis", "STANDALONE");
        i.setSpecJson("{\"metricsEnabled\":true}");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);

        var req = new com.coding.platformapi.models.MiddlewareScaleRequest();
        req.setInstanceId("mwi_1");
        req.setMetricsEnabled(true);
        service.scale(req, "admin", null);

        var dto = ArgumentCaptor.forClass(com.coding.common.models.middleware.MiddlewareScaleRequestDTO.class);
        verify(client).scale(org.mockito.ArgumentMatchers.eq("redis"), dto.capture());
        assertThat(dto.getValue().getMetricsEnabled()).isFalse();
        org.mockito.Mockito.verify(instances, org.mockito.Mockito.never())
                .updateById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void scaleEnableMetricsOnChartInstanceCarriesSpecForUpgrade() {
        // Nacos 走 helm upgrade：必须带上台账里的完整规格，否则 values 会被冲回默认
        PlatformMiddlewareInstance i = instance("nacos", "STANDALONE");
        i.setSpecJson("{\"metricsEnabled\":false,\"replicas\":1,\"cpuRequest\":\"200m\","
                + "\"memoryRequest\":\"512Mi\",\"storageSize\":\"5Gi\",\"storageClass\":\"nfs-client\"}");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);

        var req = new com.coding.platformapi.models.MiddlewareScaleRequest();
        req.setInstanceId("mwi_1");
        req.setMetricsEnabled(true);
        service.scale(req, "admin", null);

        var dto = ArgumentCaptor.forClass(com.coding.common.models.middleware.MiddlewareScaleRequestDTO.class);
        verify(client).scale(org.mockito.ArgumentMatchers.eq("nacos"), dto.capture());
        assertThat(dto.getValue().getStorageSize()).isEqualTo("5Gi");
        assertThat(dto.getValue().getStorageClass()).isEqualTo("nfs-client");
        assertThat(dto.getValue().getReplicas()).isEqualTo(1);
        assertThat(dto.getValue().getCpuRequest()).isEqualTo("200m");
    }

    @Test
    void catalogListsMilvusWithModesAndVersion() {
        when(client.operatorStatus(any())).thenReturn(List.of());
        when(instances.selectByCluster("c1", null)).thenReturn(List.of());

        var item = service.catalog("c1", null).stream()
                .filter(i -> "milvus".equals(i.getCode())).findFirst().orElseThrow();

        assertThat(item.isChartBacked()).isFalse();
        assertThat(item.getVersions()).containsExactly("2.6.25");
        assertThat(item.getModes()).extracting(m -> m.getMode())
                .containsExactly("STANDALONE", "CLUSTER");
    }

    @Test
    void milvusConnectionPointsAtServicePortsAndStorageSecret() {
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(instance("milvus", "CLUSTER"));

        var conn = service.connection("mwi_1", null);

        assertThat(conn.getEndpoints()).extracting(MiddlewareConnectionDTO.Line::value)
                .contains("mw-milvus-abcdefgh-milvus.demo.svc.cluster.local:19530",
                        "mw-milvus-abcdefgh-milvus.demo.svc.cluster.local:9091");
        assertThat(conn.getCredentials()).extracting(MiddlewareConnectionDTO.Line::value)
                .anyMatch(v -> v.contains("mw-milvus-abcdefgh-milvus-storage"));
    }

    @Test
    void milvusConnectionSwitchesToAuthSecretWhenEnabled() {
        // 开了鉴权，凭据就是 root 口令（Secret <名称>-milvus-auth）；没开时才回落到对象存储的 AK/SK
        PlatformMiddlewareInstance i = instance("milvus", "CLUSTER");
        i.setSpecJson("{\"objectStorageBucket\":\"b\",\"authEnabled\":true}");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);

        var conn = service.connection("mwi_1", null);

        assertThat(conn.getCredentials()).extracting(MiddlewareConnectionDTO.Line::value)
                .anyMatch(v -> v.contains("mw-milvus-abcdefgh-milvus-auth"))
                .anyMatch(v -> v.startsWith("root"));
        assertThat(conn.getNote()).contains("已开启鉴权");
        assertThat(conn.getInfo().getSecretName()).isEqualTo("mw-milvus-abcdefgh-milvus-auth");
        assertThat(conn.getInfo().getUsernameKey()).isEqualTo("username");
        assertThat(conn.getInfo().getPasswordKey()).isEqualTo("password");
    }

    @Test
    void milvusAuthEnabledReachesK8sAndIsRecordedWithoutThePassword() {
        when(client.operatorStatus(any())).thenReturn(List.of(status("milvus", true, true)));
        when(client.create(anyString(), any())).thenReturn(true);
        when(components.seaweedInstalled("c1")).thenReturn(true);
        when(objectStorage.s3Endpoint("c1")).thenReturn("http://s3:8333");
        ObjectStorageBucketView bucket = new ObjectStorageBucketView();
        bucket.setId("obk_1");
        bucket.setBucket("t-1234abcd-milvus-x");
        when(objectStorage.createBucket(any(), anyString())).thenReturn(bucket);
        when(objectStorage.issueCredential(any(), anyString()))
                .thenReturn(new ObjectCredentialDTO("AK", "SK"));

        MiddlewareCreateRequest r = milvusRequest("PLATFORM");
        r.setAuthEnabled(true);
        service.create(r, "admin", "t1");

        ArgumentCaptor<MiddlewareCreateRequestDTO> sent =
                ArgumentCaptor.forClass(MiddlewareCreateRequestDTO.class);
        verify(client).create(anyString(), sent.capture());
        assertThat(sent.getValue().getAuthEnabled()).isTrue();
        assertThat(sent.getValue().getPassword()).isNotBlank();

        ArgumentCaptor<PlatformMiddlewareInstance> saved =
                ArgumentCaptor.forClass(PlatformMiddlewareInstance.class);
        verify(instances).insert(saved.capture());
        assertThat(saved.getValue().getSpecJson())
                .contains("\"authEnabled\":true")
                .doesNotContain(sent.getValue().getPassword());
    }

    @Test
    void milvusPasswordChangeGeneratesAPasswordWhenTheUserLeavesItEmpty() {
        PlatformMiddlewareInstance i = instance("milvus", "STANDALONE");
        i.setTenantId("t1");
        i.setSpecJson("{\"authEnabled\":true}");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);
        when(client.changePassword(anyString(), any())).thenReturn(true);

        MiddlewarePasswordChangeRequest req = new MiddlewarePasswordChangeRequest();
        req.setInstanceId("mwi_1");
        service.changePassword(req, "t1");

        ArgumentCaptor<MiddlewarePasswordRequestDTO> sent =
                ArgumentCaptor.forClass(MiddlewarePasswordRequestDTO.class);
        verify(client).changePassword(anyString(), sent.capture());
        assertThat(sent.getValue().getNewPassword()).hasSizeGreaterThanOrEqualTo(8);
        assertThat(sent.getValue().getName()).isEqualTo("mw-milvus-abcdefgh");
        assertThat(sent.getValue().getNamespace()).isEqualTo("demo");
        assertThat(sent.getValue().getInstanceId()).isEqualTo("mwi_1");
        assertThat(sent.getValue().getTenantId()).isEqualTo("t1");
    }

    @Test
    void milvusPasswordChangeKeepsACustomPassword() {
        PlatformMiddlewareInstance i = instance("milvus", "STANDALONE");
        i.setTenantId("t1");
        i.setSpecJson("{\"authEnabled\":true}");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);
        when(client.changePassword(anyString(), any())).thenReturn(true);

        MiddlewarePasswordChangeRequest req = new MiddlewarePasswordChangeRequest();
        req.setInstanceId("mwi_1");
        req.setNewPassword("  my-own-pw-123  ");
        service.changePassword(req, "t1");

        ArgumentCaptor<MiddlewarePasswordRequestDTO> sent =
                ArgumentCaptor.forClass(MiddlewarePasswordRequestDTO.class);
        verify(client).changePassword(anyString(), sent.capture());
        assertThat(sent.getValue().getNewPassword()).isEqualTo("my-own-pw-123");
    }

    @Test
    void milvusPasswordChangeRefusesInstancesWithoutAuth() {
        PlatformMiddlewareInstance i = instance("milvus", "STANDALONE");
        i.setTenantId("t1");
        i.setSpecJson("{\"authEnabled\":false}");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);

        MiddlewarePasswordChangeRequest req = new MiddlewarePasswordChangeRequest();
        req.setInstanceId("mwi_1");

        assertThatThrownBy(() -> service.changePassword(req, "t1"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("没有开启鉴权");
        verify(client, never()).changePassword(anyString(), any());
    }

    @Test
    void milvusPasswordChangeRefusesOtherMiddlewaresAndShortPasswords() {
        PlatformMiddlewareInstance redis = instance("redis", "SENTINEL");
        redis.setTenantId("t1");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(redis);
        MiddlewarePasswordChangeRequest req = new MiddlewarePasswordChangeRequest();
        req.setInstanceId("mwi_1");
        assertThatThrownBy(() -> service.changePassword(req, "t1"))
                .hasMessageContaining("不支持改密码");

        PlatformMiddlewareInstance milvus = instance("milvus", "STANDALONE");
        milvus.setTenantId("t1");
        milvus.setSpecJson("{\"authEnabled\":true}");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(milvus);
        req.setNewPassword("short");
        assertThatThrownBy(() -> service.changePassword(req, "t1"))
                .hasMessageContaining("8~72");
        verify(client, never()).changePassword(anyString(), any());
    }

    @Test
    void formOptionsReportsObjectStorageAvailability() {
        when(allocations.listByCluster("c1")).thenReturn(List.of());
        when(components.seaweedInstalled("c1")).thenReturn(true);

        assertThat(service.formOptions("c1", null).isObjectStorageAvailable()).isTrue();

        when(components.seaweedInstalled("c1")).thenThrow(new RuntimeException("boom"));
        assertThat(service.formOptions("c1", null).isObjectStorageAvailable()).isFalse();
    }

    @Test
    void everyMetricTemplateFormatsWithThreeArguments() {
        // MetricsService.businessMetric 只传 cluster/namespace/pod 三个参数；
        // 模板里出现第 4 个 %s 会让 String.format 抛异常，整块业务指标 + 资源图一起 500。
        for (var item : catalog.items()) {
            for (var def : item.metrics()) {
                assertThatCode(() -> String.format(def.query(), "c1", "demo", "pod-.*"))
                        .as(item.code() + "." + def.key())
                        .doesNotThrowAnyException();
            }
        }
    }

    private MiddlewareCreateRequest milvusRequest(String storageType) {
        MiddlewareCreateRequest r = new MiddlewareCreateRequest();
        r.setClusterId("c1");
        r.setCode("milvus");
        r.setMode("STANDALONE");
        r.setNamespace("demo");
        r.setReplicas(1);
        r.setVersion("2.6.25");
        r.setCpuRequest("500m");
        r.setCpuLimit("2");
        r.setMemoryRequest("2Gi");
        r.setMemoryLimit("4Gi");
        r.setStorageSize("20Gi");
        r.setLocalStorageSize("10Gi");
        r.setEtcdReplicas(1);
        r.setObjectStorageType(storageType);
        if ("CUSTOM".equals(storageType)) {
            r.setObjectStorageEndpoint("s3.example.com:9000");
            r.setObjectStorageBucket("my-bucket");
            r.setObjectStorageAccessKey("AK");
            r.setObjectStorageSecretKey("SK");
        }
        return r;
    }

    @Test
    void milvusPlatformStorageCreatesBucketAndPassesCredentials() {
        when(client.operatorStatus(any())).thenReturn(List.of(status("milvus", true, true)));
        when(client.create(anyString(), any())).thenReturn(true);
        when(components.seaweedInstalled("c1")).thenReturn(true);
        when(objectStorage.s3Endpoint("c1"))
                .thenReturn("http://platform-seaweedfs-s3.platform-storage:8333");

        ObjectStorageBucketView bucket = new ObjectStorageBucketView();
        bucket.setId("obk_1");
        bucket.setBucket("t-1234abcd-milvus-mw-milvus-abc12345");
        when(objectStorage.createBucket(any(), anyString())).thenReturn(bucket);
        when(objectStorage.issueCredential(any(), anyString()))
                .thenReturn(new ObjectCredentialDTO("AK_PLATFORM", "SK_PLATFORM"));

        service.create(milvusRequest("PLATFORM"), "admin", "t1");

        ArgumentCaptor<ObjectStorageCreateBucket> bucketBody =
                ArgumentCaptor.forClass(ObjectStorageCreateBucket.class);
        verify(objectStorage).createBucket(bucketBody.capture(), anyString());
        assertThat(bucketBody.getValue().getTenantId()).isEqualTo("t1");
        assertThat(bucketBody.getValue().getName()).startsWith("milvus-mw-milvus-");
        assertThat(bucketBody.getValue().getQuotaBytes()).isEqualTo(20L * 1024 * 1024 * 1024);

        ArgumentCaptor<MiddlewareCreateRequestDTO> sent =
                ArgumentCaptor.forClass(MiddlewareCreateRequestDTO.class);
        verify(client).create(anyString(), sent.capture());
        assertThat(sent.getValue().getObjectStorageEndpoint())
                .isEqualTo("platform-seaweedfs-s3.platform-storage:8333");
        assertThat(sent.getValue().getObjectStorageBucket()).isEqualTo(bucket.getBucket());
        assertThat(sent.getValue().getObjectStorageAccessKey()).isEqualTo("AK_PLATFORM");
        assertThat(sent.getValue().getObjectStorageSecretKey()).isEqualTo("SK_PLATFORM");

        ArgumentCaptor<PlatformMiddlewareInstance> saved =
                ArgumentCaptor.forClass(PlatformMiddlewareInstance.class);
        verify(instances).insert(saved.capture());
        assertThat(saved.getValue().getSpecJson())
                .contains("obk_1")
                .doesNotContain("AK_PLATFORM")
                .doesNotContain("SK_PLATFORM");
    }

    @Test
    void milvusCreateFailureKeepsTheBucketToAvoidDeletingLiveData() {
        // 网关把传输层异常也包成 CloudPlatformException：CR 可能已经建成、Pod 正在跑，
        // 这时删桶 = 删掉实例唯一的数据。宁可留一个空桶让运维核对，也不能误删。
        when(client.operatorStatus(any())).thenReturn(List.of(status("milvus", true, true)));
        when(client.create(anyString(), any())).thenThrow(new CloudPlatformException(
                com.coding.common.exception.EnumResponseType.ERROR, "调用 k8s-server 失败: timeout"));
        when(components.seaweedInstalled("c1")).thenReturn(true);
        when(objectStorage.s3Endpoint("c1"))
                .thenReturn("http://platform-seaweedfs-s3.platform-storage:8333");
        ObjectStorageBucketView bucket = new ObjectStorageBucketView();
        bucket.setId("obk_1");
        bucket.setBucket("t-1234abcd-milvus-mw-milvus-abc12345");
        when(objectStorage.createBucket(any(), anyString())).thenReturn(bucket);
        when(objectStorage.issueCredential(any(), anyString()))
                .thenReturn(new ObjectCredentialDTO("AK", "SK"));

        assertThatThrownBy(() -> service.create(milvusRequest("PLATFORM"), "admin", "t1"))
                .hasMessageContaining("timeout");

        verify(objectStorage, never()).deleteBucket(any(), anyString());
    }

    @Test
    void createRejectsCustomStorageWithoutCredentials() {
        when(client.operatorStatus(any())).thenReturn(List.of(status("milvus", true, true)));
        var r = milvusRequest("CUSTOM");
        r.setObjectStorageSecretKey("");

        assertThatThrownBy(() -> service.create(r, "admin", "t1"))
                .hasMessageContaining("AccessKey");
    }

    @Test
    void platformStorageNeedsATenantFromTheTopBar() {
        // 平台桶必须有主人：tenantId 由前端从顶栏（资源上下文）带过来；
        // 平台视图且确实没有可用租户时，前端会禁用「平台对象存储」
        when(client.operatorStatus(any())).thenReturn(List.of(status("milvus", true, true)));

        assertThatThrownBy(() -> service.create(milvusRequest("PLATFORM"), "admin", null))
                .hasMessageContaining("顶栏");
        verify(objectStorage, never()).createBucket(any(), anyString());
    }

    @Test
    void deleteWithoutKeepPvcDeletesPlatformBucket() {
        PlatformMiddlewareInstance i = instance("milvus", "CLUSTER");
        i.setTenantId("t1");
        i.setSpecJson("{\"objectStorageType\":\"PLATFORM\",\"objectStorageBucketId\":\"obk_1\"}");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);
        when(client.delete(anyString(), any())).thenReturn(true);

        var req = new MiddlewareDeleteRequest();
        req.setInstanceId("mwi_1");
        req.setKeepPvc(false);
        service.delete(req, "admin", "t1");

        ArgumentCaptor<ObjectStorageBucketRef> ref = ArgumentCaptor.forClass(ObjectStorageBucketRef.class);
        verify(objectStorage).deleteBucket(ref.capture(), anyString());
        assertThat(ref.getValue().getBucketId()).isEqualTo("obk_1");
    }

    @Test
    void deleteWithKeepPvcKeepsBucket() {
        PlatformMiddlewareInstance i = instance("milvus", "CLUSTER");
        i.setTenantId("t1");
        i.setSpecJson("{\"objectStorageType\":\"PLATFORM\",\"objectStorageBucketId\":\"obk_1\"}");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);
        when(client.delete(anyString(), any())).thenReturn(true);

        var req = new MiddlewareDeleteRequest();
        req.setInstanceId("mwi_1");
        req.setKeepPvc(true);
        service.delete(req, "admin", "t1");

        verify(objectStorage, never()).deleteBucket(any(), anyString());
    }

    @Test
    void createRejectsEtcdReplicasOutOfRangeBeforeCreatingBucket() {
        // 校验要在建桶之前：否则越界值会先建出一个空桶、再被 k8s-server 拒
        when(client.operatorStatus(any())).thenReturn(List.of(status("milvus", true, true)));
        var r = milvusRequest("PLATFORM");
        r.setEtcdReplicas(4);

        assertThatThrownBy(() -> service.create(r, "admin", "t1"))
                .hasMessageContaining("etcd");
        verify(objectStorage, never()).createBucket(any(), anyString());
    }

    @Test
    void createDeletesBucketWhenLedgerInsertFails() {
        // 建桶之后、调 k8s 之前失败 = 确定没建成，可以安全回收（连同凭据）
        when(client.operatorStatus(any())).thenReturn(List.of(status("milvus", true, true)));
        when(components.seaweedInstalled("c1")).thenReturn(true);
        when(objectStorage.s3Endpoint("c1"))
                .thenReturn("http://platform-seaweedfs-s3.platform-storage:8333");
        ObjectStorageBucketView bucket = new ObjectStorageBucketView();
        bucket.setId("obk_1");
        bucket.setBucket("t-1234abcd-milvus-mw-milvus-abc12345");
        when(objectStorage.createBucket(any(), anyString())).thenReturn(bucket);
        when(objectStorage.issueCredential(any(), anyString()))
                .thenReturn(new ObjectCredentialDTO("AK", "SK"));
        when(instances.insert(any())).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.create(milvusRequest("PLATFORM"), "admin", "t1"))
                .hasMessageContaining("db down");

        ArgumentCaptor<ObjectStorageBucketRef> ref = ArgumentCaptor.forClass(ObjectStorageBucketRef.class);
        verify(objectStorage).deleteBucket(ref.capture(), anyString());
        assertThat(ref.getValue().getBucketId()).isEqualTo("obk_1");
        verify(client, never()).create(anyString(), any());
    }

    @Test
    void milvusConnectionShowsPlatformBucket() {
        PlatformMiddlewareInstance i = instance("milvus", "STANDALONE");
        i.setSpecJson("{\"objectStorageType\":\"PLATFORM\","
                + "\"objectStorageBucket\":\"t-1234abcd-milvus-mw-milvus-abc12345\"}");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);

        var conn = service.connection("mwi_1", null);

        assertThat(conn.getCredentials()).extracting(MiddlewareConnectionDTO.Line::value)
                .anyMatch(v -> v.contains("t-1234abcd-milvus-mw-milvus-abc12345"));
    }

    @Test
    void milvusConnectionShowsCustomS3Endpoint() {
        PlatformMiddlewareInstance i = instance("milvus", "STANDALONE");
        i.setSpecJson("{\"objectStorageType\":\"CUSTOM\","
                + "\"objectStorageEndpoint\":\"s3.example.com:9000\",\"objectStorageBucket\":\"my-bucket\"}");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);

        var conn = service.connection("mwi_1", null);

        assertThat(conn.getCredentials()).extracting(MiddlewareConnectionDTO.Line::value)
                .anyMatch(v -> v.contains("s3.example.com:9000") && v.contains("my-bucket"));
    }

    // ==================== ES 的 Kibana 开关（编辑可双向） ====================

    @Test
    void scalePassesKibanaExpectationAndRecordsIt() {
        PlatformMiddlewareInstance i = instance("elasticsearch", "CLUSTER");
        i.setSpecJson("{\"kibana\":true,\"tls\":true}");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);

        var req = new com.coding.platformapi.models.MiddlewareScaleRequest();
        req.setInstanceId("mwi_1");
        req.setKibana(false);
        service.scale(req, "admin", null);

        var dto = ArgumentCaptor.forClass(com.coding.common.models.middleware.MiddlewareScaleRequestDTO.class);
        verify(client).scale(org.mockito.ArgumentMatchers.eq("elasticsearch"), dto.capture());
        // 三态必须原样透传：false 是「确保不存在」，不能被折成 null（那样就成了不处理）
        assertThat(dto.getValue().getKibana()).isFalse();

        var saved = ArgumentCaptor.forClass(PlatformMiddlewareInstance.class);
        verify(instances).updateById(saved.capture());
        assertThat(saved.getValue().getSpecJson()).contains("\"kibana\":false");
    }

    @Test
    void scaleWithoutKibanaLeavesItAlone() {
        PlatformMiddlewareInstance i = instance("elasticsearch", "CLUSTER");
        i.setSpecJson("{\"kibana\":true}");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);

        var req = new com.coding.platformapi.models.MiddlewareScaleRequest();
        req.setInstanceId("mwi_1");
        req.setCpuRequest("1");
        service.scale(req, "admin", null);

        var dto = ArgumentCaptor.forClass(com.coding.common.models.middleware.MiddlewareScaleRequestDTO.class);
        verify(client).scale(org.mockito.ArgumentMatchers.eq("elasticsearch"), dto.capture());
        assertThat(dto.getValue().getKibana()).isNull();
        verify(instances, never()).updateById(any());   // 没动 Kibana 就不回写台账
    }

    @Test
    void connectionHidesKibanaWhenDisabledInSpec() {
        PlatformMiddlewareInstance i = instance("elasticsearch", "CLUSTER");
        i.setSpecJson("{\"kibana\":false}");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);

        var c = service.connection("mwi_1", null);

        assertThat(c.getEndpoints()).extracting(MiddlewareConnectionDTO.Line::label)
                .containsExactly("HTTPS 接口");
    }

    @Test
    void connectionKeepsKibanaForInstancesWithoutTheFlag() {
        // 老实例 spec_json 里没有 kibana 键 → 保持改动前的行为（照常列出）
        PlatformMiddlewareInstance i = instance("elasticsearch", "CLUSTER");
        i.setSpecJson("{\"mode\":\"CLUSTER\"}");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(i);

        var c = service.connection("mwi_1", null);

        assertThat(c.getEndpoints()).extracting(MiddlewareConnectionDTO.Line::label)
                .containsExactly("HTTPS 接口", "Kibana");
    }
}
