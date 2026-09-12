package com.coding.platformapi.metrics.model;

import lombok.Data;

import java.util.List;

/** data.result[]：{@code { metric, value?, values? }}。
 * <p>value/values 元素是 Prometheus 的 {@code [timestamp, "stringValue"]} 数组（字符串承载，避免精度问题）。
 * 即时查询有 value、区间查询有 values；本功能只读 values。 */
@Data
public class PromResult<M> {
    /** 标签 map；纯 sum()（无 by）时为空 {} */
    private M metric;
    /** 即时查询：[ts, "val"] */
    private List<String> value;
    /** 区间查询：[[ts, "val"], ...] */
    private List<List<String>> values;
}
