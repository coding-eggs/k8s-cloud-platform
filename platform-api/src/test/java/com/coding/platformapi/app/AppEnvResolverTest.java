package com.coding.platformapi.app;

import com.coding.common.models.app.AppInjectDTO;
import com.coding.common.models.middleware.MiddlewareConnectionDTO.ConnInfo;
import com.coding.platformapi.app.AppEnvResolver.AppEnvRow;
import com.coding.platformapi.app.AppEnvResolver.Result;
import com.coding.platformapi.app.AppEnvResolver.ServiceAddr;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppEnvResolverTest {

    private static final ConnInfo MYSQL = ConnInfo
            .of("mysql", "db.demo.svc.cluster.local", 3306).secret("db-sec", "user", "pass").build();
    private static final ConnInfo REDIS = ConnInfo
            .of("redis", "r.demo.svc", 6379).secret("r-sec", null, "pw").build();
    private static final Map<String, ConnInfo> DEPS = Map.of("db", MYSQL, "cache", REDIS);

    private static AppEnvRow lit(String name, String value) {
        return new AppEnvRow(name, "LITERAL", value, null, null);
    }

    private static Result run(List<AppEnvRow> rows, Map<String, ConnInfo> deps, Map<String, ServiceAddr> svcs) {
        return AppEnvResolver.resolve(rows, deps, svcs);
    }

    @Test
    void singleHostRef() {
        Result r = run(List.of(lit("DB_HOST", "${db.host}")), DEPS, Map.of());
        assertThat(r.problems()).isEmpty();
        assertThat(r.env()).hasSize(1);
        assertThat(r.env().get(0).getValue()).isEqualTo("db.demo.svc.cluster.local");
        assertThat(r.env().get(0).isInline()).isFalse();
    }

    @Test
    void passwordRef() {
        Result r = run(List.of(lit("DB_PW", "${db.password}")), DEPS, Map.of());
        assertThat(r.problems()).isEmpty();
        AppInjectDTO d = r.env().get(0);
        assertThat(d.getName()).isEqualTo("DB_PW");
        assertThat(d.getValue()).isNull();
        assertThat(d.getSecretName()).isEqualTo("db-sec");
        assertThat(d.getSecretKey()).isEqualTo("pass");
    }

    @Test
    void templateWithSecret() {
        Result r = run(List.of(lit("DSN", "mysql://${db.username}:${db.password}@${db.host}/x")), DEPS, Map.of());
        assertThat(r.problems()).isEmpty();
        assertThat(r.env()).hasSize(3);
        assertThat(r.env().get(0).getName()).isEqualTo("_REF_0");
        assertThat(r.env().get(0).getSecretKey()).isEqualTo("user");
        assertThat(r.env().get(1).getName()).isEqualTo("_REF_1");
        assertThat(r.env().get(1).getSecretKey()).isEqualTo("pass");
        AppInjectDTO dsn = r.env().get(2);
        assertThat(dsn.isInline()).isTrue();
        assertThat(dsn.getValue()).isEqualTo("mysql://$(_REF_0):$(_REF_1)@db.demo.svc.cluster.local/x");
        assertThat(r.env()).noneMatch(d -> d.getValue() != null && d.getValue().contains("pass") && !d.getValue().contains("$("));
    }

    @Test
    void templateWithoutSecretIsPlainValue() {
        Result r = run(List.of(lit("URL", "jdbc:mysql://${db.address}/x")), DEPS, Map.of());
        assertThat(r.env()).hasSize(1);
        assertThat(r.env().get(0).getValue()).isEqualTo("jdbc:mysql://db.demo.svc.cluster.local:3306/x");
        assertThat(r.env().get(0).isInline()).isFalse();
    }

    @Test
    void unknownDep() {
        Result r = run(List.of(lit("X", "${nope.host}")), DEPS, Map.of());
        assertThat(r.problems()).hasSize(1);
        assertThat(r.problems().get(0).getSeverity()).isEqualTo("ERROR");
        assertThat(r.problems().get(0).getMessage()).contains("nope");
        assertThat(r.env()).isEmpty();
    }

    @Test
    void unknownField() {
        Result r = run(List.of(lit("X", "${db.bogus}")), DEPS, Map.of());
        assertThat(r.problems()).hasSize(1);
        assertThat(r.problems().get(0).getMessage()).contains("db").contains("bogus");
    }

    @Test
    void fieldWithoutValue() {
        Result r = run(List.of(lit("X", "${cache.username}")), DEPS, Map.of());
        assertThat(r.problems()).hasSize(1);
        assertThat(r.problems().get(0).getMessage()).contains("cache").contains("username");
    }

    @Test
    void duplicateName() {
        Result r = run(List.of(lit("A", "1"), lit("A", "2")), DEPS, Map.of());
        assertThat(r.problems()).hasSize(1);
        assertThat(r.problems().get(0).getMessage()).contains("A");
    }

    @Test
    void reservedPrefix() {
        Result r = run(List.of(lit("_REF_x", "1")), DEPS, Map.of());
        assertThat(r.problems()).hasSize(1);
        assertThat(r.problems().get(0).getSeverity()).isEqualTo("ERROR");
    }

    @Test
    void esTlsUrl() {
        ConnInfo es = ConnInfo.of("elasticsearch", "https://es.demo.svc", 9200).build();
        ConnInfo plain = ConnInfo.of("elasticsearch", "es.demo.svc", 9200).build();
        Result r = run(List.of(lit("A", "${es.url}"), lit("B", "${plain.url}")),
                Map.of("es", es, "plain", plain), Map.of());
        assertThat(r.problems()).isEmpty();
        assertThat(r.env().get(0).getValue()).isEqualTo("https://es.demo.svc");
        assertThat(r.env().get(1).getValue()).isEqualTo("http://es.demo.svc:9200");
    }

    @Test
    void serviceRef() {
        Result r = run(List.of(lit("API", "${api.url}"), lit("P", "${api.port}"), lit("H", "${api.host}")),
                DEPS, Map.of("api", new ServiceAddr("app-api", 8080)));
        assertThat(r.problems()).isEmpty();
        assertThat(r.env().get(0).getValue()).isEqualTo("http://app-api:8080");
        assertThat(r.env().get(1).getValue()).isEqualTo("8080");
        assertThat(r.env().get(2).getValue()).isEqualTo("app-api");
    }

    @Test
    void secretRowAndPlainLiteral() {
        Result r = run(List.of(new AppEnvRow("S", "SECRET", null, "my-sec", "k"), lit("L", "info")), DEPS, Map.of());
        assertThat(r.env().get(0).getSecretName()).isEqualTo("my-sec");
        assertThat(r.env().get(1).getValue()).isEqualTo("info");
    }

    @Test
    void referencesCollectsKeys() {
        assertThat(AppEnvResolver.references(List.of(lit("A", "${db.host}:${cache.port}"), lit("B", "x"),
                new AppEnvRow("S", "SECRET", null, "s", "k")))).containsExactlyInAnyOrder("db", "cache");
    }
}
