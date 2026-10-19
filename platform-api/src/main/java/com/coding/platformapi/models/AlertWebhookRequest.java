package com.coding.platformapi.models;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Alertmanager **webhook receiver** 的请求体 —— 一个**信封对象**，告警在 {@code alerts} 里。
 *
 * <pre>
 * {
 *   "version": "4", "groupKey": "...", "truncatedAlerts": 0,
 *   "receiver": "monitor/platform-webhook/platform", "status": "firing",
 *   "groupLabels": {...}, "commonLabels": {...}, "commonAnnotations": {...},
 *   "externalURL": "...",
 *   "alerts": [ { "status": "firing", "labels": {...}, "annotations": {...},
 *                 "startsAt": "...", "endsAt": "...", "generatorURL": "...", "fingerprint": "..." } ]
 * }
 * </pre>
 *
 * <p>⚠️ 两个曾经踩过的坑（真实推送会因此整包 400）：
 * <ol>
 *   <li><b>不是裸数组</b>。裸数组是 {@code GET /api/v2/alerts} 的**返回**形状，与 webhook 推送无关。</li>
 *   <li>alert 里的 {@code status} 是<b>字符串</b> {@code "firing"} / {@code "resolved"}，
 *       不是 {@code {state, silencedBy, inhibitedBy}} 那个对象（那是 API 的形状）。</li>
 * </ol>
 *
 * <p>每次推的是**当前批次快照**，不是增量；投递失败会重试、{@code repeat_interval} 到点会重推，
 * 所以落库必须幂等（见 AlertIngestService）。
 */
@Data
public class AlertWebhookRequest {

    private String version;

    private String groupKey;

    /** 本批次整体状态：firing | resolved */
    private String status;

    /** 形如 {@code monitor/platform-webhook/platform}（AMC 命名空间 / AMC 名 / receiver 名） */
    private String receiver;

    private String externalURL;

    private Map<String, String> groupLabels;

    private Map<String, String> commonLabels;

    private Map<String, String> commonAnnotations;

    private Integer truncatedAlerts;

    private List<Alert> alerts;

    @Data
    public static class Alert {
        /** firing | resolved —— **字符串**（见类注释第 2 条坑） */
        private String status;

        private Map<String, String> labels;

        private Map<String, String> annotations;

        /** RFC3339 */
        private String startsAt;

        /**
         * RFC3339。⚠️ firing 时是**零值时间** {@code 0001-01-01T00:00:00Z}（未结束），
         * 落库前要归一成 null，否则会存一个公元 1 年的结束时间。
         */
        private String endsAt;

        private String generatorURL;

        /** 标签集的哈希 —— 幂等键的一半 */
        private String fingerprint;
    }
}
