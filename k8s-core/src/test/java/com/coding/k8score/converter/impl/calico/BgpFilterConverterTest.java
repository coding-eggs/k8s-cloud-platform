package com.coding.k8score.converter.impl.calico;

import com.coding.common.models.k8s.dto.BgpFilterDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BgpFilterConverterTest {

    private final BgpFilterConverter c = new BgpFilterConverter();

    @Test
    void revert_parses_four_rule_lists_with_flattened_fields() {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion("projectcalico.org/v3");
        res.setKind("BGPFilter");
        res.setMetadata(new ObjectMetaBuilder().withName("filter-a")
                .withCreationTimestamp("2026-01-03T00:00:00Z").build());

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("cidr", "10.48.0.0/16");
        rule.put("prefixLength", Map.of("min", 16, "max", 24));
        rule.put("source", "RemotePeers");
        rule.put("interface", "cali+"); // 真实 JSON key = "interface"（Java 保留字 → iface）
        rule.put("matchOperator", "In");
        rule.put("peerType", "eBGP");
        rule.put("communities", Map.of("values", List.of("64512:65535")));
        rule.put("asPathPrefix", List.of(65000, "AS65001")); // numorstring 混合态
        rule.put("priority", 1024);
        rule.put("action", "Accept");
        rule.put("operations", List.of(
                Map.of("addCommunity", Map.of("value", "64512:100")),
                Map.of("prependASPath", Map.of("prefix", List.of(65000, 65001))),
                Map.of("setPriority", Map.of("value", 2048))));

        Map<String, Object> rejectRule = new LinkedHashMap<>();
        rejectRule.put("cidr", "192.168.0.0/16");
        rejectRule.put("matchOperator", "Equal");
        rejectRule.put("action", "Reject");

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("exportV4", List.of(rule));
        spec.put("importV4", List.of(rejectRule));
        // exportV6/importV6 缺席 → null
        res.setAdditionalProperty("spec", spec);

        BgpFilterDTO dto = c.revert(res);
        assertThat(dto.getName()).isEqualTo("filter-a");
        assertThat(dto.getCreationTime()).isEqualTo("2026-01-03T00:00:00Z");
        assertThat(dto.getExportV4()).hasSize(1);
        var r = dto.getExportV4().get(0);
        assertThat(r.getCidr()).isEqualTo("10.48.0.0/16");
        assertThat(r.getPrefixLengthMin()).isEqualTo(16);
        assertThat(r.getPrefixLengthMax()).isEqualTo(24);
        assertThat(r.getSource()).isEqualTo("RemotePeers");
        assertThat(r.getIface()).isEqualTo("cali+");
        assertThat(r.getMatchOperator()).isEqualTo("In");
        assertThat(r.getPeerType()).isEqualTo("eBGP");
        assertThat(r.getCommunityValues()).containsExactly("64512:65535");
        assertThat(r.getAsPathPrefix()).containsExactly("65000", "AS65001");
        assertThat(r.getPriority()).isEqualTo(1024);
        assertThat(r.getAction()).isEqualTo("Accept");
        assertThat(r.getOperations()).hasSize(3);
        assertThat(r.getOperations().get(0).getAddCommunity()).isEqualTo("64512:100");
        assertThat(r.getOperations().get(1).getPrependAsPath()).containsExactly("65000", "65001");
        assertThat(r.getOperations().get(2).getSetPriority()).isEqualTo(2048);

        assertThat(dto.getImportV4()).hasSize(1);
        var rr = dto.getImportV4().get(0);
        assertThat(rr.getAction()).isEqualTo("Reject");
        assertThat(rr.getOperations()).isNull(); // 缺席 → null
        assertThat(dto.getExportV6()).isNull();
        assertThat(dto.getImportV6()).isNull();
    }

    @Test
    void revert_null_resource_returns_empty_dto() {
        BgpFilterDTO dto = c.revert(null);
        assertThat(dto.getName()).isNull();
        assertThat(dto.getExportV4()).isNull();
    }

}
