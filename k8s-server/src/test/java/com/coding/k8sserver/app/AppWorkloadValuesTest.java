package com.coding.k8sserver.app;

import com.coding.common.models.app.AppNodeDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppWorkloadValuesTest {

    private static AppNodeDTO node(Map<String, Object> params) {
        AppNodeDTO n = new AppNodeDTO();
        n.setKey("web");
        n.setKind("SERVICE");
        n.setParams(params);
        return n;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> workload(Map<String, Object> values) {
        return (Map<String, Object>) values.get("workload");
    }

    @Test
    void mapsBasicParamsAndAppliesDefaults() {
        Map<String, Object> v = AppWorkloadValues.build("app_1", "demo", 3,
                node(Map.of("image", "nginx:1.27")));

        assertThat(v).containsEntry("app", Map.of("id", "app_1", "slug", "demo", "revision", 3));
        Map<String, Object> w = workload(v);
        assertThat(w).containsEntry("key", "web").containsEntry("mode", "service")
                .containsEntry("image", "nginx:1.27").containsEntry("replicas", 1);
        Map<String, Object> res = (Map<String, Object>) w.get("resources");
        assertThat(res.get("requests")).isEqualTo(Map.of("cpu", "100m", "memory", "128Mi"));
        assertThat(res.get("limits")).isEqualTo(Map.of("cpu", "1", "memory", "1Gi"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void splitsPlainEnvAndSecretRefs() {
        AppNodeDTO n = node(Map.of(
                "image", "nginx",
                "env", List.of(
                        Map.of("name", "MODE", "value", "prod"),
                        Map.of("name", "DB_PASSWORD", "secret", true, "secretName", "demo-db", "secretKey", "password"))));
        Map<String, Object> w = workload(AppWorkloadValues.build("app_1", "demo", 1, n));

        assertThat((List<Object>) w.get("env")).containsExactly(Map.of("name", "MODE", "value", "prod"));
        assertThat((List<Object>) w.get("secretEnv")).containsExactly(
                Map.of("name", "DB_PASSWORD", "secretName", "demo-db", "secretKey", "password"));
    }

    @Test
    void rejectsSecretRefWithoutSecretName() {
        AppNodeDTO n = node(Map.of(
                "image", "nginx",
                "env", List.of(Map.of("name", "DB_PASSWORD", "secret", true))));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> AppWorkloadValues.build("app_1", "demo", 1, n))
                .hasMessageContaining("没填 Secret 名");
    }

    @Test
    void probeDefaultsOnAndCanBeDisabled() {
        Map<String, Object> on = workload(AppWorkloadValues.build("app_1", "demo", 1,
                node(Map.of("image", "nginx"))));
        assertThat((Map<String, Object>) on.get("probe")).containsEntry("enabled", true);

        Map<String, Object> off = workload(AppWorkloadValues.build("app_1", "demo", 1,
                node(Map.of("image", "nginx", "probeEnabled", false))));
        assertThat((Map<String, Object>) off.get("probe")).containsEntry("enabled", false);
    }

    @Test
    void rejectsMissingImage() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> AppWorkloadValues.build("app_1", "demo", 1, node(Map.of())))
                .hasMessageContaining("请填写镜像");
    }

    @Test
    void workerNeverGetsPersistenceOrService() {
        AppNodeDTO n = new AppNodeDTO();
        n.setKey("jobs");
        n.setKind("WORKER");
        n.setParams(Map.of("image", "busybox", "persistenceEnabled", true, "expose", "nodeport"));

        Map<String, Object> w = workload(AppWorkloadValues.build("app_1", "demo", 1, n));

        assertThat(w).containsEntry("mode", "worker");
        assertThat((Map<String, Object>) w.get("persistence")).containsEntry("enabled", false);
        assertThat((Map<String, Object>) w.get("expose")).containsEntry("type", "none").containsEntry("nodePort", 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nodePortIsPassedOnlyWhenExposedAsNodePort() {
        Map<String, Object> fixed = workload(AppWorkloadValues.build("app_1", "demo", 1,
                node(Map.of("image", "nginx", "port", 80, "expose", "nodeport", "nodePort", 30080))));
        assertThat((Map<String, Object>) fixed.get("expose"))
                .containsEntry("type", "nodeport").containsEntry("nodePort", 30080);

        Map<String, Object> stray = workload(AppWorkloadValues.build("app_1", "demo", 1,
                node(Map.of("image", "nginx", "port", 80, "nodePort", 30080))));
        assertThat((Map<String, Object>) stray.get("expose"))
                .containsEntry("type", "none").containsEntry("nodePort", 0);
    }

    private static com.coding.common.models.app.AppInjectDTO inline(String name, String value) {
        com.coding.common.models.app.AppInjectDTO d = com.coding.common.models.app.AppInjectDTO.value(name, value);
        d.setInline(true);
        return d;
    }

    @Test
    @SuppressWarnings("unchecked")
    void inlineInjectGoesToInlineEnvNotConfigMapEnv() {
        List<com.coding.common.models.app.AppInjectDTO> inject = List.of(
                com.coding.common.models.app.AppInjectDTO.secret("_REF_0", "demo-db", "password"),
                inline("DB_URL", "jdbc://u:$(_REF_0)@h"),
                com.coding.common.models.app.AppInjectDTO.value("PLAIN", "x"));
        Map<String, Object> w = workload(AppWorkloadValues.build("app_1", "demo", 1,
                node(Map.of("image", "nginx")), inject));

        assertThat((List<Object>) w.get("inlineEnv")).containsExactly(
                Map.of("name", "DB_URL", "value", "jdbc://u:$(_REF_0)@h"));
        assertThat((List<Object>) w.get("env")).containsExactly(Map.of("name", "PLAIN", "value", "x"));
        assertThat((List<Object>) w.get("secretEnv")).hasSize(1);
    }

    private static String hash(int revision, String envValue) {
        AppNodeDTO n = node(Map.of("image", "nginx", "env", List.of(Map.of("name", "A", "value", envValue))));
        return (String) workload(AppWorkloadValues.build("app_1", "demo", revision, n)).get("configHash");
    }

    @Test
    void configHashFollowsEnvButNotRevision() {
        assertThat(hash(1, "a")).isNotBlank().isEqualTo(hash(2, "a"));
        assertThat(hash(1, "a")).isNotEqualTo(hash(1, "b"));
    }

    @Test
    void configHashCoversInlineAndSecretRefs() {
        AppNodeDTO n = node(Map.of("image", "nginx"));
        String base = (String) workload(AppWorkloadValues.build("a", "d", 1, n)).get("configHash");
        String withInline = (String) workload(AppWorkloadValues.build("a", "d", 1, n,
                List.of(inline("U", "v")))).get("configHash");
        String withRef = (String) workload(AppWorkloadValues.build("a", "d", 1, n,
                List.of(com.coding.common.models.app.AppInjectDTO.secret("P", "s", "k")))).get("configHash");
        assertThat(List.of(base, withInline, withRef)).doesNotHaveDuplicates();
    }

    private static Map<String, Object> withFiles(List<Map<String, Object>> files) {
        return Map.of("image", "nginx", "configFiles", files);
    }

    @Test
    @SuppressWarnings("unchecked")
    void configFilesBecomeKeyedEntriesForTheFilesConfigMap() {
        Map<String, Object> w = workload(AppWorkloadValues.build("a", "d", 1, node(withFiles(List.of(
                Map.of("path", "/etc/app/app.yaml", "content", "a: 1\n"),
                Map.of("path", "/etc/nginx/conf.d/default.conf", "content", "server {}"))))));

        assertThat((List<Object>) w.get("configFiles")).containsExactly(
                Map.of("key", "file-0", "path", "/etc/app/app.yaml", "content", "a: 1\n"),
                Map.of("key", "file-1", "path", "/etc/nginx/conf.d/default.conf", "content", "server {}"));
        assertThat((List<Object>) workload(AppWorkloadValues.build("a", "d", 1,
                node(Map.of("image", "nginx")))).get("configFiles")).isEmpty();
    }

    @Test
    void configHashCoversConfigFiles() {
        String none = (String) workload(AppWorkloadValues.build("a", "d", 1,
                node(Map.of("image", "nginx")))).get("configHash");
        String v1 = (String) workload(AppWorkloadValues.build("a", "d", 1,
                node(withFiles(List.of(Map.of("path", "/etc/a.conf", "content", "x=1")))))).get("configHash");
        String v2 = (String) workload(AppWorkloadValues.build("a", "d", 1,
                node(withFiles(List.of(Map.of("path", "/etc/a.conf", "content", "x=2")))))).get("configHash");
        String moved = (String) workload(AppWorkloadValues.build("a", "d", 1,
                node(withFiles(List.of(Map.of("path", "/etc/b.conf", "content", "x=1")))))).get("configHash");
        String again = (String) workload(AppWorkloadValues.build("a", "d", 2,
                node(withFiles(List.of(Map.of("path", "/etc/a.conf", "content", "x=1")))))).get("configHash");

        assertThat(List.of(none, v1, v2, moved)).doesNotHaveDuplicates();
        assertThat(again).isEqualTo(v1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void imagePullSecretsPassThrough() {
        Map<String, Object> w = workload(AppWorkloadValues.build("a", "d", 1,
                node(Map.of("image", "nginx", "imagePullSecrets", List.of("harbor-cred")))));
        assertThat((List<Object>) w.get("imagePullSecrets")).containsExactly("harbor-cred");
        assertThat((List<Object>) workload(AppWorkloadValues.build("a", "d", 1,
                node(Map.of("image", "nginx")))).get("imagePullSecrets")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void autoscalingDefaultsTargetCpu60AndNoMemory() {
        Map<String, Object> w = workload(AppWorkloadValues.build("app_1", "demo", 1,
                node(Map.of("image", "nginx", "autoscalingEnabled", true))));
        Map<String, Object> a = (Map<String, Object>) w.get("autoscaling");
        assertThat(a).containsEntry("enabled", true).containsEntry("targetCpu", 60).containsEntry("targetMemory", 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectionParamsPassThrough() {
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("image", "nginx");
        params.put("port", 80);
        params.put("podAnnotations", Map.of("a", "1"));
        params.put("podLabels", Map.of("istio.io/dataplane-mode", "ambient"));
        params.put("serviceLabels", Map.of("istio.io/use-waypoint", "wp"));
        params.put("extraEnv", List.of(Map.of("name", "OTEL_SERVICE_NAME", "value", "demo-web")));
        params.put("logMountDir", "/var/log/nginx");
        Map<String, Object> w = workload(AppWorkloadValues.build("app_1", "demo", 1, node(params)));
        assertThat(w).containsEntry("podAnnotations", Map.of("a", "1"))
                .containsEntry("podLabels", Map.of("istio.io/dataplane-mode", "ambient"))
                .containsEntry("serviceLabels", Map.of("istio.io/use-waypoint", "wp"))
                .containsEntry("extraEnv", List.of(Map.of("name", "OTEL_SERVICE_NAME", "value", "demo-web")))
                .containsEntry("logVolume", Map.of("enabled", true, "mountDir", "/var/log/nginx"));

        Map<String, Object> bare = workload(AppWorkloadValues.build("app_1", "demo", 1, node(Map.of("image", "nginx"))));
        assertThat(bare).containsEntry("podAnnotations", Map.of()).containsEntry("podLabels", Map.of())
                .containsEntry("serviceLabels", Map.of()).containsEntry("extraEnv", List.of())
                .containsEntry("logVolume", Map.of("enabled", false, "mountDir", ""));
    }

    @Test
    void configHashIgnoresInjection() {
        Map<String, Object> base = Map.of("image", "nginx", "env", List.of(Map.of("name", "A", "value", "1")));
        Map<String, Object> injected = new java.util.LinkedHashMap<>(base);
        injected.put("podAnnotations", Map.of("a", "1"));
        injected.put("extraEnv", List.of(Map.of("name", "X", "value", "y")));
        assertThat(workload(AppWorkloadValues.build("app_1", "demo", 1, node(injected))).get("configHash"))
                .isEqualTo(workload(AppWorkloadValues.build("app_1", "demo", 1, node(base))).get("configHash"));
    }
}
