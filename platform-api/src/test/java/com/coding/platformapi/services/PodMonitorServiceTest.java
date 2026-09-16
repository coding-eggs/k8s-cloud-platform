package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.PodDTO;
import com.coding.common.models.k8s.dto.PodMonitorDTO;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.platformapi.k8s.K8sResourceClient;
import com.coding.platformapi.metrics.PromDiscoveryClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PodMonitorServiceTest {

    private final K8sResourceClient k8s = mock(K8sResourceClient.class);
    private final K8sClusterMapper clusterMapper = mock(K8sClusterMapper.class);
    private final PromDiscoveryClient prom = mock(PromDiscoveryClient.class);
    private final PodMonitorService svc = new PodMonitorService(k8s, clusterMapper, prom);

    private PodMonitorDTO base() {
        PodMonitorDTO dto = new PodMonitorDTO();
        dto.setName("pm1");
        dto.setNamespace("ns1");
        dto.setTenantId("t1");
        dto.setClusterId("c1");
        return dto;
    }

    private PodDTO podWithLabels(Map<String, String> labels) {
        PodDTO p = new PodDTO();
        p.setName("p1");
        p.setNamespace("ns1");
        p.setLabels(labels);
        return p;
    }

    @Test
    void create_resolves_podRef_labels_into_matchLabels_and_strips_podRef() {
        when(k8s.get(any(PodDTO.class))).thenReturn(podWithLabels(Map.of("app", "demo")));
        when(k8s.create(any(PodMonitorDTO.class))).thenAnswer(i -> i.getArgument(0));
        PodMonitorDTO body = base();
        body.setMatchLabels(Map.of("stale", "x"));
        PodMonitorDTO.PodRef ref = new PodMonitorDTO.PodRef();
        ref.setName("p1");
        ref.setNamespace("ns1");
        body.setPodRef(ref);

        svc.create(body);

        ArgumentCaptor<PodMonitorDTO> cap = ArgumentCaptor.forClass(PodMonitorDTO.class);
        verify(k8s).create(cap.capture());
        assertThat(cap.getValue().getMatchLabels()).containsOnly(Map.entry("app", "demo"));
        assertThat(cap.getValue().getPodRef()).isNull();
    }

    @Test
    void create_pod_missing_throws_and_no_create() {
        when(k8s.get(any(PodDTO.class))).thenReturn(null);
        PodMonitorDTO body = base();
        PodMonitorDTO.PodRef ref = new PodMonitorDTO.PodRef();
        ref.setName("gone");
        body.setPodRef(ref);

        assertThatThrownBy(() -> svc.create(body)).isInstanceOf(CloudPlatformException.class);
        verify(k8s, never()).create(any(PodMonitorDTO.class));
    }

    @Test
    void create_pod_without_labels_throws() {
        when(k8s.get(any(PodDTO.class))).thenReturn(podWithLabels(Map.of()));
        PodMonitorDTO body = base();
        PodMonitorDTO.PodRef ref = new PodMonitorDTO.PodRef();
        ref.setName("p1");
        body.setPodRef(ref);

        assertThatThrownBy(() -> svc.create(body)).isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void create_without_podRef_keeps_matchLabels_as_is() {
        when(k8s.create(any(PodMonitorDTO.class))).thenAnswer(i -> i.getArgument(0));
        PodMonitorDTO body = base();
        body.setMatchLabels(Map.of("a", "b"));

        svc.create(body);

        verify(k8s, never()).get(any(PodDTO.class));
        ArgumentCaptor<PodMonitorDTO> cap = ArgumentCaptor.forClass(PodMonitorDTO.class);
        verify(k8s).create(cap.capture());
        assertThat(cap.getValue().getMatchLabels()).containsEntry("a", "b");
    }

    @Test
    void relabelLabels_only_scans_podMonitor_prefixed_pools() {
        K8sCluster cluster = new K8sCluster();
        cluster.setPrometheusUrl("http://prom:9090/");
        when(clusterMapper.selectByPrimaryKey("c1")).thenReturn(cluster);
        when(prom.scrapePools("http://prom:9090")).thenReturn(
                List.of("podMonitor/ns1/pm1/0", "serviceMonitor/ns1/pm1/0", "podMonitor/ns1/pm1/1"));
        when(prom.labelSets("http://prom:9090", "podMonitor/ns1/pm1/0"))
                .thenReturn(new PromDiscoveryClient.TargetLabelSets(Set.of("__address__"), Set.of("app")));
        when(prom.labelSets("http://prom:9090", "podMonitor/ns1/pm1/1"))
                .thenReturn(new PromDiscoveryClient.TargetLabelSets(Set.of("node"), Set.of("pod")));

        Map<String, List<String>> out = svc.relabelLabels("c1", "ns1", "pm1");

        verify(prom, never()).labelSets(anyString(), eq("serviceMonitor/ns1/pm1/0"));
        assertThat(out.get("relabeling")).containsExactly("__address__", "node");
        assertThat(out.get("metricRelabeling")).containsExactly("app", "pod");
    }

    @Test
    void relabelLabels_prom_unreachable_degrades_to_empty() {
        K8sCluster cluster = new K8sCluster();
        cluster.setPrometheusUrl("http://prom:9090");
        when(clusterMapper.selectByPrimaryKey("c1")).thenReturn(cluster);
        when(prom.scrapePools(anyString())).thenThrow(new RuntimeException("down"));

        Map<String, List<String>> out = svc.relabelLabels("c1", "ns1", "pm1");
        assertThat(out.get("relabeling")).isEmpty();
        assertThat(out.get("metricRelabeling")).isEmpty();
    }

    @Test
    void metricNames_builds_matchers_only_for_podMonitor_pools() {
        K8sCluster cluster = new K8sCluster();
        cluster.setPrometheusUrl("http://prom:9090");
        when(clusterMapper.selectByPrimaryKey("c1")).thenReturn(cluster);
        when(prom.scrapePools("http://prom:9090")).thenReturn(List.of("podMonitor/ns1/pm1/0"));
        when(prom.targetRefs("http://prom:9090", "podMonitor/ns1/pm1/0"))
                .thenReturn(List.of(new PromDiscoveryClient.TargetRef("j\\1", "inst")));
        when(prom.metricNames(eq("http://prom:9090"), anyList())).thenReturn(List.of("up"));

        assertThat(svc.metricNames("c1", "ns1", "pm1")).containsExactly("up");
        ArgumentCaptor<List<String>> mm = ArgumentCaptor.forClass(List.class);
        verify(prom).metricNames(eq("http://prom:9090"), mm.capture());
        assertThat(mm.getValue()).containsExactly("{job=\"j\\\\1\",instance=\"inst\"}");
    }
}
