package com.coding.platformapi.models;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Alertmanager webhook 的请求体：一个 Alert 数组。
 *
 * <p>形状与 {@code GET /api/v2/alerts} 的返回一致 —— Alertmanager 的 webhook receiver 就是把这个
 * 数组 POST 出来。字段名照官方契约，**不要**照 AlertmanagerConfig CRD 里的 camelCase 版本。
 *
 * <p>⚠️ 每次推的是**当前批次快照**，不是增量；投递失败会重试、{@code repeat_interval} 到点会重推。
 * 所以落库必须幂等（见 AlertIngestService）。
 */
@Data
public class AlertWebhookRequest {

    @Data
    public static class Alert {
        private Status status;
        private Map<String, String> labels;
        private Map<String, String> annotations;
        /** RFC3339 */
        private String startsAt;
        /** RFC3339；firing 时是"预计结束时间"（远未来），resolved 时是真实结束时间 */
        private String endsAt;
        private String generatorURL;
        /** 标签集的哈希 —— 幂等键的一半 */
        private String fingerprint;
    }

    @Data
    public static class Status {
        /** firing | resolved */
        private String state;
        private List<String> silencedBy;
        private List<String> inhibitedBy;
    }
}
