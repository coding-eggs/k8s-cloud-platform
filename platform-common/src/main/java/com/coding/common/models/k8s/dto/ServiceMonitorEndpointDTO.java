package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * ServiceMonitor 抓取端点（monitoring.coreos.com/v1，spec.endpoints[] 项）。
 * 覆盖常用抓取参数 + 鉴权/TLS 常用项 + relabeling 结构化列表。
 */
@Data
public class ServiceMonitorEndpointDTO {

    /** 端口名或端口号 */
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

    /** Bearer Token 文件路径（容器内挂载的 token 文件，与 bearerTokenSecret 二选一）。必须建模，否则 atomic list 整体替换时会被丢弃 */
    private String bearerTokenFile;

    /** TLS 常用项 */
    private TlsConfig tlsConfig;

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

    /** TLS 常用项（insecureSkipVerify / serverName） */
    @Data
    public static class TlsConfig {
        private Boolean insecureSkipVerify;
        private String serverName;
    }

    /** Relabeling / MetricRelabeling 单条规则 */
    @Data
    public static class Relabeling {
        /** 源标签列表（如 __meta_kubernetes_service_name） */
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
