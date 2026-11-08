package com.coding.platformapi.services;

import com.coding.data.models.system.PlatformMiddlewareInstance;
import com.coding.platformapi.app.AppSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 中间件可对外暴露的端点（spec 2026-11-09 §1）。Service 名 / 端口与 {@link MiddlewareService#connection} 一致——
 * 改那边的地址时这里要一起改。
 *
 * <p>只列「客户端只连一个地址」的端点。Kafka / Nacos / Redis 哨兵与集群返回不支持的原因，页面照实显示。
 */
public final class MiddlewareExposeCatalog {

    /** protocol：走网关时的 listener 协议（HTTP / TCP），由端点决定，用户不选 */
    public record Endpoint(String key, String label, String service, int port, String protocol) {
    }

    /** unsupportedReason 非空 = 这个实例不支持对外暴露（endpoints 为空） */
    public record Result(List<Endpoint> endpoints, String unsupportedReason) {
        static Result unsupported(String reason) {
            return new Result(List.of(), reason);
        }
    }

    private MiddlewareExposeCatalog() {
    }

    public static Result of(PlatformMiddlewareInstance instance) {
        String name = instance.getName();
        Map<String, Object> spec = AppSnapshot.parseMap(instance.getSpecJson());
        List<Endpoint> out = new ArrayList<>();
        switch (instance.getCode() == null ? "" : instance.getCode()) {
            case "mysql" -> {
                out.add(new Endpoint("rw", "读写（HAProxy）", name + "-haproxy", 3306, "TCP"));
                out.add(new Endpoint("ro", "只读（HAProxy）", name + "-haproxy", 3307, "TCP"));
            }
            case "postgresql" -> {
                out.add(new Endpoint("rw", "读写（主）", name + "-rw", 5432, "TCP"));
                out.add(new Endpoint("ro", "只读（备）", name + "-ro", 5432, "TCP"));
            }
            case "redis" -> {
                String mode = instance.getMode() == null ? "" : instance.getMode();
                if ("SENTINEL".equals(mode) || "CLUSTER".equals(mode)) {
                    return Result.unsupported("Redis 哨兵 / 集群模式会把客户端引到 Pod IP（哨兵返回主节点地址、"
                            + "集群模式 MOVED 重定向），集群外连不上，暂不支持对外暴露；单机模式可以");
                }
                out.add(new Endpoint("main", "节点", name, 6379, "TCP"));
            }
            case "elasticsearch" -> {
                // 开了 TLS：网关 HTTP 不终止 TLS，走 TCP 透传（客户端直接对 ES 握手）
                String protocol = Boolean.FALSE.equals(spec.get("tls")) ? "HTTP" : "TCP";
                out.add(new Endpoint("http", "ES 接口", name + "-es-http", 9200, protocol));
                if (!Boolean.FALSE.equals(spec.get("kibana"))) {
                    out.add(new Endpoint("kibana", "Kibana", name + "-kb-http", 5601, protocol));
                }
            }
            case "rabbitmq" -> {
                out.add(new Endpoint("amqp", "AMQP", name, 5672, "TCP"));
                out.add(new Endpoint("management", "管理台", name, 15672, "HTTP"));
            }
            case "milvus" -> out.add(new Endpoint("grpc", "gRPC", name + "-milvus", 19530, "TCP"));
            case "kafka" -> {
                return Result.unsupported("Kafka 客户端连上 bootstrap 后会改连各 broker 通告的地址，"
                        + "需要每个 broker 单独一个对外端口并改 listener 配置，暂不支持对外暴露");
            }
            case "nacos" -> {
                return Result.unsupported("Nacos 2.x 客户端默认连「HTTP 端口 + 1000」的 gRPC 端口，"
                        + "端口池分不出这样一对端口，暂不支持对外暴露");
            }
            default -> {
                return Result.unsupported("该中间件暂不支持对外暴露");
            }
        }
        return new Result(out, null);
    }

    /** 实例没开鉴权、暴露出去任何人都能连：页面开启前要二次确认 */
    public static boolean unauthenticated(PlatformMiddlewareInstance instance) {
        return "milvus".equals(instance.getCode())
                && !Boolean.TRUE.equals(AppSnapshot.parseMap(instance.getSpecJson()).get("authEnabled"));
    }
}
