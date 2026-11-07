package com.coding.platformapi.app;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppServiceDesiredTest {

    private static Map<String, Object> with(String name, String value) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("env", List.of(Map.of("name", name, "kind", "LITERAL", "value", value)));
        return d;
    }

    @Test
    void benignNamesThatContainSensitiveWordsDoNotWarn() {
        assertThat(AppServiceDesired.warnings(with("TOKEN_TTL", "3600"))).isEmpty();
        assertThat(AppServiceDesired.warnings(with("MAX_TOKENS", "4096"))).isEmpty();
        assertThat(AppServiceDesired.warnings(with("PASSWORD_MIN_LENGTH", "8"))).isEmpty();
    }

    @Test
    void plainTextCredentialWarns() {
        assertThat(AppServiceDesired.warnings(with("DB_PASSWORD", "hunter2"))).hasSize(1);
        assertThat(AppServiceDesired.warnings(with("DB_PASSWORD", "hunter2${api.host}"))).hasSize(1);
        assertThat(AppServiceDesired.warnings(with("STRIPE_API_KEY", "sk_live_x"))).hasSize(1);
        assertThat(AppServiceDesired.warnings(with("AUTH_TOKEN", "abc"))).hasSize(1);
        assertThat(AppServiceDesired.warnings(with("APP_SECRET", "abc"))).hasSize(1);
    }

    @Test
    void pureReferenceDoesNotWarn() {
        assertThat(AppServiceDesired.warnings(with("DB_PASSWORD", "${db.password}"))).isEmpty();
    }

    @Test
    void underscoreOnlyNamesDoNotThrow() {
        assertThat(AppServiceDesired.warnings(with("_", "x"))).isEmpty();
        assertThat(AppServiceDesired.warnings(with("__", "x"))).isEmpty();
        assertThat(AppServiceDesired.warnings(with(".-", "x"))).isEmpty();
    }

    @Test
    void dottedAndDashedNamesAreSplitForTheSensitiveCheck() {
        assertThat(AppServiceDesired.warnings(with("spring.datasource.password", "hunter2"))).hasSize(1);
        assertThat(AppServiceDesired.warnings(with("auth-token", "abc"))).hasSize(1);
        assertThat(AppServiceDesired.warnings(with("stripe.api.key", "sk"))).hasSize(1);
        assertThat(AppServiceDesired.warnings(with("spring.datasource.url", "jdbc:x"))).isEmpty();
    }

    @Test
    void envNamesFollowTheLegacyKubernetesRule() {
        for (String ok : List.of("spring.datasource.url", "my-var", "_X", ".hidden", "A1_b.c-d")) {
            Map<String, Object> d = base();
            d.put("env", List.of(Map.of("name", ok, "kind", "LITERAL", "value", "v")));
            assertThat(AppServiceDesired.normalize(d)).containsKey("env");
        }
        for (String bad : List.of("1abc", "a b", "a=b", "a:b", "")) {
            Map<String, Object> d = base();
            d.put("env", List.of(Map.of("name", bad, "kind", "LITERAL", "value", "v")));
            rejects(d).hasMessageContaining("环境变量名不合法");
        }
    }
    // ---------------------------------------------------------------- 配置文件

    private static Map<String, Object> base() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("imageRepo", "harbor.local/demo/api");
        d.put("imageTag", "1");
        d.put("port", 8080);
        return d;
    }

    private static Map<String, Object> file(String path, String content) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("path", path);
        f.put("content", content);
        return f;
    }

    private static Map<String, Object> withFiles(Object... files) {
        Map<String, Object> d = base();
        d.put("configFiles", List.of(files));
        return d;
    }

    private static org.assertj.core.api.AbstractThrowableAssert<?, ? extends Throwable> rejects(Map<String, Object> raw) {
        return org.assertj.core.api.Assertions.assertThatThrownBy(() -> AppServiceDesired.normalize(raw))
                .isInstanceOf(com.coding.common.exception.CloudPlatformException.class);
    }

    @Test
    void configFilesSurviveNormalizeAndAreReadBack() {
        Map<String, Object> d = AppServiceDesired.normalize(withFiles(
                file("/etc/app/app.yaml", "db: ${db.host}:${db.port}\n"),
                file("/etc/nginx/nginx.conf", "")));

        assertThat(AppServiceDesired.configFiles(d)).containsExactly(
                Map.of("path", "/etc/app/app.yaml", "content", "db: ${db.host}:${db.port}\n"),
                Map.of("path", "/etc/nginx/nginx.conf", "content", ""));
    }

    @Test
    void emptyConfigFilesAreOmittedSoTheyNeverShowAsAPendingChange() {
        Map<String, Object> d = base();
        d.put("configFiles", List.of());
        assertThat(AppServiceDesired.normalize(d)).doesNotContainKey("configFiles");
        assertThat(AppServiceDesired.normalize(base())).doesNotContainKey("configFiles");
    }

    @Test
    void configFilePathMustBeAbsoluteCleanAndAFile() {
        rejects(withFiles(file("etc/app.yaml", "x"))).hasMessageContaining("绝对路径");
        rejects(withFiles(file("/etc/../root/x", "x"))).hasMessageContaining("..");
        rejects(withFiles(file("/etc/./x", "x")));
        rejects(withFiles(file("/etc//x", "x")));
        rejects(withFiles(file("/etc/app/", "x")));
        rejects(withFiles(file("/", "x")));
        rejects(withFiles(file("/etc/my app.conf", "x")));
        rejects(withFiles(file("", "x")));
    }

    @Test
    void configFilePathsMustNotRepeatOrNest() {
        rejects(withFiles(file("/etc/a.conf", "1"), file("/etc/a.conf", "2"))).hasMessageContaining("重复");
        rejects(withFiles(file("/etc/app", "1"), file("/etc/app/x.conf", "2"))).hasMessageContaining("/etc/app");
    }

    @Test
    void configFileMustNotCollideWithThePersistentVolumeMount() {
        Map<String, Object> d = withFiles(file("/data/app.conf", "x"));
        d.put("persistenceEnabled", true);
        rejects(d).hasMessageContaining("/data");

        Map<String, Object> custom = withFiles(file("/var/lib/app", "x"));
        custom.put("persistenceEnabled", true);
        custom.put("mountPath", "/var/lib/app/data");
        rejects(custom).hasMessageContaining("/var/lib/app/data");

        // 没开持久化（或后台进程）就没有数据卷，不冲突
        assertThat(AppServiceDesired.normalize(withFiles(file("/data/app.conf", "x")))).containsKey("configFiles");
        Map<String, Object> worker = withFiles(file("/data/app.conf", "x"));
        worker.remove("port");
        worker.put("persistenceEnabled", true);
        assertThat(AppServiceDesired.normalize(worker)).containsKey("configFiles");
    }

    @Test
    void configFileContentMustNotReferenceCredentials() {
        rejects(withFiles(file("/etc/app.yaml", "password: ${db.password}")))
                .hasMessageContaining("${db.password}")
                .hasMessageContaining("host、port、address、url");
        rejects(withFiles(file("/etc/app.yaml", "user: ${db.username}")))
                .hasMessageContaining("${db.username}");
        rejects(withFiles(file("/etc/app.yaml", "x: ${db.host")))
                .hasMessageContaining("引用格式");
    }

    @Test
    void referencesIncludeConfigFileContent() {
        Map<String, Object> d = AppServiceDesired.normalize(withFiles(
                file("/etc/app.yaml", "cache: ${cache.address}\nupstream: ${api.url}")));
        assertThat(AppServiceDesired.references(d)).containsExactlyInAnyOrder("cache", "api");
    }

    // ------------------------------------------------------------------ 对外暴露

    private static Map<String, Object> svc(Object... kv) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("imageRepo", "nginx");
        d.put("imageTag", "1");
        d.put("port", 80);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            d.put((String) kv[i], kv[i + 1]);
        }
        return d;
    }

    @Test
    void noneAndBlankExposeAreNotStored() {
        assertThat(AppServiceDesired.normalize(svc("expose", "none"))).doesNotContainKey("expose");
        assertThat(AppServiceDesired.normalize(svc("expose", ""))).doesNotContainKey("expose");
        assertThat(AppServiceDesired.normalize(svc())).doesNotContainKey("expose");
    }

    @Test
    void nodePortIsNormalizedAndOptional() {
        Map<String, Object> auto = AppServiceDesired.normalize(svc("expose", "NodePort"));
        assertThat(auto).containsEntry("expose", "nodeport").doesNotContainKey("nodePort");
        assertThat(AppServiceDesired.nodePort(auto)).isNull();

        Map<String, Object> fixed = AppServiceDesired.normalize(svc("expose", "nodeport", "nodePort", "30080"));
        assertThat(fixed).containsEntry("nodePort", 30080);
        assertThat(AppServiceDesired.nodePort(fixed)).isEqualTo(30080);
    }

    @Test
    void nodePortOutOfRangeOrWithoutExposeIsRejected() {
        assertThatThrownBy(() -> AppServiceDesired.normalize(svc("expose", "nodeport", "nodePort", 8080)))
                .hasMessageContaining("30000-32767");
        assertThatThrownBy(() -> AppServiceDesired.normalize(svc("nodePort", 30080)))
                .hasMessageContaining("只有选择 NodePort");
    }

    @Test
    void unsupportedExposeAndWorkerExposeAreRejected() {
        assertThatThrownBy(() -> AppServiceDesired.normalize(svc("expose", "httproute")))
                .hasMessageContaining("不支持的暴露方式");
        Map<String, Object> worker = svc("expose", "nodeport");
        worker.remove("port");
        assertThatThrownBy(() -> AppServiceDesired.normalize(worker)).hasMessageContaining("后台进程");
    }

    // ---------- otel / mesh / HPA（2026-11-07） ----------

    @Test
    void otelNormalizesAndRejectsUnknownField() {
        assertThatThrownBy(() -> AppServiceDesired.normalize(svc("otel",
                Map.of("language", "java", "tracing", true, "foo", 1))))
                .hasMessageContaining("foo");
        Map<String, Object> ok = AppServiceDesired.normalize(svc("otel",
                Map.of("language", "java", "tracing", true, "sampling", 20)));
        assertThat(ok.get("otel")).isEqualTo(Map.of("language", "java", "tracing", true, "logs", false,
                "logMode", "app", "sampling", 20));
        assertThat(AppServiceDesired.normalize(svc("otel", Map.of("tracing", false, "logs", false))))
                .doesNotContainKey("otel");
    }

    @Test
    void otelAgentRequiresLanguage() {
        assertThatThrownBy(() -> AppServiceDesired.normalize(svc("otel", Map.of("tracing", true))))
                .hasMessageContaining("语言");
        Map<String, Object> file = AppServiceDesired.normalize(svc("otel",
                Map.of("logs", true, "logMode", "file", "logFile", "/var/log/nginx/access.log")));
        assertThat(file).containsKey("otel");
    }

    @Test
    void otelFileModeRequiresFilePath() {
        assertThatThrownBy(() -> AppServiceDesired.normalize(svc("otel",
                Map.of("logs", true, "logMode", "file", "logFile", "/var/log/nginx/"))))
                .hasMessageContaining("日志文件");
        assertThatThrownBy(() -> AppServiceDesired.normalize(svc("otel",
                Map.of("logs", true, "logMode", "file", "logFile", "access.log"))))
                .hasMessageContaining("日志文件");
        assertThatThrownBy(() -> AppServiceDesired.normalize(svc("otel",
                Map.of("tracing", true, "language", "java", "sampling", 0))))
                .hasMessageContaining("采样率");
    }

    @Test
    void otelReservedEnvRejected() {
        assertThatThrownBy(() -> AppServiceDesired.normalize(svc(
                "env", List.of(Map.of("name", "OTEL_SERVICE_NAME", "value", "x")),
                "otel", Map.of("language", "java", "tracing", true))))
                .hasMessageContaining("OTEL_SERVICE_NAME");
        // 没接入时用户可以自己写
        assertThat(AppServiceDesired.normalize(svc(
                "env", List.of(Map.of("name", "OTEL_SERVICE_NAME", "value", "x"))))).isNotNull();
    }

    @Test
    void meshNormalizes() {
        assertThatThrownBy(() -> AppServiceDesired.normalize(svc("mesh", Map.of("dataplaneMode", "foo"))))
                .hasMessageContaining("foo");
        assertThat(AppServiceDesired.normalize(svc("mesh", Map.of()))).doesNotContainKey("mesh");
        assertThat(AppServiceDesired.normalize(svc("mesh", Map.of("dataplaneMode", "ambient", "useWaypoint", "waypoint")))
                .get("mesh")).isEqualTo(Map.of("dataplaneMode", "ambient", "useWaypoint", "waypoint"));
        Map<String, Object> worker = svc("mesh", Map.of("useWaypoint", "waypoint"));
        worker.remove("port");
        assertThatThrownBy(() -> AppServiceDesired.normalize(worker)).hasMessageContaining("waypoint");
    }

    @Test
    void targetMemoryIsKept() {
        assertThat(AppServiceDesired.normalize(svc("autoscalingEnabled", true, "targetMemory", 70)))
                .containsEntry("targetMemory", 70);
    }

    @Test
    void validateHpaChangeMinMax() {
        Map<String, Object> after = svc("autoscalingEnabled", true, "minReplicas", 5, "maxReplicas", 2);
        assertThatThrownBy(() -> AppServiceDesired.validateHpaChange(null, after))
                .hasMessageContaining("副本");
    }

    @Test
    void unrelatedSaveSkipsHpaChecks() {
        Map<String, Object> before = svc("autoscalingEnabled", true, "minReplicas", 5, "maxReplicas", 2);
        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("imageTag", "1.28");
        AppServiceDesired.validateHpaChange(before, after);
    }
}
