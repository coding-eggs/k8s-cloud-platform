package com.coding.k8score.converter.impl.calico;

import com.coding.common.models.k8s.dto.BgpPeerDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BgpPeerConverterTest {

    private final BgpPeerConverter c = new BgpPeerConverter();

    @Test
    void revert_maps_real_schema_keys() {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion("projectcalico.org/v3");
        res.setKind("BGPPeer");
        res.setMetadata(new ObjectMetaBuilder().withName("spine-1")
                .withCreationTimestamp("2026-01-02T00:00:00Z").build());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("peerIP", "10.0.0.1:179"); // 真实 key = peerIP（非 ip）
        spec.put("nodeSelector", "projectcalico.org/node==spine"); // 字符串（非列表）
        spec.put("asNumber", 65001);
        spec.put("keepOriginalNextHop", true);
        spec.put("password", Map.of("secretKeyRef", Map.of("name", "bgp-pass", "namespace", "calico-system")));
        spec.put("sourceAddress", "UseNodeIP");
        spec.put("maxRestartTime", "120s");
        spec.put("ttlSecurity", 2);
        spec.put("filters", List.of("filter-a", "filter-b"));

        res.setAdditionalProperty("spec", spec);
        BgpPeerDTO dto = c.revert(res);
        assertThat(dto.getName()).isEqualTo("spine-1");
        assertThat(dto.getCreationTime()).isEqualTo("2026-01-02T00:00:00Z");
        assertThat(dto.getPeerIp()).isEqualTo("10.0.0.1:179");
        assertThat(dto.getNodeSelector()).isEqualTo("projectcalico.org/node==spine");
        assertThat(dto.getAsNumber()).isEqualTo("65001");
        assertThat(dto.getKeepOriginalNextHop()).isTrue();
        assertThat(dto.getPassword()).extracting(p -> p.getName(), p -> p.getNamespace())
                .containsExactly("bgp-pass", "calico-system");
        assertThat(dto.getSourceAddress()).isEqualTo("UseNodeIP");
        assertThat(dto.getMaxRestartTime()).isEqualTo("120s");
        assertThat(dto.getTtlSecurity()).isEqualTo(2);
        assertThat(dto.getFilters()).containsExactly("filter-a", "filter-b");
        // master 新增字段缺席 → null
        assertThat(dto.getLocalAsNumber()).isNull();
        assertThat(dto.getNextHopMode()).isNull();
    }

    @Test
    void revert_null_resource_returns_empty_dto() {
        BgpPeerDTO dto = c.revert(null);
        assertThat(dto.getName()).isNull();
        assertThat(dto.getPeerIp()).isNull();
        assertThat(dto.getPassword()).isNull();
    }

}
