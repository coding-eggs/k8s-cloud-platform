package com.coding.platformapi.components;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thanos compactor 保留期的下限（照抄 thanos v0.42.4 {@code cmd/thanos/compact.go}）。
 *
 * <p><b>为什么要在下发 helm 之前校验</b>：违反这些下限时 Helm 安装本身是成功的，但 compactor
 * 容器**启动即退出**（CrashLoopBackOff），平台却会记一条 SUCCESS —— 2026-10-20 用户把三档
 * 保留期都填成 {@code 1d} 就是这么撞上的：
 * {@code raw resolution must be higher than the minimum block size after which 5m resolution
 * downsampling will occur (40 hours)}。
 *
 * <p>规则（降采样默认开启，本平台不暴露 {@code --disable-downsampling}）：
 * <ul>
 *   <li>{@code resolutionRaw} 非 0 时必须 ≥ <b>40h</b>，否则 5m 降采样永远不会发生；</li>
 *   <li>{@code resolution5m} 非 0 时必须 ≥ <b>10d</b>，否则 1h 降采样永远不会发生；</li>
 *   <li>{@code resolution1h} 无下限。</li>
 * </ul>
 * 空值表示不写这个 key（chart 默认 30d / 90d / 365d），不触发检查。
 */
public final class ThanosRetention {

    /** raw 分辨率的下限：5m 降采样要在原始块满 40h 后才会发生 */
    static final Duration MIN_RAW = Duration.ofHours(40);
    /** 5m 分辨率的下限：1h 降采样要在 5m 块满 10 天后才会发生 */
    static final Duration MIN_5M = Duration.ofDays(10);

    /** Prometheus/Thanos 时长：数字 + 单位，可多段（如 1h30m） */
    private static final Pattern SEGMENT = Pattern.compile("(\\d+)(ms|s|m|h|d|w|y)");

    private ThanosRetention() {
    }

    /**
     * 检查一个 release values 里的 {@code compactor.retention.*}。
     *
     * @return 合法（或没有 retention 配置）返回 {@code null}；否则返回可读的拒绝原因
     */
    public static String violation(Map<String, Object> values) {
        Map<?, ?> retention = retentionOf(values);
        if (retention == null) {
            return null;
        }
        String raw = text(retention.get("resolutionRaw"));
        String fiveMinutes = text(retention.get("resolution5m"));
        String oneHour = text(retention.get("resolution1h"));

        String problem = check(raw, "resolutionRaw", "原始数据保留期", MIN_RAW,
                "Thanos 需要保留 40 小时以上的原始块才能做 5m 降采样");
        if (problem != null) {
            return problem;
        }
        problem = check(fiveMinutes, "resolution5m", "5m 降采样保留期", MIN_5M,
                "Thanos 需要保留 10 天以上的 5m 块才能做 1h 降采样");
        if (problem != null) {
            return problem;
        }
        // 1h 没有下限，只要求能解析（写错了同样会让 compactor 起不来）
        return check(oneHour, "resolution1h", "1h 降采样保留期", null, null);
    }

    private static String check(String value, String field, String label, Duration minimum, String why) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Duration parsed = parse(value);
        if (parsed == null) {
            return "「" + label + "」（compactor.retention." + field + "）不是合法的时长：" + value
                    + "（写法如 30d / 12h / 1h30m，单位从大到小）";
        }
        if (minimum != null && parsed.compareTo(minimum) < 0) {
            return "「" + label + "」不能小于 " + human(minimum) + "（收到 " + value + "）："
                    + why + "，否则 compactor 启动即崩溃；留空 = 不改动（首次安装用默认值）";
        }
        return null;
    }

    private static String human(Duration d) {
        // 40h 不能写成 "1d"（toDays() 是截断的）：整天数才用 d，否则用 h
        return d.toHours() % 24 == 0 ? d.toDays() + "d" : d.toHours() + "h";
    }

    /**
     * 解析 Prometheus/Thanos 时长（{@code 30d}、{@code 12h}、{@code 1h30m}）。
     *
     * <p>与 prometheus/common 的 {@code model.ParseDuration} 一致：单位从大到小、每个单位最多一次、
     * 至少要有一段。
     *
     * @return 解析结果；写法不合法返回 {@code null}
     */
    static Duration parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = text.trim();
        // 单位权重：必须严格递减（1h30m 合法，30m1h 不合法）
        int previousRank = Integer.MAX_VALUE;
        int position = 0;
        long millis = 0;
        Matcher m = SEGMENT.matcher(s);
        while (m.find()) {
            if (m.start() != position) {
                return null;
            }
            int rank = rank(m.group(2));
            if (rank >= previousRank) {
                return null;
            }
            previousRank = rank;
            millis += Long.parseLong(m.group(1)) * unitMillis(m.group(2));
            position = m.end();
        }
        return position == s.length() && position > 0 ? Duration.ofMillis(millis) : null;
    }

    private static int rank(String unit) {
        return switch (unit) {
            case "y" -> 6;
            case "w" -> 5;
            case "d" -> 4;
            case "h" -> 3;
            case "m" -> 2;
            case "s" -> 1;
            default -> 0; // ms
        };
    }

    private static long unitMillis(String unit) {
        return switch (unit) {
            case "y" -> Duration.ofDays(365).toMillis();
            case "w" -> Duration.ofDays(7).toMillis();
            case "d" -> Duration.ofDays(1).toMillis();
            case "h" -> Duration.ofHours(1).toMillis();
            case "m" -> Duration.ofMinutes(1).toMillis();
            case "s" -> Duration.ofSeconds(1).toMillis();
            default -> 1L; // ms
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> retentionOf(Map<String, Object> values) {
        if (values == null) {
            return null;
        }
        Object compactor = values.get("compactor");
        if (!(compactor instanceof Map<?, ?> c)) {
            return null;
        }
        Object retention = c.get("retention");
        return retention instanceof Map<?, ?> r ? r : null;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
