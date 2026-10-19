package com.coding.platformapi.components;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OTel 采集栈的 CR 对象（S3.6 的 {@code extraObjects}）。
 *
 * <p>这里生成的是**第二遍 operator release** 要下发的对象：3 个 Instrumentation +
 * 1 个 collector gateway。第一遍只装 CRD + operator（否则 Helm Build 阶段找不到 CRD）。
 *
 * <p>字符串里保留平台占位符（{@code ${temp...}}、{@code ${otel.*}}、{@code ${cluster.name}}），
 * 由 {@link ValueTemplate} 在落库前解析；collector 自己的 {@code ${env:...}} 含冒号，
 * 不会命中平台占位符正则，留给 collector 启动时展开。
 *
 * <p>{@code replicas} 必须是 **int**：ValueTemplate 只能产出字符串，而 CRD 的 replicas 是整数；
 * 所以它作为方法参数传入，不走占位符。
 */
public final class OtelObjects {

    public static final String NAMESPACE = "monitor";
    public static final String GATEWAY = "platform-otel-gateway";
    public static final String GATEWAY_SERVICE = "platform-otel-gateway-collector";
    /** 日志索引前缀：**固定值**（组件表单不再暴露）。日志查询中心按同一个常量裁剪按天索引。 */
    public static final String LOG_INDEX_PREFIX = "otel-logs";
    public static final String CONTRIB_IMAGE =
            "ghcr.io/open-telemetry/opentelemetry-collector-releases/opentelemetry-collector-contrib:0.161.0";

    private static final String INSTRUMENTATION_ENDPOINT =
            "http://" + GATEWAY_SERVICE + "." + NAMESPACE + ".svc:4318";

    private OtelObjects() {
    }

    /**
     * @param logsEnabled    是否渲染 logs 管道与 ES exporter（关闭时连 ES 的环境变量都不出现，
     *                       否则会引用一个不存在的 Secret，Pod 起不来）
     * @param gatewayReplicas collector Deployment 副本数（表单值）
     */
    public static List<Map<String, Object>> build(boolean logsEnabled, int gatewayReplicas, String tempoTlsMode) {
        return List.of(
                instrumentation("platform-instrumentation", true, false),
                instrumentation("platform-instrumentation-logs", false, true),
                instrumentation("platform-instrumentation-all", true, true),
                gateway(logsEnabled, gatewayReplicas, tempoTlsMode),
                sidecar());
    }

    /**
     * 文件日志的 per-Pod collector（批次二）。**只有工作负载显式开启文件日志时才被注入**
     * （注解 {@code sidecar.opentelemetry.io/inject}），CR 本身常驻无害。
     *
     * <p>卷名固定 {@code platform-otel-logs}：工作负载模板把日志文件的**父目录**挂到这个 emptyDir，
     * sidecar 只读挂到 {@code /var/log/app}；两边看到的是同一份文件。
     *
     * <p>采集的是**具体文件**而不是整个目录：include 从环境变量
     * {@code OTEL_FILELOG_INCLUDE} 取，值来自每个 Pod 自己的注解
     * {@code platform.coding/otel-log-file}（downward API 注入）。这样共享一个 CR
     * 也能让不同工作负载各采各的文件（支持 {@code access.log*} 这类 glob）。
     */
    private static Map<String, Object> sidecar() {
        Map<String, Object> config = Map.of(
                "receivers", Map.of("filelog", Map.of(
                        "include", List.of("${env:OTEL_FILELOG_INCLUDE}"),
                        "start_at", "end",
                        "include_file_path", true)),
                "processors", Map.of(
                        "resource", Map.of("attributes", List.of(
                                Map.of("key", "k8s.pod.name", "value", "${env:K8S_POD_NAME}",
                                        "action", "upsert"),
                                Map.of("key", "k8s.namespace.name", "value", "${env:K8S_NAMESPACE}",
                                        "action", "upsert"),
                                Map.of("key", "k8s.pod.uid", "value", "${env:K8S_POD_UID}",
                                        "action", "upsert"))),
                        // 不再在 sidecar 里跑 k8sattributes：sidecar 用 Pod 的 SA，未必有 list pods 权限；
                        // gateway 收到同 Pod IP 的 OTLP 后会用 from: connection 补齐 deployment
                        "batch", Map.of("timeout", "5s")),
                "exporters", Map.of("otlp", Map.of(
                        "endpoint", GATEWAY_SERVICE + "." + NAMESPACE + ".svc:4317",
                        "tls", Map.of("insecure", true))),
                "service", Map.of("pipelines", Map.of("logs", Map.of(
                        "receivers", List.of("filelog"),
                        "processors", List.of("resource", "batch"),
                        "exporters", List.of("otlp")))));

        return Map.of(
                "apiVersion", "opentelemetry.io/v1beta1",
                "kind", "OpenTelemetryCollector",
                "metadata", Map.of("name", "platform-otel-sidecar", "namespace", NAMESPACE),
                "spec", Map.of(
                        "mode", "sidecar",
                        "image", CONTRIB_IMAGE,
                        "env", List.of(
                                Map.of("name", "K8S_POD_NAME", "valueFrom",
                                        Map.of("fieldRef", Map.of("fieldPath", "metadata.name"))),
                                Map.of("name", "K8S_NAMESPACE", "valueFrom",
                                        Map.of("fieldRef", Map.of("fieldPath", "metadata.namespace"))),
                                Map.of("name", "K8S_POD_UID", "valueFrom",
                                        Map.of("fieldRef", Map.of("fieldPath", "metadata.uid"))),
                                // 每 Pod 的具体日志文件（sidecar 视角：/var/log/app/<文件名>）；
                                // 只有平台写入的文件日志工作负载才有这个注解，注入与注解成对出现
                                Map.of("name", "OTEL_FILELOG_INCLUDE", "valueFrom",
                                        Map.of("fieldRef", Map.of("fieldPath",
                                                "metadata.annotations['platform.coding/otel-log-file']")))),
                        "volumeMounts", List.of(Map.of(
                                "name", "platform-otel-logs",
                                "mountPath", "/var/log/app",
                                "readOnly", true)),
                        "config", config));
    }

