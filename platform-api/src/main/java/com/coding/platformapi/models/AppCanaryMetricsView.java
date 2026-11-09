package com.coding.platformapi.models;

import java.util.Map;

/**
 * 灰度面板指标：按来源分两组（内部灰度设计 §6），每组两个版本各一组即时值，查不到为 null（前端显示「暂无数据」）。
 * HTTP：rps（请求/秒）、errorRate（5xx 占比 0-1）、p50 / p99（毫秒）；TCP：cps（新建连接/秒）、bytes（收发字节/秒）。
 *
 * @param internal 经 waypoint 的集群内调用；应用线上设置非 L7 网格时为 null
 * @param external 网关进来的；服务没有网关暴露时为 null
 */
public record AppCanaryMetricsView(String window, String protocol, Group internal, Group external) {

    public record Group(Map<String, Double> stable, Map<String, Double> canary) {
    }
}
