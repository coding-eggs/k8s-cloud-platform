package com.coding.k8sserver.app;

import com.coding.common.models.app.AppInjectDTO;
import com.coding.common.models.app.AppNodeDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 注入连接信息：普通值进 ConfigMap，凭据只走 secretKeyRef。 */
class AppInjectionValuesTest {

    private static AppNodeDTO node() {
        AppNodeDTO n = new AppNodeDTO();
        n.setKey("backend");
        n.setKind("SERVICE");
        n.setParams(Map.of("image", "demo/backend:1"));
        return n;
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectedValuesSplitIntoEnvAndSecretRefs() {
        Map<String, Object> values = AppWorkloadValues.build("app_1", "demo", 1, node(),
                List.of(AppInjectDTO.value("MAIN_DB_MYSQL_HOST", "demo-mysql.demo.svc"),
                        AppInjectDTO.secret("MAIN_DB_MYSQL_PASSWORD", "demo-mysql-secrets", "root")));

        Map<String, Object> w = (Map<String, Object>) values.get("workload");
        assertThat((List<Object>) w.get("env"))
                .contains(Map.of("name", "MAIN_DB_MYSQL_HOST", "value", "demo-mysql.demo.svc"));
        assertThat((List<Object>) w.get("secretEnv"))
                .contains(Map.of("name", "MAIN_DB_MYSQL_PASSWORD",
                        "secretName", "demo-mysql-secrets", "secretKey", "root"));
        // 任何地方都不能出现明文口令
        assertThat(values.toString()).doesNotContain("password=");
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectionIsMergedWithUserDefinedEnv() {
        AppNodeDTO n = new AppNodeDTO();
        n.setKey("backend");
        n.setKind("SERVICE");
        n.setParams(Map.of("image", "demo/backend:1",
                "env", List.of(Map.of("name", "MODE", "value", "prod"))));

        Map<String, Object> w = (Map<String, Object>) AppWorkloadValues.build("app_1", "demo", 1, n,
                List.of(AppInjectDTO.value("DB_REDIS_HOST", "redis.demo.svc"))).get("workload");

        assertThat((List<Object>) w.get("env")).hasSize(2);
    }
}
