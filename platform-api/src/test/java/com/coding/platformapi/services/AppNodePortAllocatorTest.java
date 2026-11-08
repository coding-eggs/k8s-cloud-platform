package com.coding.platformapi.services;

import com.coding.common.models.app.NodePortUseDTO;
import com.coding.common.exception.CloudPlatformException;
import com.coding.data.mapper.system.PlatformAppServiceMapper;
import com.coding.data.models.system.PlatformAppService;
import com.coding.data.models.system.PlatformApplication;
import com.coding.platformapi.k8s.K8sAppClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppNodePortAllocatorTest {

    private final K8sAppClient client = mock(K8sAppClient.class);
    private final PlatformAppServiceMapper services = mock(PlatformAppServiceMapper.class);
    private final com.coding.data.mapper.system.PlatformMiddlewareExposeMapper middleware =
            mock(com.coding.data.mapper.system.PlatformMiddlewareExposeMapper.class);
    private final AppNodePortAllocator allocator = new AppNodePortAllocator(client, services, middleware, new Random(7));
    private PlatformApplication app;

    @BeforeEach
    void setUp() {
        app = new PlatformApplication();
        app.setId("app1");
        app.setClusterId("c1");
        app.setNamespace("demo");
        app.setSlug("shop");
    }

    private static NodePortUseDTO use(String ns, String name, int port) {
        NodePortUseDTO u = new NodePortUseDTO();
        u.setNamespace(ns);
        u.setName(name);
        u.setNodePort(port);
        return u;
    }

    private static Map<String, Object> nodeport(Integer port) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("port", 8080);
        d.put("expose", "nodeport");
        if (port != null) {
            d.put("nodePort", port);
        }
        return d;
    }

    private static PlatformAppService saved(String appId, String key, int nodePort) {
        PlatformAppService s = new PlatformAppService();
        s.setAppId(appId);
        s.setServiceKey(key);
        s.setDesiredJson("{\"port\":80,\"expose\":\"nodeport\",\"nodePort\":" + nodePort + "}");
        return s;
    }

    @Test
    void emptyNodePortGetsAFreePortInTheSafeRangeWrittenIntoDesired() {
        when(client.usedNodePorts("c1")).thenReturn(List.of(use("x", "a", 30000), use("istio-system", "gw", 20000)));
        when(services.selectNodePortServicesInCluster("c1")).thenReturn(List.of(saved("app9", "web", 30001)));

        Map<String, Object> out = allocator.assign(app, "api", nodeport(null));

        int p = (Integer) out.get("nodePort");
        assertThat(p).isBetween(30000, 32767).isNotIn(30000, 30001);
    }

    @Test
    void filledNodePortTakenByAnotherServiceIsRejected() {
        when(client.usedNodePorts("c1")).thenReturn(List.of(use("other", "svc", 31000)));
        when(services.selectNodePortServicesInCluster("c1")).thenReturn(List.of());

        assertThatThrownBy(() -> allocator.assign(app, "api", nodeport(31000)))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("31000");
    }

    @Test
    void filledNodePortSavedByAnotherAppServiceIsRejected() {
        when(client.usedNodePorts("c1")).thenReturn(List.of());
        when(services.selectNodePortServicesInCluster("c1")).thenReturn(List.of(saved("app9", "web", 31000)));

        assertThatThrownBy(() -> allocator.assign(app, "api", nodeport(31000)))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("31000");
    }

    @Test
    void theServicesOwnPortIsNotAConflict() {
        // 自己线上 Service（shop-api）和自己的台账行占着的端口，重复保存不算冲突
        when(client.usedNodePorts("c1")).thenReturn(List.of(use("demo", "shop-api", 31000)));
        when(services.selectNodePortServicesInCluster("c1")).thenReturn(List.of(saved("app1", "api", 31000)));

        Map<String, Object> out = allocator.assign(app, "api", nodeport(31000));

        assertThat(out.get("nodePort")).isEqualTo(31000);
    }

    @Test
    void nonNodePortDesiredIsLeftAlone() {
        Map<String, Object> d = new HashMap<>(Map.of("port", 8080, "expose", "gateway-http"));

        assertThat(allocator.assign(app, "api", d)).isEqualTo(d);
        verify(client, never()).usedNodePorts("c1");
    }

    @Test
    void fullRangeIsAnError() {
        Set<Integer> all = IntStream.rangeClosed(30000, 32767).boxed().collect(Collectors.toSet());
        assertThatThrownBy(() -> AppNodePortAllocator.pick(all, new Random(1)))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void clusterUnreadableFailsTheSaveInsteadOfGuessing() {
        when(client.usedNodePorts("c1")).thenThrow(new RuntimeException("down"));

        assertThatThrownBy(() -> allocator.assign(app, "api", nodeport(null)))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void middlewareAllocatedPortsAreAvoidedButTheEndpointsOwnIsNot() {
        com.coding.data.models.system.PlatformMiddlewareExpose other = new com.coding.data.models.system.PlatformMiddlewareExpose();
        other.setInstanceId("mwi_other");
        other.setEndpointKey("rw");
        other.setNodePort(31000);
        when(client.usedNodePorts("c1")).thenReturn(List.of(use("x", "a", 31000)));
        when(services.selectNodePortServicesInCluster("c1")).thenReturn(List.of());
        when(middleware.selectNodePortsInCluster("c1")).thenReturn(List.of(other));

        assertThatThrownBy(() -> allocator.assign(app, "api", nodeport(31000)))
                .isInstanceOf(CloudPlatformException.class);
        // 同一个端点再分配：自己的行不算占用（集群里没有别人占 31000 时可以拿到它）
        when(client.usedNodePorts("c1")).thenReturn(List.of());
        java.util.Set<Integer> got = new java.util.HashSet<>();
        for (int i = 0; i < 50; i++) {
            got.add(allocator.allocate("c1", "shop", "orders-expose-rw", "mwi_other", "rw"));
        }
        assertThat(got).allMatch(p -> p >= 30000 && p <= 32767);
    }
}
