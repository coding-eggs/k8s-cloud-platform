package com.coding.platformapi.models;

import java.util.Map;

/**
 * 灰度面板指标：两个版本各一组即时值，查不到为 null（前端显示「暂无数据」）。
 * HTTP：rps（请求/秒）、errorRate（5xx 占比 0-1）、p50 / p99（毫秒）；TCP：cps（新建连接/秒）、bytes（收发字节/秒）。
 */
public record AppCanaryMetricsView(String window, String protocol, Map<String, Double> stable,
                                   Map<String, Double> canary) {
}
