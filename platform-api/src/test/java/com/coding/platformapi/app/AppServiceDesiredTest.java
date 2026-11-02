package com.coding.platformapi.app;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
}
