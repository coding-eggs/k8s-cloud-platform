package com.coding.k8score.converter.impl.monitoring;

import com.coding.common.models.k8s.dto.PodMonitorDTO;
import com.coding.common.models.k8s.dto.PodMonitorEndpointDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PodMonitorConverterTest {

    private final PodMonitorConverter c = new PodMonitorConverter();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> specOf(GenericKubernetesResource res) {
        return (Map<String, Object>) res.getAdditionalProperties().get("spec");
    }

    private PodMonitorDTO dtoWithEndpoint(String port) {
        PodMonitorDTO dto = new PodMonitorDTO();
        dto.setName("pm1");
        dto.setNamespace("ns1");
        dto.setMatchLabels(Map.of("app", "demo"));
        PodMonitorEndpointDTO ep = new PodMonitorEndpointDTO();
        ep.setPort(port);
        ep.setPath("/metrics");
        ep.setInterval("30s");
        dto.setPodMetricsEndpoints(List.of(ep));
        return dto;
    }

    @Test
    @SuppressWarnings("unchecked")
    void convert_emits_podMetricsEndpoints_selector_and_kind() {
        GenericKubernetesResource res = c.convert(dtoWithEndpoint("9090"));
        assertThat(res.getApiVersion()).isEqualTo("monitoring.coreos.com/v1");
        assertThat(res.getKind()).isEqualTo("PodMonitor");
        Map<String, Object> spec = specOf(res);
        assertThat(spec).containsKey("selector").doesNotContainKey("endpoints");
        assertThat(((Map<String, Object>) spec.get("selector")).get("matchLabels"))
                .isEqualTo(Map.of("app", "demo"));
        List<Map<String, Object>> eps = (List<Map<String, Object>>) spec.get("podMetricsEndpoints");
        assertThat(eps).hasSize(1);
        assertThat(eps.get(0)).containsEntry("path", "/metrics").containsEntry("interval", "30s");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convert_numeric_port_becomes_portNumber_and_drops_port() {
        List<Map<String, Object>> eps =
                (List<Map<String, Object>>) specOf(c.convert(dtoWithEndpoint("9090"))).get("podMetricsEndpoints");
        assertThat(eps.get(0)).containsEntry("portNumber", 9090).doesNotContainKey("port");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convert_named_port_stays_string_port() {
        List<Map<String, Object>> eps =
                (List<Map<String, Object>>) specOf(c.convert(dtoWithEndpoint("metrics"))).get("podMetricsEndpoints");
        assertThat(eps.get(0)).containsEntry("port", "metrics").doesNotContainKey("portNumber");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convert_out_of_range_numeric_string_falls_back_to_port_string() {
        // 超端口范围的纯数字（如误输 999999）→ 按命名端口处理，避免 int 溢出
        List<Map<String, Object>> eps =
                (List<Map<String, Object>>) specOf(c.convert(dtoWithEndpoint("999999"))).get("podMetricsEndpoints");
        assertThat(eps.get(0)).containsEntry("port", "999999").doesNotContainKey("portNumber");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convert_honorLabels_emitted_only_when_true() {
        PodMonitorDTO dto = dtoWithEndpoint("metrics");
        List<Map<String, Object>> eps =
                (List<Map<String, Object>>) specOf(c.convert(dto)).get("podMetricsEndpoints");
        assertThat(eps.get(0)).doesNotContainKey("honorLabels");
        dto.getPodMetricsEndpoints().get(0).setHonorLabels(true);
        eps = (List<Map<String, Object>>) specOf(c.convert(dto)).get("podMetricsEndpoints");
        assertThat(eps.get(0)).containsEntry("honorLabels", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void revert_prefers_named_port_then_reads_portNumber() {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion("monitoring.coreos.com/v1");
        res.setKind("PodMonitor");
        res.setMetadata(new ObjectMetaBuilder().withName("pm1").withNamespace("ns1")
                .withCreationTimestamp("2026-01-01T00:00:00Z").build());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("selector", Map.of("matchLabels", Map.of("app", "demo")));
        Map<String, Object> epNum = new LinkedHashMap<>();
        epNum.put("portNumber", 9090);
        epNum.put("honorLabels", true);
        Map<String, Object> epNamed = new LinkedHashMap<>();
        epNamed.put("port", "metrics");
        epNamed.put("portNumber", 9090); // 理论互斥；共存时以 port 为准
        spec.put("podMetricsEndpoints", List.of(epNum, epNamed));
        res.setAdditionalProperty("spec", spec);

        PodMonitorDTO dto = c.revert(res);
        assertThat(dto.getName()).isEqualTo("pm1");
        assertThat(dto.getCreationTime()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(dto.getMatchLabels()).containsEntry("app", "demo");
        assertThat(dto.getPodMetricsEndpoints()).hasSize(2);
        assertThat(dto.getPodMetricsEndpoints().get(0).getPort()).isEqualTo("9090");
        assertThat(dto.getPodMetricsEndpoints().get(0).getHonorLabels()).isTrue();
        assertThat(dto.getPodMetricsEndpoints().get(1).getPort()).isEqualTo("metrics");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertForUpdate_preserves_unmodeled_keys_and_replaces_modeled() {
        // live endpoint：建模字段旧值 + 外部字段（authorization/proxyUrl/bearerTokenFile/tlsConfig.ca）
        Map<String, Object> liveTls = new LinkedHashMap<>();
        liveTls.put("insecureSkipVerify", true);
        liveTls.put("ca", Map.of("configMap", Map.of("name", "cm")));
        Map<String, Object> liveEp = new LinkedHashMap<>();
        liveEp.put("port", "metrics");
        liveEp.put("path", "/old");
        liveEp.put("honorLabels", true);
        liveEp.put("authorization", Map.of("type", "Bearer"));
        liveEp.put("proxyUrl", "http://p:1");
        liveEp.put("bearerTokenFile", "/var/run/secrets/t");
        liveEp.put("tlsConfig", liveTls);
        Map<String, Object> liveSpec = new LinkedHashMap<>();
        liveSpec.put("podMetricsEndpoints", List.of(liveEp));
        GenericKubernetesResource live = new GenericKubernetesResource();
        live.setMetadata(new ObjectMetaBuilder().withName("pm1").withNamespace("ns1").build());
        live.setAdditionalProperty("spec", liveSpec);

        // dto：端口改数字、清空 honorLabels、未动 tls（null）；relabelings 新加一条
        PodMonitorDTO dto = dtoWithEndpoint("9091");
        dto.getPodMetricsEndpoints().get(0).setHonorLabels(false);
        PodMonitorEndpointDTO.Relabeling r = new PodMonitorEndpointDTO.Relabeling();
        r.setAction("keep");
        r.setSourceLabels(List.of("__meta_kubernetes_pod_name"));
        dto.getPodMetricsEndpoints().get(0).setRelabelings(List.of(r));

        List<Map<String, Object>> merged =
                (List<Map<String, Object>>) specOf(c.convertForUpdate(dto, live)).get("podMetricsEndpoints");
        assertThat(merged).hasSize(1);
        Map<String, Object> ep = merged.get(0);
        assertThat(ep).containsEntry("portNumber", 9091)
                .doesNotContainKey("port")                      // 命名→数字：旧 port 被建模列表清理
                .containsEntry("honorLabels", false)             // 显式 false 照常下发（putBool 只跳 null；false 与缺省语义等价，CRD 合法）
                .containsEntry("path", "/metrics")
                .containsEntry("authorization", Map.of("type", "Bearer")) // 外部字段原样保留
                .containsEntry("proxyUrl", "http://p:1")
                .containsEntry("bearerTokenFile", "/var/run/secrets/t");
        assertThat((Map<String, Object>) ep.get("tlsConfig"))
                .containsEntry("insecureSkipVerify", true)       // 深合并：dto tlsConfig=null → 线上整块保留
                .containsKey("ca");
        assertThat((List<Map<String, Object>>) ep.get("relabelings")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertForUpdate_blank_port_removes_both_port_and_portNumber() {
        // 注：UI 校验 port 必填，此为 overlay 兜底语义（spec §4.2）
        Map<String, Object> liveEp = new LinkedHashMap<>();
        liveEp.put("port", "metrics");
        liveEp.put("path", "/old");
        Map<String, Object> liveSpec = new LinkedHashMap<>();
        liveSpec.put("podMetricsEndpoints", List.of(liveEp));
        GenericKubernetesResource live = new GenericKubernetesResource();
        live.setMetadata(new ObjectMetaBuilder().withName("pm1").withNamespace("ns1").build());
        live.setAdditionalProperty("spec", liveSpec);

        PodMonitorDTO dto = dtoWithEndpoint(null);
        List<Map<String, Object>> merged =
                (List<Map<String, Object>>) specOf(c.convertForUpdate(dto, live)).get("podMetricsEndpoints");
        assertThat(merged.get(0)).doesNotContainKey("port").doesNotContainKey("portNumber");
    }
}
