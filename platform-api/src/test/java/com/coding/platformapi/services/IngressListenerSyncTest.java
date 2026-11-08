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
    private final IngressListenerSync sync = new IngressListenerSync(ports, client);

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
}
