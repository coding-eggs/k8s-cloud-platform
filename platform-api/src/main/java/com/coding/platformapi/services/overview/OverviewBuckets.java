package com.coding.platformapi.services.overview;

import com.coding.data.models.system.StatusCount;
import com.coding.platformapi.models.overview.CountBreakdown;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * 概览的状态归类口径（spec §4）：库里 / 集群里的原始状态 → 卡片上的几档。纯函数，归类只在这里写一处。
 */
public final class OverviewBuckets {

    private OverviewBuckets() {
    }

    /** READY→RUNNING、DEPLOYING、DRAFT→UNRELEASED、FAILED、OFFLINE；DELETING 与未知不计 */
    public static CountBreakdown applications(List<StatusCount> rows) {
        return bucket(rows, List.of("RUNNING", "DEPLOYING", "UNRELEASED", "FAILED", "OFFLINE"), s -> {
            if (s == null) {
                return null;
            }
            return switch (s) {
                case "READY" -> "RUNNING";
                case "DEPLOYING" -> "DEPLOYING";
                case "DRAFT" -> "UNRELEASED";
                case "FAILED" -> "FAILED";
                case "OFFLINE" -> "OFFLINE";
                default -> null;
            };
        });
    }

    /** 每条流水线最近一次运行：TIMEOUT 并入 FAILED，从没跑过（null）= NEVER */
    public static CountBreakdown pipelines(List<StatusCount> rows) {
        return bucket(rows, List.of("SUCCEEDED", "FAILED", "RUNNING", "CANCELLED", "NEVER"), s -> {
            if (s == null) {
                return "NEVER";
            }
            return switch (s) {
                case "SUCCEEDED" -> "SUCCEEDED";
                case "FAILED", "TIMEOUT" -> "FAILED";
                case "RUNNING" -> "RUNNING";
                case "CANCELLED" -> "CANCELLED";
                default -> null;
            };
        });
    }

    /** 触发中告警按级别：critical / warning，其余（含缺省）归 OTHER */
    public static CountBreakdown alerts(List<StatusCount> rows) {
        return bucket(rows, List.of("CRITICAL", "WARNING", "OTHER"), s -> {
            String v = s == null ? "" : s.toLowerCase(Locale.ROOT);
            return switch (v) {
                case "critical" -> "CRITICAL";
                case "warning" -> "WARNING";
                default -> "OTHER";
            };
        });
    }

    /** 中间件实时状态：READY→NORMAL、DEGRADED/FAILED→ABNORMAL、CREATING/DELETING→PROCESSING */
    public static CountBreakdown middleware(List<String> statuses) {
        // 有实例的实时状态读不到 → 整块不可用（null，前端「—」），不拿残缺数冒充全量
        if (statuses.contains("UNKNOWN")) {
            return null;
        }
        Map<String, Long> by = keys(List.of("NORMAL", "ABNORMAL", "PROCESSING"));
        long total = 0;
        for (String s : statuses) {
            String k = middlewareKey(s);
            if (k != null) {
                by.merge(k, 1L, Long::sum);
                total++;
            }
        }
        return new CountBreakdown(total, by);
    }

    /** 中间件台账 / 实时状态 → 总览桶（NORMAL / ABNORMAL / PROCESSING）；不认识的 → null（不计）。 */
    public static String middlewareKey(String status) {
        return status == null ? null : switch (status) {
            case "READY" -> "NORMAL";
            case "DEGRADED", "FAILED" -> "ABNORMAL";
            case "CREATING", "DELETING" -> "PROCESSING";
            default -> null;
        };
    }

    /** 多个范围（集群）的结果相加；null（该范围取不到）跳过；<b>全部</b>取不到 → null（不是 0）。空列表 → 0。 */
    public static CountBreakdown sum(List<CountBreakdown> parts) {
        if (!parts.isEmpty() && parts.stream().allMatch(java.util.Objects::isNull)) {
            return null;
        }
        Map<String, Long> by = new LinkedHashMap<>();
        long total = 0;
        for (CountBreakdown p : parts) {
            if (p == null) {
                continue;
            }
            total += p.total();
            p.byStatus().forEach((k, v) -> by.merge(k, v, Long::sum));
        }
        return new CountBreakdown(total, by);
    }

    private static CountBreakdown bucket(List<StatusCount> rows, List<String> order, Function<String, String> map) {
        Map<String, Long> by = keys(order);
        long total = 0;
        for (StatusCount r : rows) {
            String k = map.apply(r.getStatus());
            if (k != null) {
                by.merge(k, r.getCount(), Long::sum);
                total += r.getCount();
            }
        }
        return new CountBreakdown(total, by);
    }

    private static Map<String, Long> keys(List<String> order) {
        Map<String, Long> by = new LinkedHashMap<>();
        order.forEach(k -> by.put(k, 0L));
        return by;
    }
}
