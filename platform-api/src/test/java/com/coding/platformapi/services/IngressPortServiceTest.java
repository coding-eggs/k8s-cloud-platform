package com.coding.platformapi.services;

import com.coding.data.mapper.system.PlatformIngressPortMapper;
import com.coding.data.models.system.PlatformIngressPort;
import com.coding.platformapi.k8s.K8sAppClient;
import com.coding.platformapi.services.IngressPortService.PortRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngressPortServiceTest {

    private static final String C = "c1";
    private static final PortRange POOL = new PortRange(20000, 20009);
    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");

    private PlatformIngressPortMapper mapper;
    private K8sAppClient components;
    private IngressPortService svc;
    private com.coding.data.mapper.system.PlatformComponentOperationMapper operations;
    private final List<PlatformIngressPort> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mapper = mock(PlatformIngressPortMapper.class);
        components = mock(K8sAppClient.class);
        operations = mock(com.coding.data.mapper.system.PlatformComponentOperationMapper.class);
        svc = new IngressPortService(mapper, components, operations, Clock.fixed(NOW, ZoneId.of("UTC")));
        rows.clear();
        when(mapper.selectByCluster(C)).thenReturn(rows);
        // 20002 未在 Service 上声明
        when(components.servicePorts(C, "istio-system", "external-gateway"))
                .thenReturn(List.of(80, 443, 20000, 20001, 20003, 20004, 20005));
        when(mapper.insert(any())).thenReturn(1);
        when(mapper.reactivate(any())).thenReturn(1);
    }

    private static PlatformIngressPort row(long id, int port, String status, Instant releasedAt) {
        PlatformIngressPort r = new PlatformIngressPort();
        r.setId(id);
        r.setClusterId(C);
        r.setPort(port);
        r.setNamespace("other");
        r.setAppId("other-app");
        r.setServiceKey("x");
        r.setProtocol("HTTP");
        r.setStatus(status);
        r.setReleasedAt(releasedAt == null ? null : Date.from(releasedAt));
        return r;
    }

    @Test
    void reusesExistingPortForSameService() {
        PlatformIngressPort mine = row(9, 20003, "ACTIVE", null);
        mine.setAppId("app1");
        mine.setServiceKey("api");
        when(mapper.selectActiveByService("app1", "api")).thenReturn(mine);
        assertThat(svc.allocate(C, "shop", "app1", "api", "HTTP", POOL)).isEqualTo(20003);
        verify(mapper, never()).insert(any());
    }

    @Test
    void picksLowestFreeDeclaredPort() {
        rows.add(row(1, 20000, "ACTIVE", null));
        rows.add(row(2, 20001, "RELEASED", NOW.minusSeconds(60)));   // 冷却中
        assertThat(svc.allocate(C, "shop", "app1", "web", "HTTP", POOL)).isEqualTo(20003);
        verify(mapper).insert(any());
    }

    @Test
    void reusesReleasedRowAfterCooldown() {
        rows.add(row(1, 20000, "RELEASED", NOW.minusSeconds(11 * 60)));
        assertThat(svc.allocate(C, "shop", "app1", "web", "TCP", POOL)).isEqualTo(20000);
        verify(mapper).reactivate(any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void retriesOnDuplicateKey() {
        doThrow(new DuplicateKeyException("dup")).doReturn(1).when(mapper).insert(any());
        assertThat(svc.allocate(C, "shop", "app1", "web", "HTTP", POOL)).isEqualTo(20001);
        verify(mapper, times(2)).insert(any());
    }

    @Test
    void exhaustedPoolFails() {
        for (int p : List.of(20000, 20001, 20003, 20004, 20005)) {
            rows.add(row(p, p, "ACTIVE", null));
        }
        assertThatThrownBy(() -> svc.allocate(C, "shop", "app1", "web", "HTTP", POOL))
                .hasMessageContaining("端口池已用完");
    }

    @Test
    void protocolSwitchKeepsPort() {
        PlatformIngressPort mine = row(9, 20003, "ACTIVE", null);
        mine.setAppId("app1");
        mine.setServiceKey("api");
        when(mapper.selectActiveByService("app1", "api")).thenReturn(mine);
        assertThat(svc.allocate(C, "shop", "app1", "api", "TCP", POOL)).isEqualTo(20003);
        verify(mapper).updateProtocol(eq(9L), eq("TCP"), any());
    }

    @Test
    void releaseMarksRowsReleased() {
        svc.release("app1", "api");
        verify(mapper).releaseService(eq("app1"), eq("api"), any());
        svc.releaseApp("app1");
        verify(mapper).releaseApp(eq("app1"), any());
    }

    @Test
    void activeOutsideFiltersByRange() {
        rows.add(row(1, 20000, "ACTIVE", null));
        rows.add(row(2, 20500, "ACTIVE", null));
        rows.add(row(3, 20600, "RELEASED", NOW));
        assertThat(svc.activeOutside(C, new PortRange(20000, 20099)))
                .extracting(PlatformIngressPort::getPort).containsExactly(20500);
    }

    @Test
    void portRangeBasics() {
        assertThat(POOL.size()).isEqualTo(10);
        assertThat(POOL.contains(20009)).isTrue();
        assertThat(POOL.contains(20010)).isFalse();
        verify(mapper, never()).updateProtocol(anyLong(), any(), any());
    }

    @Test
    void currentPoolComesFromTheLastSuccessfulIstioOperation() {
        com.coding.data.models.system.PlatformComponentOperation last =
                new com.coding.data.models.system.PlatformComponentOperation();
        last.setValuesSnapshot("{\"external-gateway\":{\"platform\":{\"ingressPortPool\":{\"from\":20000,\"to\":20099}}}}");
        when(operations.selectLatestSuccess(C, "istio")).thenReturn(last);
        assertThat(svc.currentPool(C)).isEqualTo(new PortRange(20000, 20099));
        when(operations.selectLatestSuccess("c2", "istio")).thenReturn(null);
        assertThat(svc.currentPool("c2")).isNull();
    }

    // ------------------------------------------------------------ 终审修复

    @Test
    void lockKeepsProtocolOfAnAlreadyHeldPort() {
        // 保存时不改协议：已上线的 HTTP 路由还挂着，协议在发布 PRECHECK 才按快照改（I1）
        PlatformIngressPort mine = row(9, 20003, "ACTIVE", null);
        mine.setAppId("app1");
        mine.setServiceKey("api");
        when(mapper.selectActiveByService("app1", "api")).thenReturn(mine);

        assertThat(svc.lock(C, "shop", "app1", "api", "TCP", POOL)).isFalse();
        verify(mapper, never()).updateProtocol(anyLong(), any(), any());
    }

    @Test
    void lockReportsANewlyCreatedPort() {
        assertThat(svc.lock(C, "shop", "app1", "api", "HTTP", POOL)).isTrue();
        verify(mapper).insert(any());
    }

    @Test
    void concurrentAllocationForTheSameServiceReturnsTheWinnersPort() {
        // 同一服务两个请求并发：后到的撞 (app_id, service_key) ACTIVE 唯一键，拿赢家的端口，不另占一个（I2）
        PlatformIngressPort winner = row(5, 20004, "ACTIVE", null);
        winner.setAppId("app1");
        winner.setServiceKey("api");
        when(mapper.selectActiveByService("app1", "api")).thenReturn(null, winner);
        doThrow(new DuplicateKeyException("uk_ingress_active_service")).when(mapper).insert(any());

        assertThat(svc.lock(C, "shop", "app1", "api", "HTTP", POOL)).isFalse();
        verify(mapper, times(1)).insert(any());
    }

    @Test
    void currentPoolRefusedWhileAnIstioOperationRuns() {
        // 组件变更进行中：网关 Service 端口可能正在缩，此时分配可能落进被删的段（I5）
        when(operations.countRunning(C, "istio")).thenReturn(1);

        assertThatThrownBy(() -> svc.currentPool(C)).hasMessageContaining("Istio");
    }
}
