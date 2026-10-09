package com.coding.k8score.converter.impl.gateway;

import com.coding.common.models.k8s.dto.GatewayDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayConverterTest {

    private final GatewayConverter c = new GatewayConverter();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> specOf(GenericKubernetesResource res) {
        return (Map<String, Object>) res.getAdditionalProperties().get("spec");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listenersOf(Map<String, Object> spec) {
        return (List<Map<String, Object>>) spec.get("listeners");
    }

    private static GatewayDTO.Listener listener(String name, int port, String protocol) {
        GatewayDTO.Listener l = new GatewayDTO.Listener();
        l.setName(name);
        l.setPort(port);
        l.setProtocol(protocol);
        return l;
    }

    private GenericKubernetesResource live(Map<String, Object> spec) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setMetadata(new ObjectMetaBuilder().withName("gw").withNamespace("team-a").build());
        res.setAdditionalProperty("spec", spec);
        return res;
    }

    @Test
    @SuppressWarnings("unchecked")
    void convert_emits_namespaced_spec_with_listener_core_fields() {
        GatewayDTO dto = new GatewayDTO();
        dto.setName("gw");
        dto.setNamespace("team-a");
        dto.setGatewayClassName("istio");
        dto.setListeners(List.of(listener("http", 80, "HTTP")));

        GenericKubernetesResource res = c.convert(dto);

        assertThat(res.getApiVersion()).isEqualTo("gateway.networking.k8s.io/v1");
        assertThat(res.getKind()).isEqualTo("Gateway");
        assertThat(res.getMetadata().getNamespace()).isEqualTo("team-a");
        Map<String, Object> spec = specOf(res);
        assertThat(spec).containsEntry("gatewayClassName", "istio");
        List<Map<String, Object>> listeners = listenersOf(spec);
        assertThat(listeners).hasSize(1);
        assertThat(listeners.get(0)).containsEntry("name", "http")
                .containsEntry("port", 80)
                .containsEntry("protocol", "HTTP")
                .doesNotContainKeys("hostname", "tls", "allowedRoutes");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convert_emits_tls_and_allowedRoutes_in_crd_shape() {
        GatewayDTO.Listener l = listener("https", 443, "HTTPS");
        l.setHostname("*.example.com");
        GatewayDTO.ListenerTls tls = new GatewayDTO.ListenerTls();
        tls.setMode("Terminate");
        GatewayDTO.CertificateRef ref = new GatewayDTO.CertificateRef();
        ref.setName("wildcard-cert");
        tls.setCertificateRefs(List.of(ref));
        l.setTls(tls);
        GatewayDTO.AllowedRoutes ar = new GatewayDTO.AllowedRoutes();
        GatewayDTO.RouteNamespaces ns = new GatewayDTO.RouteNamespaces();
        ns.setFrom("Selector");
        GatewayDTO.RouteGroupKind kind = new GatewayDTO.RouteGroupKind();
        kind.setKind("HTTPRoute");
        ar.setNamespaces(ns);
        ar.setKinds(List.of(kind));
        l.setAllowedRoutes(ar);

        GatewayDTO dto = new GatewayDTO();
        dto.setName("gw");
        dto.setNamespace("team-a");
        dto.setGatewayClassName("istio");
        dto.setListeners(List.of(l));

        Map<String, Object> listener = listenersOf(specOf(c.convert(dto))).get(0);

        assertThat(listener).containsEntry("hostname", "*.example.com");
        Map<String, Object> tlsMap = (Map<String, Object>) listener.get("tls");
        assertThat(tlsMap).containsEntry("mode", "Terminate");
        List<Map<String, Object>> refs = (List<Map<String, Object>>) tlsMap.get("certificateRefs");
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0)).containsEntry("name", "wildcard-cert").doesNotContainKey("kind");

        // allowedRoutes 的真实字段名是 namespaces{from,selector}，不是 namespacesFrom/namespaceSelector
        Map<String, Object> arMap = (Map<String, Object>) listener.get("allowedRoutes");
        assertThat(arMap).doesNotContainKeys("namespacesFrom", "namespaceSelector");
        Map<String, Object> nsMap = (Map<String, Object>) arMap.get("namespaces");
        assertThat(nsMap).containsEntry("from", "Selector");
        List<Map<String, Object>> kinds = (List<Map<String, Object>>) arMap.get("kinds");
        assertThat(kinds).hasSize(1);
        assertThat(kinds.get(0)).containsEntry("kind", "HTTPRoute").doesNotContainKey("group");
    }

    @Test
    void revert_reads_listeners_and_status() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("gatewayClassName", "istio");
        spec.put("listeners", List.of(Map.of(
                "name", "https", "port", 443, "protocol", "HTTPS",
                "tls", Map.of("mode", "Terminate", "certificateRefs", List.of(Map.of("name", "cert"))),
                "allowedRoutes", Map.of("namespaces", Map.of("from", "Same")))));
        GenericKubernetesResource res = live(spec);
        res.setAdditionalProperty("status", Map.of(
                "conditions", List.of(Map.of("type", "Programmed", "status", "True")),
                "listeners", List.of(Map.of("name", "https", "attachedRoutes", 3))));

        GatewayDTO dto = c.revert(res);

        assertThat(dto.getName()).isEqualTo("gw");
        assertThat(dto.getNamespace()).isEqualTo("team-a");
        assertThat(dto.getGatewayClassName()).isEqualTo("istio");
        assertThat(dto.getListeners()).hasSize(1);
        GatewayDTO.Listener l = dto.getListeners().get(0);
        assertThat(l.getName()).isEqualTo("https");
        assertThat(l.getProtocol()).isEqualTo("HTTPS");
        assertThat(l.getTls().getMode()).isEqualTo("Terminate");
        assertThat(l.getTls().getCertificateRefs().get(0).getName()).isEqualTo("cert");
        assertThat(l.getAllowedRoutes().getNamespaces().getFrom()).isEqualTo("Same");
        assertThat(dto.getConditions().get(0).getType()).isEqualTo("Programmed");
        assertThat(dto.getListenerStatuses()).hasSize(1);
        assertThat(dto.getListenerStatuses().get(0).getAttachedRoutes()).isEqualTo(3);
    }

    @Test
    void revert_null_resource_returns_empty_dto() {
        GatewayDTO dto = c.revert(null);
        assertThat(dto.getName()).isNull();
        assertThat(dto.getListeners()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertForUpdate_preserves_unmodeled_listener_subfields() {
        // 线上：listener 带未建模的 tls.options / tls.frontendValidation，以及 spec 级未建模键
        Map<String, Object> liveTls = new LinkedHashMap<>();
        liveTls.put("mode", "Terminate");
        liveTls.put("certificateRefs", List.of(Map.of("name", "old-cert")));
        liveTls.put("options", Map.of("cipher-suites", "TLS_AES_128_GCM_SHA256"));
        liveTls.put("frontendValidation", Map.of("caCertificateRefs", List.of(Map.of("name", "ca"))));
        Map<String, Object> liveListener = new LinkedHashMap<>();
        liveListener.put("name", "https");
        liveListener.put("port", 443);
        liveListener.put("protocol", "HTTPS");
        liveListener.put("tls", liveTls);
        Map<String, Object> liveSpec = new LinkedHashMap<>();
        liveSpec.put("gatewayClassName", "istio");
        liveSpec.put("listeners", List.of(liveListener));
        liveSpec.put("allowedListeners", Map.of("namespaces", Map.of("from", "All"))); // 未建模 spec 键

        // 表单：改证书名，不动 options/frontendValidation
        GatewayDTO.ListenerTls dtoTls = new GatewayDTO.ListenerTls();
        dtoTls.setMode("Terminate");
        GatewayDTO.CertificateRef ref = new GatewayDTO.CertificateRef();
        ref.setName("new-cert");
        dtoTls.setCertificateRefs(List.of(ref));
        GatewayDTO.Listener dtoListener = listener("https", 443, "HTTPS");
        dtoListener.setTls(dtoTls);
        GatewayDTO dto = new GatewayDTO();
        dto.setName("gw");
        dto.setNamespace("team-a");
        dto.setGatewayClassName("istio");
        dto.setListeners(List.of(dtoListener));

        Map<String, Object> spec = specOf(c.convertForUpdate(dto, live(liveSpec)));

        // spec 级未建模键保留
        assertThat(spec).containsEntry("allowedListeners", Map.of("namespaces", Map.of("from", "All")));
        Map<String, Object> listener = listenersOf(spec).get(0);
        Map<String, Object> tls = (Map<String, Object>) listener.get("tls");
        assertThat(tls).containsEntry("mode", "Terminate");
        // 未建模子字段原样保留
        assertThat(tls).containsEntry("options", Map.of("cipher-suites", "TLS_AES_128_GCM_SHA256"));
        assertThat(tls).containsKey("frontendValidation");
        // 建模字段被覆盖
        List<Map<String, Object>> refs = (List<Map<String, Object>>) tls.get("certificateRefs");
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0)).containsEntry("name", "new-cert");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertForUpdate_overlays_listeners_by_name_not_by_index() {
        // 线上顺序 https, http；表单顺序 http, https —— 按 name 对齐，不该把 tls 串到 http 上
        Map<String, Object> liveHttps = new LinkedHashMap<>();
        liveHttps.put("name", "https");
        liveHttps.put("port", 443);
        liveHttps.put("protocol", "HTTPS");
        liveHttps.put("tls", new LinkedHashMap<>(Map.of("mode", "Terminate", "options", Map.of("k", "v"))));
        Map<String, Object> liveHttp = new LinkedHashMap<>();
        liveHttp.put("name", "http");
        liveHttp.put("port", 80);
        liveHttp.put("protocol", "HTTP");
        Map<String, Object> liveSpec = new LinkedHashMap<>();
        liveSpec.put("gatewayClassName", "istio");
        liveSpec.put("listeners", List.of(liveHttps, liveHttp));

        GatewayDTO.ListenerTls dtoTls = new GatewayDTO.ListenerTls();
        dtoTls.setMode("Terminate");
        GatewayDTO.Listener dtoHttps = listener("https", 443, "HTTPS");
        dtoHttps.setTls(dtoTls);
        GatewayDTO dto = new GatewayDTO();
        dto.setName("gw");
        dto.setNamespace("team-a");
        dto.setGatewayClassName("istio");
        dto.setListeners(List.of(listener("http", 80, "HTTP"), dtoHttps));

        List<Map<String, Object>> listeners = listenersOf(specOf(c.convertForUpdate(dto, live(liveSpec))));

        assertThat(listeners).extracting(l -> l.get("name")).containsExactly("http", "https");
        // http（第一个）不该被塞上 tls
        assertThat(listeners.get(0)).doesNotContainKey("tls");
        // https 拿到了线上同名的 options
        assertThat((Map<String, Object>) listeners.get(1).get("tls")).containsKey("options");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertForUpdate_rename_falls_back_to_index_and_keeps_unmodeled_fields() {
        Map<String, Object> liveListener = new LinkedHashMap<>();
        liveListener.put("name", "old-name");
        liveListener.put("port", 80);
        liveListener.put("protocol", "HTTP");
        liveListener.put("someExtension", "keep-me"); // 未建模 listener 字段
        Map<String, Object> liveSpec = new LinkedHashMap<>();
        liveSpec.put("gatewayClassName", "istio");
        liveSpec.put("listeners", List.of(liveListener));

        GatewayDTO dto = new GatewayDTO();
        dto.setName("gw");
        dto.setNamespace("team-a");
        dto.setGatewayClassName("istio");
        dto.setListeners(new ArrayList<>(List.of(listener("new-name", 8080, "HTTP"))));

        Map<String, Object> out = listenersOf(specOf(c.convertForUpdate(dto, live(liveSpec)))).get(0);

        assertThat(out).containsEntry("name", "new-name").containsEntry("port", 8080)
                .containsEntry("someExtension", "keep-me");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertForUpdate_dropping_a_listener_removes_it() {
        Map<String, Object> liveHttp = new LinkedHashMap<>(Map.of("name", "http", "port", 80, "protocol", "HTTP"));
        Map<String, Object> liveHttps = new LinkedHashMap<>(Map.of("name", "https", "port", 443, "protocol", "HTTPS"));
        Map<String, Object> liveSpec = new LinkedHashMap<>();
        liveSpec.put("gatewayClassName", "istio");
        liveSpec.put("listeners", List.of(liveHttp, liveHttps));

        GatewayDTO dto = new GatewayDTO();
        dto.setName("gw");
        dto.setNamespace("team-a");
        dto.setGatewayClassName("istio");
        dto.setListeners(List.of(listener("http", 80, "HTTP"))); // 删掉 https

        List<Map<String, Object>> listeners = listenersOf(specOf(c.convertForUpdate(dto, live(liveSpec))));

        assertThat(listeners).hasSize(1);
        assertThat(listeners.get(0)).containsEntry("name", "http");
    }

}
