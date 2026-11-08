package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.middleware.MiddlewareExposeRequestDTO;
import com.coding.data.mapper.system.PlatformMiddlewareExposeMapper;
import com.coding.data.mapper.system.PlatformMiddlewareInstanceMapper;
import com.coding.data.models.system.PlatformIngressPort;
import com.coding.data.models.system.PlatformMiddlewareExpose;
import com.coding.data.models.system.PlatformMiddlewareInstance;
import com.coding.platformapi.k8s.K8sAppClient;
import com.coding.platformapi.k8s.K8sMiddlewareClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MiddlewareExposeServiceTest {

    private static final IngressPortService.PortRange POOL = new IngressPortService.PortRange(20000, 20999);

    private PlatformMiddlewareInstanceMapper instances;
    private PlatformMiddlewareExposeMapper rows;
    private IngressPortService ports;
    private IngressListenerSync listeners;
    private AppNodePortAllocator nodePorts;
    private K8sMiddlewareClient client;
    private K8sAppClient appClient;
    private ComponentFeatureGuard guard;
    private MiddlewareExposeService service;
    private PlatformMiddlewareInstance mysql;

    @BeforeEach
    void setUp() {
        instances = mock(PlatformMiddlewareInstanceMapper.class);
        rows = mock(PlatformMiddlewareExposeMapper.class);
        ports = mock(IngressPortService.class);
        listeners = mock(IngressListenerSync.class);
        nodePorts = mock(AppNodePortAllocator.class);
        client = mock(K8sMiddlewareClient.class);
        appClient = mock(K8sAppClient.class);
        guard = mock(ComponentFeatureGuard.class);
        service = new MiddlewareExposeService(instances, rows, ports, listeners, nodePorts, client, appClient, guard);
        mysql = new PlatformMiddlewareInstance();
        mysql.setId("mwi_1");
        mysql.setTenantId("t1");
        mysql.setClusterId("c1");
        mysql.setNamespace("shop");
        mysql.setName("orders");
        mysql.setCode("mysql");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(mysql);
        when(rows.selectByInstance("mwi_1")).thenReturn(List.of());
        // syncReleasing：先 apply（这里什么都不做）再执行 release 回调
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(3)).run();
            return null;
        }).when(listeners).syncReleasing(any(), any(), any(), any());
    }

    private static PlatformMiddlewareExpose row(String key, String mode, Integer nodePort) {
        PlatformMiddlewareExpose r = new PlatformMiddlewareExpose();
        r.setInstanceId("mwi_1");
        r.setEndpointKey(key);
        r.setMode(mode);
        r.setNodePort(nodePort);
        return r;
    }

    @Test
    void gatewayAllocatesAPoolPortSyncsTheListenerSetThenAppliesATcpRoute() {
        when(ports.currentPool("c1")).thenReturn(POOL);
        when(ports.lock("c1", "shop", PlatformIngressPort.OWNER_MIDDLEWARE, "mwi_1", "rw", "TCP", POOL)).thenReturn(true);
        when(ports.portOf("mwi_1", "rw")).thenReturn(20007);

        service.update("mwi_1", "rw", "GATEWAY", "t1");

        var order = inOrder(listeners, client);
        order.verify(listeners).sync("c1", "shop");
        ArgumentCaptor<MiddlewareExposeRequestDTO> cap = ArgumentCaptor.forClass(MiddlewareExposeRequestDTO.class);
        order.verify(client).exposeApply(cap.capture());
        MiddlewareExposeRequestDTO r = cap.getValue();
        assertThat(r.getMode()).isEqualTo("GATEWAY");
        assertThat(r.getProtocol()).isEqualTo("TCP");
        assertThat(r.getServiceName()).isEqualTo("orders-haproxy");
        assertThat(r.getServicePort()).isEqualTo(3306);
        assertThat(r.getSectionName()).isEqualTo("p20007");
        assertThat(r.getListenerSetName()).isEqualTo("platform-ingress");
        ArgumentCaptor<PlatformMiddlewareExpose> saved = ArgumentCaptor.forClass(PlatformMiddlewareExpose.class);
        verify(rows).upsert(saved.capture());
        assertThat(saved.getValue().getMode()).isEqualTo("GATEWAY");
        assertThat(saved.getValue().getNodePort()).isNull();
    }

    @Test
    void gatewayRequiresTheRoutesFeatureAndAPool() {
        doThrow(new CloudPlatformException(com.coding.common.exception.EnumResponseType.ERROR, "未装"))
                .when(guard).require(eq("c1"), any(), any());
        assertThatThrownBy(() -> service.update("mwi_1", "rw", "GATEWAY", "t1")).isInstanceOf(CloudPlatformException.class);
        verify(ports, never()).lock(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void newlyAllocatedPortIsReleasedWhenTheListenerSetSyncFails() {
        when(ports.currentPool("c1")).thenReturn(POOL);
        when(ports.lock(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(ports.portOf("mwi_1", "rw")).thenReturn(20007);
        doThrow(new RuntimeException("k8s down")).when(listeners).sync("c1", "shop");

        assertThatThrownBy(() -> service.update("mwi_1", "rw", "GATEWAY", "t1")).isInstanceOf(RuntimeException.class);

        verify(ports).release("mwi_1", "rw");
        verify(client, never()).exposeApply(any());
        verify(rows, never()).upsert(any());
    }

    @Test
    void nodePortGetsAPlatformChosenPortAndKeepsItOnReapply() {
        when(nodePorts.allocate("c1", "shop", "orders-expose-rw", "mwi_1", "rw")).thenReturn(31234);

        service.update("mwi_1", "rw", "NODEPORT", "t1");

        ArgumentCaptor<MiddlewareExposeRequestDTO> cap = ArgumentCaptor.forClass(MiddlewareExposeRequestDTO.class);
        verify(client).exposeApply(cap.capture());
        assertThat(cap.getValue().getMode()).isEqualTo("NODEPORT");
        assertThat(cap.getValue().getNodePort()).isEqualTo(31234);

        // 再次保存 NodePort：沿用已分配的端口，不重新挑
        when(rows.selectByInstance("mwi_1")).thenReturn(List.of(row("rw", "NODEPORT", 31234)));
        service.update("mwi_1", "rw", "NODEPORT", "t1");
        verify(nodePorts, org.mockito.Mockito.times(1)).allocate(any(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void leavingTheGatewayAppliesTheNewObjectsFirstThenReleasesThePortAfterTheListenerSetSync() {
        when(rows.selectByInstance("mwi_1")).thenReturn(List.of(row("rw", "GATEWAY", null)));

        service.update("mwi_1", "rw", "NONE", "t1");

        var order = inOrder(client, listeners, ports);
        order.verify(client).exposeApply(any());
        ArgumentCaptor<Predicate<PlatformIngressPort>> leaving = ArgumentCaptor.forClass(Predicate.class);
        order.verify(listeners).syncReleasing(eq("c1"), eq("shop"), leaving.capture(), any());
        order.verify(ports).release("mwi_1", "rw");
        PlatformIngressPort mine = new PlatformIngressPort();
        mine.setAppId("mwi_1");
        mine.setServiceKey("rw");
        PlatformIngressPort other = new PlatformIngressPort();
        other.setAppId("mwi_1");
        other.setServiceKey("ro");
        assertThat(leaving.getValue().test(mine)).isTrue();
        assertThat(leaving.getValue().test(other)).isFalse();
        verify(rows).deleteByKey("mwi_1", "rw");
    }

    @Test
    void otherTenantsInstanceIsRejected() {
        assertThatThrownBy(() -> service.update("mwi_1", "rw", "NODEPORT", "t2")).isInstanceOf(CloudPlatformException.class);
        verify(client, never()).exposeApply(any());
    }

    @Test
    void unsupportedTypesAndUnknownEndpointsAreRejected() {
        mysql.setCode("kafka");
        assertThatThrownBy(() -> service.update("mwi_1", "bootstrap", "NODEPORT", "t1"))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("Kafka");
        mysql.setCode("mysql");
        assertThatThrownBy(() -> service.update("mwi_1", "nope", "NODEPORT", "t1"))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void cleanupDeletesObjectsReleasesPortsAndRows() {
        PlatformIngressPort held = new PlatformIngressPort();
        held.setAppId("mwi_1");
        held.setServiceKey("rw");
        when(ports.activeOfApp("mwi_1")).thenReturn(List.of(held));

        service.cleanup(mysql);

        verify(client).exposeDeleteAll(any());
        verify(ports).releaseApp("mwi_1");
        verify(rows).deleteByInstance("mwi_1");
    }

    @Test
    void cleanupKeepsGoingWhenTheClusterCallFails() {
        doThrow(new RuntimeException("down")).when(client).exposeDeleteAll(any());
        when(ports.activeOfApp("mwi_1")).thenReturn(List.of());

        service.cleanup(mysql);   // 不抛

        verify(rows).deleteByInstance("mwi_1");
    }

    @Test
    void listShowsEveryEndpointWithItsModeAndPort() {
        when(rows.selectByInstance("mwi_1")).thenReturn(List.of(row("ro", "NODEPORT", 31001)));
        when(ports.currentPool("c1")).thenReturn(POOL);

        var view = service.list("mwi_1", "t1");

        assertThat(view.unsupportedReason()).isNull();
        assertThat(view.endpoints()).extracting(e -> e.key()).containsExactly("rw", "ro");
        assertThat(view.endpoints().get(0).mode()).isEqualTo("NONE");
        assertThat(view.endpoints().get(1).mode()).isEqualTo("NODEPORT");
        assertThat(view.endpoints().get(1).port()).isEqualTo(31001);
        assertThat(view.endpoints().get(0).target()).isEqualTo("orders-haproxy:3306");
    }
}
