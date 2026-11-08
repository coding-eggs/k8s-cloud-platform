package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.IngressListenerDTO;
import com.coding.common.models.k8s.dto.IngressListenerSetRequestDTO;
import com.coding.data.models.system.PlatformIngressPort;
import com.coding.platformapi.k8s.K8sAppClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngressListenerSyncTest {

    private final IngressPortService ports = mock(IngressPortService.class);
    private final K8sAppClient client = mock(K8sAppClient.class);
    private final java.util.List<String> locked = new java.util.ArrayList<>();
    private final IngressNamespaceLock lock = (cluster, ns, body) -> {
        locked.add(cluster + "/" + ns);
        body.run();
    };
    private final IngressListenerSync sync = new IngressListenerSync(ports, client, lock);

    private static PlatformIngressPort port(int p, String protocol) {
        PlatformIngressPort r = new PlatformIngressPort();
        r.setPort(p);
        r.setProtocol(protocol);
        r.setStatus(PlatformIngressPort.ACTIVE);
        return r;
    }

    @Test
    void listenersFromActivePorts() {
        when(ports.activeInNamespace("c1", "demo")).thenReturn(List.of(port(20001, "HTTP"), port(20002, "TCP")));
        when(client.applyIngressListenerSet(any())).thenReturn(true);

        sync.sync("c1", "demo");

        ArgumentCaptor<IngressListenerSetRequestDTO> req = ArgumentCaptor.forClass(IngressListenerSetRequestDTO.class);
        verify(client).applyIngressListenerSet(req.capture());
        IngressListenerSetRequestDTO r = req.getValue();
        assertThat(r.getClusterId()).isEqualTo("c1");
        assertThat(r.getNamespace()).isEqualTo("demo");
        assertThat(r.getName()).isEqualTo(IngressListenerSync.LISTENER_SET);
        assertThat(r.getParentNamespace()).isEqualTo("istio-system");
        assertThat(r.getParentName()).isEqualTo("external-gateway");
        assertThat(r.getNamespaceLabels()).containsEntry(IngressListenerSync.NS_LABEL, "true");
        assertThat(r.getListeners()).extracting(IngressListenerDTO::getName).containsExactly("p20001", "p20002");
        assertThat(r.getListeners()).extracting(IngressListenerDTO::getProtocol).containsExactly("HTTP", "TCP");
        assertThat(r.getListeners()).extracting(IngressListenerDTO::getPort).containsExactly(20001, 20002);
    }

    @Test
    void emptyNamespaceSendsEmptyList() {
        when(ports.activeInNamespace("c1", "demo")).thenReturn(List.of());
        when(client.applyIngressListenerSet(any())).thenReturn(true);

        sync.sync("c1", "demo");

        ArgumentCaptor<IngressListenerSetRequestDTO> req = ArgumentCaptor.forClass(IngressListenerSetRequestDTO.class);
        verify(client).applyIngressListenerSet(req.capture());
        assertThat(req.getValue().getListeners()).isEmpty();
    }

    @Test
    void missingCrdRejectedWhenThereAreListeners() {
        when(ports.activeInNamespace("c1", "demo")).thenReturn(List.of(port(20001, "HTTP")));
        when(client.applyIngressListenerSet(any())).thenReturn(false);

        assertThatThrownBy(() -> sync.sync("c1", "demo")).hasMessageContaining("ListenerSet");
    }

    @Test
    void missingCrdIsFineWhenNothingToRemove() {
        // 没装 CRD 的集群上清空 = 本来就没有可删的东西，不能因此让下线 / 删服务失败
        when(ports.activeInNamespace("c1", "demo")).thenReturn(List.of());
        when(client.applyIngressListenerSet(any())).thenReturn(false);

        sync.sync("c1", "demo");
    }

    @Test
    void sectionNameIsPPlusPort() {
        assertThat(IngressListenerSync.sectionName(20003)).isEqualTo("p20003");
    }

    // ------------------------------------------------------------ 终审修复

    private static PlatformIngressPort owned(int p, String app, String key) {
        PlatformIngressPort r = port(p, "HTTP");
        r.setAppId(app);
        r.setServiceKey(key);
        return r;
    }

    @Test
    void syncRunsUnderTheNamespaceLock() {
        // 同命名空间两次整份同步交错会丢 listener（I3）：读分配表 + 下发必须在锁里
        when(ports.activeInNamespace("c1", "demo")).thenReturn(List.of(port(20001, "HTTP")));
        when(client.applyIngressListenerSet(any())).thenAnswer(inv -> {
            assertThat(locked).containsExactly("c1/demo");
            return true;
        });

        sync.sync("c1", "demo");

        assertThat(locked).containsExactly("c1/demo");
    }

    @Test
    void syncReleasingLeavesOutReleasedPortsAndReleasesAfterApply() {
        when(ports.activeInNamespace("c1", "demo"))
                .thenReturn(List.of(owned(20001, "app1", "api"), owned(20002, "app2", "web")));
        when(client.applyIngressListenerSet(any())).thenReturn(true);
        Runnable release = mock(Runnable.class);

        sync.syncReleasing("c1", "demo", p -> "app1".equals(p.getAppId()), release);

        ArgumentCaptor<IngressListenerSetRequestDTO> req = ArgumentCaptor.forClass(IngressListenerSetRequestDTO.class);
        var order = org.mockito.Mockito.inOrder(client, release);
        order.verify(client).applyIngressListenerSet(req.capture());
        order.verify(release).run();
        assertThat(req.getValue().getListeners()).extracting(IngressListenerDTO::getName).containsExactly("p20002");
    }

    @Test
    void portsStayHeldWhenTheListenerSetCannotBeApplied() {
        // 下发失败不释放：否则 listener 留在集群上、端口冷却后分给别的命名空间 → 冲突（I4）
        when(ports.activeInNamespace("c1", "demo"))
                .thenReturn(List.of(owned(20001, "app1", "api"), owned(20002, "app2", "web")));
        when(client.applyIngressListenerSet(any())).thenReturn(false);
        Runnable release = mock(Runnable.class);

        assertThatThrownBy(() -> sync.syncReleasing("c1", "demo", p -> "app1".equals(p.getAppId()), release))
                .hasMessageContaining("ListenerSet");
        org.mockito.Mockito.verifyNoInteractions(release);
    }
}
