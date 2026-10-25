package com.coding.platformapi.app;

import com.coding.common.models.app.AppEdgeDTO;
import com.coding.common.models.app.AppNodeDTO;
import com.coding.common.models.app.AppProblemDTO;
import com.coding.common.models.app.AppSpecDTO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppSpecValidatorTest {

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
    void acceptsSimpleValidGraph() {
        assertThat(errors(spec(List.of(node("web", "SERVICE")), List.of()))).isEmpty();
    }

    @Test
    void rejectsUnknownKindAndCronjob() {
        assertThat(errors(spec(List.of(node("job", "CRONJOB")), List.of())))
                .anySatisfy(m -> assertThat(m).contains("不支持的节点类型"));
        assertThat(errors(spec(List.of(node("db", "DATABASE")), List.of())))
                .anySatisfy(m -> assertThat(m).contains("不支持的节点类型"));
    }

    @Test
    void rejectsDuplicateAndIllegalKey() {
        assertThat(errors(spec(List.of(node("web", "SERVICE"), node("web", "WORKER")), List.of())))
                .anySatisfy(m -> assertThat(m).contains("重复的节点 key"));
        assertThat(errors(spec(List.of(node("Web_1", "SERVICE")), List.of())))
                .anySatisfy(m -> assertThat(m).contains("节点 key 只能用小写字母"));
    }

    @Test
    void rejectsEdgeToMissingNodeAndSelfLoop() {
        assertThat(errors(spec(List.of(node("web", "SERVICE")), List.of(edge("web", "ghost")))))
                .anySatisfy(m -> assertThat(m).contains("连线指向不存在的节点"));
        assertThat(errors(spec(List.of(node("web", "SERVICE")), List.of(edge("web", "web")))))
                .anySatisfy(m -> assertThat(m).contains("不能指向自己"));
    }

    @Test
    void rejectsCycle() {
        List<AppNodeDTO> nodes = List.of(node("a", "SERVICE"), node("b", "SERVICE"), node("c", "SERVICE"));
        List<AppEdgeDTO> edges = List.of(edge("a", "b"), edge("b", "c"), edge("c", "a"));
        assertThat(errors(spec(nodes, edges))).anySatisfy(m -> assertThat(m).contains("循环依赖"));
    }

    @Test
    void rejectsTooManyNodesAndMissingNamespace() {
        List<AppNodeDTO> nodes = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            nodes.add(node("n" + i, "SERVICE"));
        }
        assertThat(errors(spec(nodes, List.of()))).anySatisfy(m -> assertThat(m).contains("最多 20 个节点"));
        AppSpecDTO noNamespace = spec(List.of(node("web", "SERVICE")), List.of());
        noNamespace.setNamespace("");
        assertThat(errors(noNamespace)).anySatisfy(m -> assertThat(m).contains("未选择命名空间"));
    }
}
