package com.coding.platformapi.trace;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Tempo /api/search 的一条结果。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TraceSummaryDTO {
    private String traceId;
    private String rootService;
    private String rootName;
    private String startTimeUnixNano;
    private Long durationMs;
    /** 这条 trace 涉及的服务数（Tempo serviceStats 的条目数） */
    private Integer serviceCount;
    /** span 总数（Tempo serviceStats 里各服务 spanCount 之和） */
    private Integer spanCount;
}