    private static Map<String, Object> instrumentation(String name, boolean tracing, boolean logs) {
        List<Map<String, Object>> env = new ArrayList<>();
        env.add(Map.of("name", "OTEL_EXPORTER_OTLP_PROTOCOL", "value", "http/protobuf"));
        if (logs) {
            env.add(Map.of("name", "OTEL_LOGS_EXPORTER", "value", "otlp"));
        }

        Map<String, Object> spec = new LinkedHashMap<>();
        // 端点用 4318：Python 注入镜像只带 http/protobuf exporter；Java agent 同样支持
        spec.put("exporter", Map.of("endpoint", INSTRUMENTATION_ENDPOINT));
        spec.put("propagators", List.of("tracecontext", "baggage"));
        spec.put("env", env);
        spec.put("sampler", tracing
                ? Map.of("type", "parentbased_traceidratio", "argument", "${otel.sampling}")
                : Map.of("type", "always_off"));
        spec.put("resource", Map.of("resourceAttributes", Map.of("cluster.name", "${cluster.name}")));
        spec.put("java", logs
                ? Map.of("env", List.of(
                        Map.of("name", "OTEL_INSTRUMENTATION_LOGBACK_APPENDER_ENABLED", "value", "true"),
                        Map.of("name", "OTEL_INSTRUMENTATION_LOG4J_APPENDER_ENABLED", "value", "true")))
                : Map.of());
        spec.put("python", Map.of());

        return Map.of(
                "apiVersion", "opentelemetry.io/v1alpha1",
                "kind", "Instrumentation",
                "metadata", Map.of("name", name, "namespace", NAMESPACE),
                "spec", spec);
    }

