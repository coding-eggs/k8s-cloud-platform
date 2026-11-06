package com.coding.platformapi.models.overview;

import java.util.Map;

/** 数量卡的数据：总数 + 按状态拆分（键序固定，值为 0 的键也保留，前端按需隐藏）。 */
public record CountBreakdown(long total, Map<String, Long> byStatus) {
}
