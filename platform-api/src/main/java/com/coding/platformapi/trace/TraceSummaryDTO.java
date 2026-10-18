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
}
