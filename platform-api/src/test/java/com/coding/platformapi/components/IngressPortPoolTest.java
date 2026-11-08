package com.coding.platformapi.components;

import com.coding.platformapi.services.IngressPortService.PortRange;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngressPortPoolTest {

    @Test
    void parseValidatesRange() {
        assertThat(IngressPortPool.parse(Map.of("from", 20000, "to", 20999))).isEqualTo(new PortRange(20000, 20999));
        assertThat(IngressPortPool.parse(null)).isNull();
        assertThatThrownBy(() -> IngressPortPool.parse(Map.of("from", 20000, "to", 26000))).hasMessageContaining("5000");
        assertThatThrownBy(() -> IngressPortPool.parse(Map.of("from", 21000, "to", 20000))).hasMessageContaining("起始");
    }

    @Test
    void bothEndsEmptyMeansDisabled() {
        // 表单两个框都清空 = 停用；提交的是 {from:null,to:null}（Map 才能在深合并里盖掉旧值）
        Map<String, Object> cleared = new HashMap<>();
        cleared.put("from", null);
        cleared.put("to", null);
        assertThat(IngressPortPool.parse(cleared)).isNull();
        assertThat(IngressPortPool.parse(Map.of())).isNull();
        assertThatThrownBy(() -> IngressPortPool.parse(Map.of("from", 20000))).hasMessageContaining("结束");
        assertThatThrownBy(() -> IngressPortPool.parse(Map.of("from", 0, "to", 10))).hasMessageContaining("1-65535");
    }

    @Test
    void overlapWithPortTableRejected() {
        assertThatThrownBy(() -> IngressPortPool.assertNoOverlap(new PortRange(400, 500),
                List.of(Map.of("name", "https", "port", 443, "nodePort", 443)))).hasMessageContaining("443");
        IngressPortPool.assertNoOverlap(new PortRange(20000, 20999),
                List.of(Map.of("name", "https", "port", 443, "nodePort", 443)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void renderExpandsPoolAndDropsPlatformKey() {
        Map<String, Object> in = Map.of(
                "service", Map.of("type", "NodePort", "ports", List.of(Map.of("name", "http2", "port", 80))),
                "platform", Map.of("ingressPortPool", Map.of("from", 20000, "to", 20002)));
        Map<String, Object> out = IngressPortPool.render(in);
        List<Map<String, Object>> ports = (List<Map<String, Object>>) ((Map<String, Object>) out.get("service")).get("ports");
        assertThat(out).doesNotContainKey("platform");
        assertThat(ports).extracting(p -> p.get("name")).containsExactly("http2", "pool-20000", "pool-20001", "pool-20002");
        assertThat(ports.get(1)).containsEntry("nodePort", 20000).containsEntry("targetPort", 20000)
                .doesNotContainKey("listener");
        assertThat(in).containsKey("platform"); // 不修改入参
    }

    @Test
    void renderWithoutPoolOnlyDropsPlatformKey() {
        Map<String, Object> out = IngressPortPool.render(Map.of("service", Map.of("type", "NodePort")));
        assertThat(out).containsOnlyKeys("service");
    }

    @Test
    void fromSnapshotReadsTheGatewayReleaseValues() {
        String snapshot = "{\"external-gateway\":{\"platform\":{\"ingressPortPool\":{\"from\":20000,\"to\":20099}}}}";
        assertThat(IngressPortPool.fromSnapshot(snapshot)).isEqualTo(new PortRange(20000, 20099));
        assertThat(IngressPortPool.fromSnapshot("{\"external-gateway\":{}}")).isNull();
        assertThat(IngressPortPool.fromSnapshot(null)).isNull();
    }
}
