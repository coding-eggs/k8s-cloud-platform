package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareOperatorStatusDTO;
import com.coding.common.models.k8s.dto.StorageClassDTO;
import com.coding.common.models.middleware.MiddlewareConnectionDTO;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.system.PlatformMiddlewareInstanceMapper;
import com.coding.data.mapper.system.PlatformMiddlewareOperationMapper;
import com.coding.data.mapper.system.PlatformMiddlewareOperatorMapper;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.system.PlatformMiddlewareInstance;
import com.coding.data.models.system.PlatformMiddlewareOperation;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.k8s.K8sMiddlewareClient;
import com.coding.platformapi.middleware.MiddlewareCatalog;
import com.coding.platformapi.models.MiddlewareCreateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MiddlewareServiceTest {

    private final PlatformMiddlewareInstanceMapper instances = mock(PlatformMiddlewareInstanceMapper.class);
    private final PlatformMiddlewareOperationMapper operations = mock(PlatformMiddlewareOperationMapper.class);
    private final PlatformMiddlewareOperatorMapper operators = mock(PlatformMiddlewareOperatorMapper.class);
    private final K8sMiddlewareClient client = mock(K8sMiddlewareClient.class);
    private final K8sClient k8s = mock(K8sClient.class);
    private final PlatformTenantNamespaceMapper allocations = mock(PlatformTenantNamespaceMapper.class);

    private final MiddlewareService service = new MiddlewareService(
            instances, operations, operators, client, new MiddlewareCatalog(),
            JsonMapper.builder().build(), k8s, allocations);

    @BeforeEach
    void opsGetIds() {
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

        assertThat(service.list("c1", null, "t1"))
                .extracting(PlatformMiddlewareInstance::getId).containsExactly("mwi_1");
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
}
