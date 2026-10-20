package com.coding.platformapi.components;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Thanos compactor 的保留期下限（照抄 thanos v0.42.4 `cmd/thanos/compact.go`）。
 *
 * <p>2026-10-20 实锤：表单里把三档都填成 1d，Helm 装成功、compactor 却 CrashLoopBackOff：
 * {@code raw resolution must be higher than the minimum block size after which 5m resolution
 * downsampling will occur (40 hours)}。这类组合必须在下发 helm 之前就拒掉。
 */
class ThanosRetentionTest {

    private static Map<String, Object> retention(String raw, String fiveMinutes, String oneHour) {
        Map<String, Object> retention = new LinkedHashMap<>();
        retention.put("resolutionRaw", raw);
        retention.put("resolution5m", fiveMinutes);
        retention.put("resolution1h", oneHour);
        Map<String, Object> compactor = new LinkedHashMap<>();
        compactor.put("retention", retention);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("compactor", compactor);
        return values;
    }

    @Test
    void chartDefaultsAreAccepted() {
        assertThat(ThanosRetention.violation(retention("30d", "90d", "365d"))).isNull();
    }

    @Test
    void rawRetentionBelowFortyHoursIsRejected() {
        // 用户实测的坏配置：三档全 1d
        String problem = ThanosRetention.violation(retention("1d", "1d", "1d"));

        assertThat(problem).contains("40h").contains("1d");
    }

    @Test
    void rawRetentionAtTheBoundaryIsAccepted() {
        // Thanos 的判据是 "< 40h"，正好 40h 合法
        assertThat(ThanosRetention.violation(retention("40h", "", ""))).isNull();
    }

    @Test
    void fiveMinuteRetentionBelowTenDaysIsRejected() {
        String problem = ThanosRetention.violation(retention("30d", "1d", "365d"));

        assertThat(problem).contains("10d").contains("1d");
    }

    @Test
    void fiveMinuteRetentionAtTheBoundaryIsAccepted() {
        assertThat(ThanosRetention.violation(retention("30d", "10d", "365d"))).isNull();
    }

    @Test
    void oneHourRetentionHasNoLowerBound() {
        assertThat(ThanosRetention.violation(retention("30d", "90d", "1h"))).isNull();
    }

    @Test
    void emptyValuesMeanTheChartDefaultAndAreAccepted() {
        // 留空 = 不写这个 key（chart 默认 30d/90d/365d）
        assertThat(ThanosRetention.violation(retention("", "", ""))).isNull();
        assertThat(ThanosRetention.violation(Map.of())).isNull();
    }

    @Test
    void unparsableDurationIsRejectedWithTheFieldName() {
        String problem = ThanosRetention.violation(retention("30", "90d", "365d"));

        assertThat(problem).contains("30").contains("resolutionRaw");
    }

    @Test
    void parsesPrometheusDurationSyntax() {
        assertThat(ThanosRetention.parse("30d")).isEqualTo(Duration.ofDays(30));
        assertThat(ThanosRetention.parse("12h")).isEqualTo(Duration.ofHours(12));
        assertThat(ThanosRetention.parse("1h30m")).isEqualTo(Duration.ofMinutes(90));
        assertThat(ThanosRetention.parse("1w")).isEqualTo(Duration.ofDays(7));
        assertThat(ThanosRetention.parse("1y")).isEqualTo(Duration.ofDays(365));
        assertThat(ThanosRetention.parse("500ms")).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void rejectsOutOfOrderOrRepeatedUnits() {
        // Prometheus 的写法要求单位从大到小、每个单位最多一次；"30d1d" / "1h1h" 都不是合法时长
        assertThat(ThanosRetention.parse("1d30d")).isNull();
        assertThat(ThanosRetention.parse("1h1h")).isNull();
        assertThat(ThanosRetention.parse("30")).isNull();
        assertThat(ThanosRetention.parse("-1d")).isNull();
    }
}
