package com.coding.platformapi.metrics.model;

import lombok.Data;

/** Prometheus / Thanos HTTP API 响应外层（envelope）：{@code { status, data, errorType?, error? }}。
 *  默认命名（resultType/errorType/status/data 均为 camelCase），不套全局 snake_case。 */
@Data
public class PromApiResponse<T> {
    /** "success" | "error" */
    private String status;

    private T data;

    /** 仅 error：错误类别（bad_data / execution / internal …） */
    private String errorType;

    /** 仅 error：错误详情 */
    private String error;
}