    private static Map<String, Object> gateway(boolean logsEnabled, int replicas, String tempoTlsMode) {
        Map<String, Object> exporters = new LinkedHashMap<>();
        Map<String, Object> tempoExporter = new LinkedHashMap<>();
        tempoExporter.put("endpoint", "${env:TEMPO_ENDPOINT}");
        switch (tempoTlsMode == null ? "plaintext" : tempoTlsMode) {
            case "tls" -> {
                // 不写 tls 块 = 默认 TLS + 校验证书（管理员用受信任证书时）
            }
            case "tls-skip-verify" -> tempoExporter.put("tls", Map.of("insecure_skip_verify", true));
            default -> tempoExporter.put("tls", Map.of("insecure", true));
        }
        exporters.put("otlp/tempo", tempoExporter);
        exporters.put("prometheus", Map.of(
                "endpoint", "0.0.0.0:8889",
                "resource_to_telemetry_conversion", Map.of("enabled", true)));
        if (logsEnabled) {
            exporters.put("elasticsearch", Map.of(
                    "endpoints", List.of("${env:ES_ENDPOINT}"),
                    "user", "${env:ES_USERNAME}",
                    "password", "${env:ES_PASSWORD}",
                    "tls", Map.of("insecure_skip_verify", true),
                    // logs_index 是**前缀**（固定值）；logstash_format 追加日期 → otel-logs-YYYY.MM.DD（按天分割）
                    "logs_index", LOG_INDEX_PREFIX,
                    "logstash_format", Map.of(
                            "enabled", true,
                            "prefix_separator", "-",
                            "date_format", "%Y.%m.%d"),
                    // ⚠️ 0.161.0 起 mapping.mode 被忽略且默认是 otel —— otel/ecs 模式走 data stream
                    //     （require_data_stream=true），普通索引会报 resource_not_found。
                    //     用 allowed_modes 固定为 none：写普通索引，ES 自动创建。
                    "mapping", Map.of("allowed_modes", List.of("none"))));
        }

        Map<String, Object> pipelines = new LinkedHashMap<>();
        pipelines.put("traces", Map.of(
                "receivers", List.of("otlp"),
                "processors", List.of("memory_limiter", "resource", "k8sattributes", "batch"),
                "exporters", List.of("otlp/tempo")));
        pipelines.put("metrics", Map.of(
                "receivers", List.of("otlp"),
                "processors", List.of("memory_limiter", "resource", "k8sattributes", "batch"),
                "exporters", List.of("prometheus")));
        if (logsEnabled) {
            pipelines.put("logs", Map.of(
                    "receivers", List.of("otlp"),
                    "processors", List.of("memory_limiter", "resource", "k8sattributes", "batch"),
                    "exporters", List.of("elasticsearch")));
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("receivers", Map.of("otlp", Map.of("protocols", Map.of(
                "grpc", Map.of("endpoint", "0.0.0.0:4317"),
                "http", Map.of("endpoint", "0.0.0.0:4318")))));
        config.put("processors", Map.of(
                "memory_limiter", Map.of(
                        "check_interval", "1s",
                        "limit_percentage", 80,
                        "spike_limit_percentage", 20),
                // 应用 span 由 Instrumentation 带 cluster.name；网格 span 没有 —— 在 gateway 统一补
                "resource", Map.of("attributes", List.of(Map.of(
                        "key", "cluster.name", "value", "${cluster.name}", "action", "upsert"))),
                "k8sattributes", Map.of(
                        "auth_type", "serviceAccount",
                        "extract", Map.of("metadata", List.of(
                                "k8s.namespace.name", "k8s.pod.name",
                                "k8s.deployment.name", "k8s.statefulset.name",
                                "k8s.daemonset.name", "k8s.job.name", "k8s.node.name")),
                        "pod_association", List.of(
                                Map.of("sources", List.of(Map.of(
                                        "from", "resource_attribute", "name", "k8s.pod.ip"))),
                                Map.of("sources", List.of(Map.of("from", "connection"))))),
                "batch", Map.of("timeout", "5s", "send_batch_size", 8192)));
        config.put("exporters", exporters);
        config.put("service", Map.of("pipelines", pipelines));

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("mode", "deployment");
        spec.put("replicas", replicas);
        spec.put("image", CONTRIB_IMAGE);
        spec.put("ports", List.of(
                Map.of("name", "otlp-grpc", "port", 4317, "protocol", "TCP"),
                Map.of("name", "otlp-http", "port", 4318, "protocol", "TCP"),
                Map.of("name", "prometheus", "port", 8889, "protocol", "TCP")));
        spec.put("env", gatewayEnv(logsEnabled));
        spec.put("resources", Map.of(
                "requests", Map.of("cpu", "200m", "memory", "256Mi"),
                "limits", Map.of("cpu", "1", "memory", "1Gi")));
        spec.put("config", config);

        return Map.of(
                "apiVersion", "opentelemetry.io/v1beta1",
                "kind", "OpenTelemetryCollector",
                "metadata", Map.of("name", GATEWAY, "namespace", NAMESPACE),
                "spec", spec);
    }

    private static List<Map<String, Object>> gatewayEnv(boolean logsEnabled) {
        List<Map<String, Object>> env = new ArrayList<>();
        env.add(Map.of("name", "TEMPO_ENDPOINT", "value", "${tempo.endpoint}"));
        if (logsEnabled) {
            // 日志关闭时不引用 platform-otel-es（平台不会创建它），否则 Pod 会因缺 Secret 起不来
            env.add(Map.of("name", "ES_ENDPOINT", "value", "${otel.es.endpoint}"));
            // 用户名/密码都来自平台在集群内生成的 platform-otel-es（来源 Secret 固定 username/password 两个 key）
            env.add(Map.of("name", "ES_USERNAME", "valueFrom", Map.of("secretKeyRef",
                    Map.of("name", "platform-otel-es", "key", "username"))));
            env.add(Map.of("name", "ES_PASSWORD", "valueFrom", Map.of("secretKeyRef",
                    Map.of("name", "platform-otel-es", "key", "password"))));
        }
        return env;
    }
}
