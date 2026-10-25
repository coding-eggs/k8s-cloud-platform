package com.coding.platformapi.app;

import com.coding.common.models.app.AppEdgeDTO;
import com.coding.common.models.app.AppInjectDTO;
import com.coding.common.models.app.AppNodeDTO;
import com.coding.common.models.app.AppSpecDTO;
import com.coding.common.models.middleware.MiddlewareConnectionDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppDependencyResolverTest {

    private static AppNodeDTO node(String key, String kind) {
        AppNodeDTO n = new AppNodeDTO();
        n.setKey(key);
        n.setKind(kind);
        n.setParams(Map.of());
        return n;
    }

    private static AppEdgeDTO edge(String from, String to) {
        AppEdgeDTO e = new AppEdgeDTO();
        e.setFrom(from);
        e.setTo(to);
        return e;
    }

    @Test
    void injectsHostPortUserAndPasswordRef() {
        AppSpecDTO spec = new AppSpecDTO();
        spec.setNodes(List.of(node("backend", "SERVICE"), node("main-db", "MIDDLEWARE")));
        spec.setEdges(List.of(edge("backend", "main-db")));

        MiddlewareConnectionDTO.ConnInfo info = MiddlewareConnectionDTO.ConnInfo
                .of("mysql", "demo-mysql.demo.svc.cluster.local", 3306)
                .secret("demo-mysql-secrets", "root", "root")
                .build();

        List<AppDependencyResolver.Injection> out =
                AppDependencyResolver.resolve(spec, Map.of("main-db", info));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).nodeKey()).isEqualTo("backend");
        Map<String, AppInjectDTO> byName = out.get(0).byName();
        assertThat(byName.get("MAIN_DB_MYSQL_HOST").getValue())
                .isEqualTo("demo-mysql.demo.svc.cluster.local");
        assertThat(byName.get("MAIN_DB_MYSQL_PORT").getValue()).isEqualTo("3306");
        assertThat(byName.get("MAIN_DB_MYSQL_USER").getSecretName()).isEqualTo("demo-mysql-secrets");
        assertThat(byName.get("MAIN_DB_MYSQL_USER").getSecretKey()).isEqualTo("root");
        assertThat(byName.get("MAIN_DB_MYSQL_PASSWORD").getSecretName()).isEqualTo("demo-mysql-secrets");
        // 密码永远是引用，不落值
        assertThat(byName.get("MAIN_DB_MYSQL_PASSWORD").getValue()).isNull();
    }

    @Test
    void kafkaWithoutCredentialsInjectsOnlyBootstrap() {
        AppSpecDTO spec = new AppSpecDTO();
        spec.setNodes(List.of(node("backend", "SERVICE"), node("mq", "MIDDLEWARE")));
        spec.setEdges(List.of(edge("backend", "mq")));
        MiddlewareConnectionDTO.ConnInfo info = MiddlewareConnectionDTO.ConnInfo
                .of("kafka", "demo-kafka-kafka-bootstrap.demo.svc.cluster.local", 9092).build();

        Map<String, AppInjectDTO> env = AppDependencyResolver
                .resolve(spec, Map.of("mq", info)).get(0).byName();

        // 变量名统一 <节点KEY>_<协议>_<字段>
        assertThat(env).containsOnlyKeys("MQ_KAFKA_BOOTSTRAP");
        assertThat(env.get("MQ_KAFKA_BOOTSTRAP").getValue())
                .isEqualTo("demo-kafka-kafka-bootstrap.demo.svc.cluster.local:9092");
    }

    @Test
    void elasticsearchGetsUrlInsteadOfHostPort() {
        AppSpecDTO spec = new AppSpecDTO();
        spec.setNodes(List.of(node("web", "SERVICE"), node("search", "EXTERNAL")));
        spec.setEdges(List.of(edge("web", "search")));
        MiddlewareConnectionDTO.ConnInfo info = MiddlewareConnectionDTO.ConnInfo
                .of("elasticsearch", "es.demo.svc", 9200)
                .secret("es-elastic", null, "elastic").build();

        Map<String, AppInjectDTO> env = AppDependencyResolver
                .resolve(spec, Map.of("search", info)).get(0).byName();

        assertThat(env.get("SEARCH_ES_URL").getValue()).isEqualTo("http://es.demo.svc:9200");
        assertThat(env.get("SEARCH_ES_PASSWORD").getSecretName()).isEqualTo("es-elastic");
    }

    @Test
    void componentsWithoutDependenciesGetNothing() {
        AppSpecDTO spec = new AppSpecDTO();
        spec.setNodes(List.of(node("web", "SERVICE")));
        spec.setEdges(List.of());

        assertThat(AppDependencyResolver.resolve(spec, Map.of())).isEmpty();
    }

    @Test
    void middlewareOrderIsStable() {
        AppSpecDTO spec = new AppSpecDTO();
        spec.setNodes(List.of(node("zeta", "MIDDLEWARE"), node("alpha", "MIDDLEWARE"), node("web", "SERVICE")));

        assertThat(AppDependencyResolver.middlewareOrder(spec)).containsExactly("alpha", "zeta");
    }
}
