package com.coding.k8score.converter.impl.calico;

import com.coding.common.models.k8s.dto.BgpConfigurationDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BgpConfigurationConverterTest {

    private final BgpConfigurationConverter c = new BgpConfigurationConverter();

    private GenericKubernetesResource resWithSpec(Map<String, Object> spec) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion("projectcalico.org/v3");
        res.setKind("BGPConfiguration");
        res.setMetadata(new ObjectMetaBuilder().withName("default")
                .withLabels(Map.of("team", "net"))
                .withCreationTimestamp("2026-01-01T00:00:00Z").build());
        if (spec != null) {
            res.setAdditionalProperty("spec", spec);
        }
        return res;
    }

    @Test
    void revert_reads_full_spec_with_nested_lists_and_numorstring() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("logSeverityScreen", "Info");
        spec.put("nodeToNodeMeshEnabled", true);
        spec.put("asNumber", 64512); // numorstring 数字态
        spec.put("listenPort", 179);
        spec.put("serviceClusterIPs", List.of(Map.of("cidr", "10.96.0.0/12")));
        spec.put("communities", List.of(Map.of("name", "calico", "value", "64512:65535")));
        spec.put("prefixAdvertisements", List.of(Map.of("cidr", "10.48.0.0/16", "communities", List.of("calico"))));
        spec.put("nodeMeshPassword", Map.of("secretKeyRef",
                Map.of("name", "bgp-pass", "namespace", "calico-system", "key", "pass")));
        spec.put("bindMode", "NodeIP");
        spec.put("ignoredInterfaces", List.of("docker0"));

        BgpConfigurationDTO dto = c.revert(resWithSpec(spec));
        assertThat(dto.getName()).isEqualTo("default");
        assertThat(dto.getCreationTime()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(dto.getLogSeverityScreen()).isEqualTo("Info");
        assertThat(dto.getNodeToNodeMeshEnabled()).isTrue();
        assertThat(dto.getAsNumber()).isEqualTo("64512"); // 数字态归一为 String
        assertThat(dto.getListenPort()).isEqualTo(179);
        assertThat(dto.getServiceClusterIPs()).hasSize(1);
        assertThat(dto.getServiceClusterIPs().get(0).getCidr()).isEqualTo("10.96.0.0/12");
        assertThat(dto.getCommunities()).hasSize(1);
        var comm = dto.getCommunities().get(0);
        assertThat(comm.getName()).isEqualTo("calico");
        assertThat(comm.getValue()).isEqualTo("64512:65535");
        assertThat(dto.getPrefixAdvertisements()).hasSize(1);
        var pa = dto.getPrefixAdvertisements().get(0);
        assertThat(pa.getCidr()).isEqualTo("10.48.0.0/16");
        assertThat(pa.getCommunities()).containsExactly("calico");
        assertThat(dto.getNodeMeshPassword())
                .extracting(p -> p.getName(), p -> p.getNamespace(), p -> p.getKey())
                .containsExactly("bgp-pass", "calico-system", "pass");
        assertThat(dto.getBindMode()).isEqualTo("NodeIP");
        assertThat(dto.getIgnoredInterfaces()).containsExactly("docker0");
    }

    @Test
    void revert_asnumber_string_form_kept_verbatim() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("asNumber", "AS65000"); // numorstring 字符串态
        assertThat(c.revert(resWithSpec(spec)).getAsNumber()).isEqualTo("AS65000");
    }

    @Test
    void revert_null_resource_returns_empty_dto() {
        BgpConfigurationDTO dto = c.revert(null);
        assertThat(dto.getName()).isNull();
        assertThat(dto.getAsNumber()).isNull();
        assertThat(dto.getCommunities()).isNull();
    }

    @Test
    void revert_absent_spec_keeps_metadata_only() {
        BgpConfigurationDTO dto = c.revert(resWithSpec(null));
        assertThat(dto.getName()).isEqualTo("default");
        assertThat(dto.getLogSeverityScreen()).isNull();
        assertThat(dto.getServiceLoadBalancerAggregation()).isNull(); // 新版字段缺席 → null
    }

}
