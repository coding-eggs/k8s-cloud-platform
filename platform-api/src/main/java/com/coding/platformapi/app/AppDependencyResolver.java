package com.coding.platformapi.app;

import com.coding.common.models.app.AppEdgeDTO;
import com.coding.common.models.app.AppInjectDTO;
import com.coding.common.models.app.AppNodeDTO;
import com.coding.common.models.app.AppSpecDTO;
import com.coding.common.models.middleware.MiddlewareConnectionDTO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 依赖解析：连线 → 组件环境变量。纯函数（不碰集群、不碰库）。
 *
 * <p>变量名统一 {@code <节点KEY>_<协议>_<字段>}（节点 key 转大写、连字符变下划线）：
 * 用户在检查器里看到的就是这一组名字，不再有第二套命名。
 */
public final class AppDependencyResolver {

    /** 一个组件节点要注入的变量（已按下标 name 索引，便于测试与去重）。 */
    public record Injection(String nodeKey, Map<String, AppInjectDTO> byName) {
    }

    private static final Map<String, String> PROTOCOL_LABELS = Map.of(
            "mysql", "MYSQL", "postgresql", "POSTGRES", "redis", "REDIS",
            "kafka", "KAFKA", "rabbitmq", "AMQP", "elasticsearch", "ES",
            "nacos", "NACOS", "milvus", "MILVUS");

    private AppDependencyResolver() {
    }

    public static List<Injection> resolve(AppSpecDTO spec, Map<String, MiddlewareConnectionDTO.ConnInfo> connByNode) {
        Map<String, List<String>> usesByComponent = new HashMap<>();
        for (AppEdgeDTO e : spec.getEdges() == null ? List.<AppEdgeDTO>of() : spec.getEdges()) {
            usesByComponent.computeIfAbsent(e.getFrom(), k -> new ArrayList<>()).add(e.getTo());
        }
        List<Injection> out = new ArrayList<>();
        for (AppNodeDTO n : spec.getNodes() == null ? List.<AppNodeDTO>of() : spec.getNodes()) {
            List<String> uses = usesByComponent.getOrDefault(n.getKey(), List.of());
            if (uses.isEmpty()) {
                continue;
            }
            Map<String, AppInjectDTO> env = new LinkedHashMap<>();
            for (String dep : uses) {
                MiddlewareConnectionDTO.ConnInfo info = connByNode.get(dep);
                if (info != null) {
                    injectInto(env, AppObjectNames.envPrefix(dep), info);
                }
            }
            if (!env.isEmpty()) {
                out.add(new Injection(n.getKey(), env));
            }
        }
        return out;
    }

    static void injectInto(Map<String, AppInjectDTO> env, String prefix,
                           MiddlewareConnectionDTO.ConnInfo info) {
        String key = prefix + "_" + PROTOCOL_LABELS.getOrDefault(info.getProtocol(), "DB");
        switch (info.getProtocol() == null ? "" : info.getProtocol()) {
            // 客户端要的是 bootstrap.servers：只给一个 BOOTSTRAP，不给 HOST/PORT 免得用户拼错
            case "kafka" -> env.put(key + "_BOOTSTRAP", AppInjectDTO.value(key + "_BOOTSTRAP",
                    info.getHost() + ":" + info.getPort()));
            // HTTP 客户端要完整 URL
            case "elasticsearch", "nacos" -> env.put(key + "_URL", AppInjectDTO.value(key + "_URL",
                    "http://" + info.getHost() + (info.getPort() == null ? "" : ":" + info.getPort())));
            default -> {
                env.put(key + "_HOST", AppInjectDTO.value(key + "_HOST", info.getHost()));
                if (info.getPort() != null) {
                    env.put(key + "_PORT", AppInjectDTO.value(key + "_PORT", String.valueOf(info.getPort())));
                }
            }
        }
        if (info.getUsernameKey() != null && info.getSecretName() != null) {
            env.put(key + "_USER", AppInjectDTO.secret(key + "_USER", info.getSecretName(), info.getUsernameKey()));
        }
        if (info.getPasswordKey() != null && info.getSecretName() != null) {
            env.put(key + "_PASSWORD",
                    AppInjectDTO.secret(key + "_PASSWORD", info.getSecretName(), info.getPasswordKey()));
        }
    }

    /** 中间件节点的创建顺序（v1 串行；按 key 稳定排序，保证同一张图每次顺序一致）。 */
    public static List<String> middlewareOrder(AppSpecDTO spec) {
        return spec.getNodes().stream()
                .filter(n -> "MIDDLEWARE".equals(n.getKind()))
                .map(AppNodeDTO::getKey)
                .sorted()
                .toList();
    }
}
