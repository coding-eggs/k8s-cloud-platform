package com.coding.platformapi.app;

import com.coding.common.models.app.AppEdgeDTO;
import com.coding.common.models.app.AppNodeDTO;
import com.coding.common.models.app.AppProblemDTO;
import com.coding.common.models.app.AppSpecDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 中间件 / 外部服务节点与它们的连线规则。 */
class AppSpecValidatorMiddlewareTest {

    private static AppNodeDTO node(String key, String kind, Map<String, Object> params) {
        AppNodeDTO n = new AppNodeDTO();
        n.setKey(key);
        n.setKind(kind);
        n.setParams(params);
        return n;
    }

    private static AppEdgeDTO edge(String from, String to) {
        AppEdgeDTO e = new AppEdgeDTO();
        e.setFrom(from);
        e.setTo(to);
        return e;
    }

    private static AppSpecDTO spec(List<AppNodeDTO> nodes, List<AppEdgeDTO> edges) {
        AppSpecDTO s = new AppSpecDTO();
        s.setNamespace("demo");
        s.setNodes(nodes);
        s.setEdges(edges);
        return s;
    }

    private static List<String> errors(AppSpecDTO s) {
        return AppSpecValidator.validate(s).stream()
                .filter(p -> "ERROR".equals(p.getSeverity()))
                .map(AppProblemDTO::getMessage)
                .toList();
    }

    @Test
    void acceptsMiddlewareAndExternalNodes() {
        AppSpecDTO s = spec(List.of(
                        node("web", "SERVICE", Map.of("image", "nginx")),
                        node("db", "MIDDLEWARE", Map.of("code", "mysql", "provisionMode", "CREATE")),
                        node("mq", "EXTERNAL", Map.of("host", "mq.corp.internal", "port", 9092))),
                List.of(edge("web", "db"), edge("web", "mq")));

        assertThat(errors(s)).isEmpty();
    }

    @Test
    void rejectsMiddlewareMissingCodeOrMode() {
        assertThat(errors(spec(List.of(node("db", "MIDDLEWARE", Map.of())), List.of())))
                .anySatisfy(m -> assertThat(m).contains("中间件节点必须选择中间件类型"));
        assertThat(errors(spec(List.of(node("db", "MIDDLEWARE", Map.of("code", "mysql"))), List.of())))
                .anySatisfy(m -> assertThat(m).contains("必须选择来源"));
        assertThat(errors(spec(List.of(node("db", "MIDDLEWARE",
                Map.of("code", "mysql", "provisionMode", "SOMETHING"))), List.of())))
                .anySatisfy(m -> assertThat(m).contains("不支持的来源方式"));
    }

    @Test
    void bindRequiresInstanceAndExternalRequiresHost() {
        assertThat(errors(spec(List.of(node("db", "MIDDLEWARE",
                Map.of("code", "mysql", "provisionMode", "BIND"))), List.of())))
                .anySatisfy(m -> assertThat(m).contains("绑定已有实例需要选择一个实例"));
        assertThat(errors(spec(List.of(node("db", "MIDDLEWARE",
                Map.of("code", "mysql", "provisionMode", "EXTERNAL"))), List.of())))
                .anySatisfy(m -> assertThat(m).contains("外部中间件必须填写地址"));
    }

    @Test
    void rejectsMiddlewareToMiddlewareEdges() {
        AppSpecDTO s = spec(List.of(
                        node("db", "MIDDLEWARE", Map.of("code", "mysql", "provisionMode", "CREATE")),
                        node("cache", "MIDDLEWARE", Map.of("code", "redis", "provisionMode", "CREATE"))),
                List.of(edge("db", "cache")));

        assertThat(errors(s)).anySatisfy(m -> assertThat(m).contains("中间件之间不能连线"));
    }

    @Test
    void onlyComponentsMayStartAnEdge() {
        AppSpecDTO s = spec(List.of(
                        node("web", "SERVICE", Map.of("image", "nginx")),
                        node("mq", "EXTERNAL", Map.of("host", "mq.corp.internal"))),
                List.of(edge("mq", "web")));

        assertThat(errors(s)).anySatisfy(m -> assertThat(m).contains("只有服务和后台进程可以发起连线"));
    }

    @Test
    void externalNodeRequiresHost() {
        assertThat(errors(spec(List.of(node("ext", "EXTERNAL", Map.of("protocol", "mysql"))), List.of())))
                .anySatisfy(m -> assertThat(m).contains("外部服务必须填写地址"));
    }
}
