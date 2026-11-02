package com.coding.k8sserver.app;

import com.coding.common.models.app.AppInitRequestDTO;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 初始化 Job 的模板：payload 进 ConfigMap，Job 里只引用它（清单里不出现 SQL 正文）。 */
class AppInitScriptsTest {

    private static AppInitRequestDTO req(String code, String payload) {
        AppInitRequestDTO r = new AppInitRequestDTO();
        r.setClusterId("c1");
        r.setNamespace("demo");
        r.setJobName("demo-db-init-3");
        r.setCode(code);
        r.setSecretName("demo-mysql-secrets");
        r.setPayload(payload);
        r.setEnv(Map.of("HOST", "demo-mysql.demo.svc", "PORT", "3306"));
        return r;
    }

    @Test
    void mysqlJobReferencesConfigMapAndReadsPasswordFromSecret() {
        String job = AppInitScripts.jobFor(req("mysql", "CREATE TABLE IF NOT EXISTS t(id int);"));

        assertThat(job).contains("kind: Job").contains("image: mysql:8.4");
        assertThat(job).contains("secretKeyRef").contains("demo-mysql-secrets");
        assertThat(job).doesNotContain("CREATE TABLE");            // SQL 正文不在 Job 里
        assertThat(job).contains("demo-db-init-3-sql");            // 只引用 ConfigMap
        assertThat(job).contains("backoffLimit: 0");               // 失败交给平台重试
    }

    @Test
    void payloadGoesIntoConfigMap() {
        String cm = AppInitScripts.configMapFor(req("mysql", "CREATE TABLE t(id int);"));

        assertThat(cm).contains("kind: ConfigMap").contains("demo-db-init-3-sql");
        assertThat(cm).contains("CREATE TABLE t(id int);");
    }

    @Test
    void elasticsearchUsesCurl() {
        String job = AppInitScripts.jobFor(req("elasticsearch", "{\"path\":\"/demo\"}"));
        assertThat(job).contains("curlimages/curl");
        assertThat(job).contains("demo-db-init-3-sql");
    }

    private static AppInitRequestDTO esReq(String url) {
        AppInitRequestDTO r = req("elasticsearch", "{\"settings\":{}}");
        r.setEnv(Map.of("HOST", "es.demo.svc", "PORT", "9200", "URL", url, "INDEX_PATH", "/orders"));
        return r;
    }

    @Test
    void elasticsearchScriptUsesIndexPathFailsOnHttpErrorsAndTrustsSelfSignedOnlyForHttps() {
        for (String url : new String[]{"https://es.demo.svc:9200", "http://es.demo.svc:9200"}) {
            String job = AppInitScripts.jobFor(esReq(url));
            // 不再有名为 PATH 的 env（它会覆盖可执行文件搜索路径）
            assertThat(job).doesNotContain("name: PATH").doesNotContain("$PATH");
            assertThat(job).contains("name: INDEX_PATH").contains("\"/orders\"").contains("$INDEX_PATH");
            assertThat(job).contains("curl -fsS");
            // -k 由 URL 协议决定，脚本里不写死协议
            assertThat(job).contains("https://*) K=").contains("$K");
            assertThat(job).contains("value: \"" + url + "\"");
        }
    }

    @Test
    void otherScriptsFailOnErrorsAndPassCredentialsInWaysTheClientsUnderstand() {
        assertThat(AppInitScripts.jobFor(req("mysql", "x"))).contains("MYSQL_PWD=");
        assertThat(AppInitScripts.jobFor(req("postgresql", "x"))).contains("ON_ERROR_STOP=1").contains("PGPASSWORD=");
        // redis-cli 喂标准输入时报错也退 0：必须扫输出
        assertThat(AppInitScripts.jobFor(req("redis", "x"))).contains("grep -qE").contains("NOAUTH");
        // kafka 逐条记失败，不被循环最后一条的退出码覆盖
        assertThat(AppInitScripts.jobFor(req("kafka", "x"))).contains("rc=0;").contains("|| rc=1;").contains("exit $rc");
    }

    @Test
    void kafkaCreatesTopics() {
        String job = AppInitScripts.jobFor(req("kafka", "orders:3:1"));
        assertThat(job).contains("kafka-topics.sh").contains("--create").contains("if-not-exists");
        // topic 列表同样是「正文」，进 ConfigMap 而不是 Job 清单
        assertThat(job).doesNotContain("orders:3:1").contains("demo-db-init-3-sql");
        assertThat(AppInitScripts.configMapFor(req("kafka", "orders:3:1"))).contains("orders:3:1");
    }

    @Test
    void unsupportedCodesAreReported() {
        assertThat(AppInitScripts.supports("nacos")).isFalse();
        assertThat(AppInitScripts.supports("rabbitmq")).isFalse();
        assertThat(AppInitScripts.supports("mysql")).isTrue();
        assertThat(AppInitScripts.supports("postgresql")).isTrue();
        assertThat(AppInitScripts.supports("redis")).isTrue();
        assertThat(AppInitScripts.supports("elasticsearch")).isTrue();
        assertThat(AppInitScripts.supports("kafka")).isTrue();
    }
}
