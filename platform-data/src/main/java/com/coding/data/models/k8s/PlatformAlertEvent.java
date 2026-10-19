package com.coding.data.models.k8s;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 告警事件（表 platform_alert_event）。**一次 firing 会话一行**。
 *
 * <p>设计见 docs/superpowers/specs/2026-10-17-alerting-design.md。
 *
 * <p><b>幂等键 = (clusterId, fingerprint, startsAt)</b>，理由见迁移文件的注释 —— 一句话：
 * fingerprint 单独不够（它跨会话不变），加 startsAt 才是一次会话。
 *
 * <p><b>刻意没有 tenantId</b>：归属在查询时 join platform_tenant_namespace 决定。
 * 冗余一份会漂，而且一个命名空间可以分配给多个租户，定死 tenantId 表达不了。
 *
 * <p>{@code namespace} 来自 Alertmanager payload 的 {@code labels.namespace}，是**规则作者自报的**。
 * 平台在下发 PrometheusRule 时代打这个标签（见 PrometheusRuleService），所以经平台建的规则可信；
 * 但租户 kubectl 直连建的规则可以伪造。**只能注入、不能读取**（spec §13 残留风险）。
 */
@Data
public class PlatformAlertEvent {

    private Long id;

    private String clusterId;

    private String namespace;

    private String alertname;

    private String severity;

    /** firing | resolved */
    private String status;

    private String fingerprint;

    /** 原始 labels 的 JSON 串（落库为 JSON 列） */
    private String labels;

    /** 原始 annotations 的 JSON 串 */
    private String annotations;

    /**
     * ⚠️ 必须带毫秒。全应用默认的 Date 格式是 {@code yyyy-MM-dd HH:mm:ss}（见 JsonMapperConfig），
     * 而列表的游标就是 {@code (startsAt, id)} —— 截到秒再回传，下一页的
     * {@code starts_at = cursor} 永远不成立，**「加载更多」会静默失效**（同一秒内的行再也翻不到）。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private Date startsAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private Date endsAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private Date receivedAt;

    /**
     * **查询时补的**，不落库、不参与 SQL：这条告警由哪个 PrometheusRule 的哪个组定义。
     *
     * <p>Alertmanager 的 payload 里没有 CR 名/组名，所以只能按 {@code (namespace, alertname)}
     * 反查 K8s 对象。规则改名或删除过的历史告警查不到 → 前端显示「—」。
     */
    private List<AlertSource> sources;
}
