package com.coding.platformapi.trace;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** 归一化后的 span（从 Tempo 的 OTLP JSON 拍平）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SpanDTO {
    private String spanId;
    private String parentSpanId;
    private String name;
    /** resource.service.name */
    private String serviceName;
    /** resource.cluster.name（gateway 统一 upsert） */
    private String clusterName;
    private String namespace;
    private String startTimeUnixNano;
    private String endTimeUnixNano;
    private Long durationMs;
    private String statusCode;
    private Map<String, String> attributes;
}
