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
        assertThat((Map<String, Object>) w.get("expose")).containsEntry("type", "none");
    }
}
