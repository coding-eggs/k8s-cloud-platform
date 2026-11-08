package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.ListenerSetDTO;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.models.system.PlatformApplication;
import com.coding.data.models.system.PlatformIngressPort;
import com.coding.platformapi.k8s.K8sClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListenerSetServiceTest {

    private final K8sClient k8s = mock(K8sClient.class);
    private final IngressPortService ports = mock(IngressPortService.class);
    private final PlatformApplicationMapper apps = mock(PlatformApplicationMapper.class);
    private final com.coding.data.mapper.system.PlatformMiddlewareInstanceMapper middleware =
            mock(com.coding.data.mapper.system.PlatformMiddlewareInstanceMapper.class);
    private final ListenerSetService svc = new ListenerSetService(k8s, ports, apps, middleware);

    private static ListenerSetDTO set(String name, String ns, int... listenerPorts) {
        ListenerSetDTO d = new ListenerSetDTO();
        d.setName(name);
        d.setNamespace(ns);
        d.setClusterId("c1");
        d.setListeners(java.util.Arrays.stream(listenerPorts).mapToObj(p -> {
            ListenerSetDTO.Listener l = new ListenerSetDTO.Listener();
            l.setName("p" + p);
            l.setPort(p);
            l.setProtocol("HTTP");
            return l;
        }).toList());
        return d;
    }

    private static PlatformIngressPort port(int p, String appId, String key) {
        PlatformIngressPort r = new PlatformIngressPort();
        r.setPort(p);
        r.setAppId(appId);
        r.setServiceKey(key);
        r.setStatus(PlatformIngressPort.ACTIVE);
        return r;
    }

    private static PlatformApplication app(String id, String name) {
        PlatformApplication a = new PlatformApplication();
        a.setId(id);
        a.setName(name);
        return a;
    }

    @Test
    void platformIngressListenersGetTheirOwningAppAndService() {
        ListenerSetDTO q = new ListenerSetDTO();
        q.setClusterId("c1");
        q.setNamespace("shop");
        when(k8s.list(any())).thenReturn(List.of(set(IngressListenerSync.LISTENER_SET, "shop", 20001, 20002)));
        when(ports.activeInNamespace("c1", "shop")).thenReturn(List.of(port(20001, "app1", "api")));
        when(apps.selectById("app1")).thenReturn(app("app1", "mall"));

        ListenerSetDTO out = svc.list(q).get(0);

        assertThat(out.getPlatformManaged()).isTrue();
        assertThat(out.getOwners()).hasSize(2);
        ListenerSetDTO.ListenerOwner owned = out.getOwners().get(0);
        assertThat(owned.getListenerName()).isEqualTo("p20001");
        assertThat(owned.getAppId()).isEqualTo("app1");
        assertThat(owned.getAppName()).isEqualTo("mall");
        assertThat(owned.getServiceKey()).isEqualTo("api");
        // 分配表里没有 20002 → 残留 listener：只有端口、没有归属
        ListenerSetDTO.ListenerOwner orphan = out.getOwners().get(1);
        assertThat(orphan.getPort()).isEqualTo(20002);
        assertThat(orphan.getAppId()).isNull();
    }

    @Test
    void userListenerSetsAreShownWithoutOwnership() {
        // 用户自建的 ListenerSet 端口与分配表无关：不补归属，也不查分配表
        ListenerSetDTO q = new ListenerSetDTO();
        q.setClusterId("c1");
        when(k8s.listAll(any())).thenReturn(List.of(set("my-listeners", "shop", 20001)));

        ListenerSetDTO out = svc.listAll(q).get(0);

        assertThat(out.getPlatformManaged()).isFalse();
        assertThat(out.getOwners()).isNull();
        verify(ports, never()).activeInNamespace(any(), any());
    }

    @Test
    void ownerOfADeletedAppKeepsTheIdWithoutAName() {
        when(k8s.get(any())).thenReturn(set(IngressListenerSync.LISTENER_SET, "shop", 20001));
        when(ports.activeInNamespace("c1", "shop")).thenReturn(List.of(port(20001, "gone", "api")));
        when(apps.selectById("gone")).thenReturn(null);

        ListenerSetDTO out = svc.get(IngressListenerSync.LISTENER_SET, "t1", "c1", "shop");

        assertThat(out.getOwners().get(0).getAppId()).isEqualTo("gone");
        assertThat(out.getOwners().get(0).getAppName()).isNull();
    }

    @Test
    void listAllLooksUpEachNamespaceOnce() {
        ListenerSetDTO q = new ListenerSetDTO();
        q.setClusterId("c1");
        when(k8s.listAll(any())).thenReturn(List.of(
                set(IngressListenerSync.LISTENER_SET, "a", 20001),
                set(IngressListenerSync.LISTENER_SET, "b", 20002)));
        when(ports.activeInNamespace(any(), any())).thenReturn(List.of());

        svc.listAll(q);

        verify(ports).activeInNamespace("c1", "a");
        verify(ports).activeInNamespace("c1", "b");
    }

    @Test
    void middlewareOwnedListenersShowTheInstanceName() {
        when(k8s.get(any())).thenReturn(set(IngressListenerSync.LISTENER_SET, "shop", 20001));
        PlatformIngressPort r = port(20001, "mwi_1", "rw");
        r.setOwnerKind(PlatformIngressPort.OWNER_MIDDLEWARE);
        when(ports.activeInNamespace("c1", "shop")).thenReturn(List.of(r));
        com.coding.data.models.system.PlatformMiddlewareInstance inst = new com.coding.data.models.system.PlatformMiddlewareInstance();
        inst.setId("mwi_1");
        inst.setName("orders");
        when(middleware.selectByPrimaryKey("mwi_1")).thenReturn(inst);

        var o = svc.get(IngressListenerSync.LISTENER_SET, "t1", "c1", "shop").getOwners().get(0);

        assertThat(o.getOwnerKind()).isEqualTo("MIDDLEWARE");
        assertThat(o.getAppName()).isEqualTo("orders");
        verify(apps, never()).selectById(any());
    }
}
