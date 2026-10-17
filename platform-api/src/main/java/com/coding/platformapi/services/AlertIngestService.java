package com.coding.platformapi.services;

import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.k8s.PlatformAlertEventMapper;
import com.coding.data.models.k8s.PlatformAlertEvent;
import com.coding.platformapi.models.AlertWebhookRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 告警落库。
 *
 * <p><b>幂等</b>：Alertmanager 至少一次投递（投递失败重试、{@code repeat_interval} 到点重推），
 * 同一次 firing 会话会被推很多遍。幂等键 {@code (clusterId, fingerprint, startsAt)} 由
 * SQL 的 ON DUPLICATE KEY UPDATE 承担 —— 本层只负责**每次都给同一组键**，
 * 不做"先查后写"（那有并发窗口）。
 *
 * <p><b>归属</b>：{@code labels.namespace} 是规则作者自报的（平台下发的规则会被代打这个标签，
 * 见 PrometheusRuleService；kubectl 直连建的可以伪造）。本层只做一件事：
 * 该命名空间在分配表里查得到就放行，查不到只记 warn 并照常落库
 * —— 管理员必须看得见"不属于任何租户"的告警，那是排查用的信号。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertIngestService {

    private final PlatformAlertEventMapper mapper;
    private final PlatformTenantNamespaceMapper allocs;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /** 落库。返回**实际成功条数**。单条失败不中断整批（Alertmanager 只看状态码）。 */
    public int ingest(String clusterId, List<AlertWebhookRequest.Alert> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            return 0;
        }
        int ok = 0;
        for (AlertWebhookRequest.Alert a : alerts) {
            try {
                PlatformAlertEvent row = toRow(clusterId, a);
                if (row == null) {
                    continue;                          // alertname / startsAt 缺失 —— 记 warn 后跳过
                }
                mapper.upsert(row);
                ok++;
            } catch (Exception e) {
                log.error("告警落库失败（cluster={} alertname={}）",
                        clusterId, a.getLabels() != null ? a.getLabels().get("alertname") : null, e);
            }
        }
        return ok;
    }

    /** 转成表行；返回 null = 这条不可落库（调用方跳过）。 */
    private PlatformAlertEvent toRow(String clusterId, AlertWebhookRequest.Alert a) {
        Map<String, String> labels = a.getLabels();
        String alertname = labels != null ? labels.get("alertname") : null;
        if (!StringUtils.hasText(alertname)) {
            log.warn("告警缺少 alertname，跳过（cluster={}）", clusterId);
            return null;
        }

        // startsAt 是幂等键的一半：缺了它每次重推都会插新行 —— 那比丢一条更糟
        Date startsAt = parseInstant(a.getStartsAt());
        if (startsAt == null) {
            log.warn("告警的 startsAt 缺失或无法解析，跳过（cluster={} alertname={} startsAt={}）",
                    clusterId, alertname, a.getStartsAt());
            return null;
        }

        PlatformAlertEvent row = new PlatformAlertEvent();
        row.setClusterId(clusterId);
        row.setAlertname(alertname);
        row.setFingerprint(a.getFingerprint());
        row.setStatus(a.getStatus() != null ? a.getStatus().getState() : null);
        row.setSeverity(labels.get("severity"));
        row.setLabels(toJson(labels));
        row.setAnnotations(toJson(a.getAnnotations()));
        row.setStartsAt(startsAt);
        row.setEndsAt(parseInstant(a.getEndsAt()));
        row.setReceivedAt(Date.from(Instant.now().truncatedTo(ChronoUnit.MILLIS)));

        String ns = labels.get("namespace");
        row.setNamespace(StringUtils.hasText(ns) ? ns : null);   // 空串归一成 null
        warnIfUnowned(clusterId, row.getNamespace());
        return row;
    }

    /** 分配表里查不到只记 warn —— 告警照常落库（管理员要看得见「不属于任何租户」的告警）。 */
    private void warnIfUnowned(String clusterId, String namespace) {
        if (namespace == null) {
            return;
        }
        try {
            List<?> hits = allocs.selectByClusterAndNs(clusterId, namespace);
            if (hits == null || hits.isEmpty()) {
                log.warn("告警的命名空间不在任何租户分配里（cluster={} namespace={}）—— 照常落库，管理员可见",
                        clusterId, namespace);
            }
        } catch (Exception e) {
            log.warn("反查命名空间分配失败（cluster={} namespace={}）: {}", clusterId, namespace, e.getMessage());
        }
    }

    /**
     * RFC3339 → Date，**截到毫秒**。
     * <p>Alertmanager 给的时间戳可能带 6 位小数秒，而落库列是 {@code DATETIME(3)} ——
     * 不截断的话唯一键在"读回再比较"时会对不上，同一次会话被当成两次。
     */
    private static Date parseInstant(String rfc3339) {
        if (!StringUtils.hasText(rfc3339)) {
            return null;
        }
        try {
            return Date.from(OffsetDateTime.parse(rfc3339).toInstant().truncatedTo(ChronoUnit.MILLIS));
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Map<String, String> map) {
        if (map == null) {
            return null;
        }
        try {
            return jsonMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("序列化 labels/annotations 失败: {}", e.getMessage());
            return null;
        }
    }
}
