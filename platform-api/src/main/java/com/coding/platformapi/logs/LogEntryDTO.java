package com.coding.platformapi.logs;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 日志查询结果的一行（来自 ES 文档；mapping mode=none 的文档是**扁平点号 key**，
 * 如 {@code Resource.k8s.pod.name}、{@code Attributes.http.method}）。
 */
@Data
public class LogEntryDTO {
    /** @timestamp（ISO 字符串，原样返回） */
    private String time;
    private String severity;
    private Integer severityNumber;
    /** 日志正文（结构化 body 会序列化成 JSON 字符串） */
    private String body;
    private String traceId;
    private String spanId;
    /**
     * 日志所属 trace 是否被采样（{@code TraceFlags} 的 bit0，W3C sampled）。
     * 为 {@code false} 时 traceId 只存在于日志里，Tempo 中没有对应 trace（点过去会 404）；
     * {@code null} = 文档没有该字段（老索引），前端按未知处理。
     */
    private Boolean sampled;
    private String service;
    private String cluster;
    private String namespace;
    private String pod;
    /** deployment / statefulset / daemonset 里第一个非空的 */
    private String workload;
    /** Attributes.* 展平后的键值（详情面板展示用） */
    private Map<String, String> attributes;
    /** ES 的 sort 值，下一页用 search_after 原样回传 */
    private List<Object> sort;
}
