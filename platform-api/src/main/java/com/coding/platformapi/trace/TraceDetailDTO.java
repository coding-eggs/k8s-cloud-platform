package com.coding.platformapi.trace;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 一条 trace 的全部 span（按开始时间升序）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TraceDetailDTO {
    private String traceId;
    private List<SpanDTO> spans;
}
