package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * PodMonitor 抓取端点（monitoring.coreos.com/v1，spec.podMetricsEndpoints[] 项）。
 * 覆盖常用抓取参数 + 鉴权/TLS 常用项 + honorLabels + relabeling 结构化列表。
 * bearerTokenFile 未建模（本 OAS 未见），update 时靠 fetch-overlay 原样保留。
 */
@Data
public class PodMonitorEndpointDTO {

    /** 端口名或端口号（DTO 层单一字段；CRD 边界按 §converter 规则拆 port / portNumber） */
    private String port;

    /** 抓取路径，缺省 /metrics */
    private String path;

    /** 抓取间隔，如 30s */
    private String interval;

    /** 抓取超时，如 10s（须小于 interval） */
    private String scrapeTimeout;

    /** 抓取协议：http / https */
    private String scheme;

    /** 抓取 URL 附加参数（map[string][]string） */
    private Map<String, List<String>> params;

    /** Basic 鉴权（username/password 各引用一个 Secret） */
    private BasicAuth basicAuth;

    /** Bearer Token（引用 Secret 的 name/key） */
    private SecretRef bearerTokenSecret;

    /** TLS 常用项 */
    private TlsConfig tlsConfig;

    /** 目标标签与指标自带标签同名冲突时：true=以指标自带标签为准 */
    private Boolean honorLabels;

    /** 抓取前重打标规则 */
    private List<Relabeling> relabelings;

    /** 抓取后指标重打标规则 */
    private List<Relabeling> metricRelabelings;

    /** Secret 引用（name + key）：basicAuth.username / password、bearerTokenSecret 共用 */
    @Data
    public static class SecretRef {
        private String name;
        private String key;
    }

    /** Basic 鉴权 */
    @Data
    public static class BasicAuth {
        private SecretRef username;
        private SecretRef password;
    }

    /** TLS 常用项（insecureSkipVerify / serverName；ca/cert/keySecret 靠 overlay 保留） */
    @Data
    public static class TlsConfig {
        private Boolean insecureSkipVerify;
        private String serverName;
    }

    /** Relabeling / MetricRelabeling 单条规则 */
    @Data
    public static class Relabeling {
        /** 源标签列表（如 __meta_kubernetes_pod_name） */
        private List<String> sourceLabels;
        private String targetLabel;
        private String regex;
        private String replacement;
        private String separator;
        private Long modulus;
        /** replace / keep / drop / hashmod / labelmap / labeldrop / labelkeep */
        private String action;
    }

}
