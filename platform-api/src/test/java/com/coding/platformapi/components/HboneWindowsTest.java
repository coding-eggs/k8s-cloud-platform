package com.coding.platformapi.components;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HboneWindowsTest {

    private static final long MIB = HboneWindows.MIB;

    private static Map<String, Object> ztunnel(Object streams, Object stream, Object connection) {
        java.util.Map<String, Object> env = new java.util.LinkedHashMap<>();
        if (streams != null) env.put("POOL_MAX_STREAMS_PER_CONNECTION", streams);
        if (stream != null) env.put("HTTP2_STREAM_WINDOW_SIZE", stream);
        if (connection != null) env.put("HTTP2_CONNECTION_WINDOW_SIZE", connection);
        return Map.of("env", env);
    }

    private static Map<String, Object> istiod(Object stream, Object connection) {
        java.util.Map<String, Object> env = new java.util.LinkedHashMap<>();
        env.put("PILOT_ENABLE_AMBIENT", "true");
        if (stream != null) env.put("PILOT_HBONE_INITIAL_STREAM_WINDOW_SIZE", stream);
        if (connection != null) env.put("PILOT_HBONE_INITIAL_CONNECTION_WINDOW_SIZE", connection);
        return Map.of("pilot", Map.of("env", env));
    }

    @Test
    void ztunnelAcceptsConnectionWindowLargerThanAllStreamsTogether() {
        assertThat(HboneWindows.violation("ztunnel",
                ztunnel("16", String.valueOf(4 * MIB), String.valueOf(128 * MIB)))).isNull();
    }

    @Test
    void ztunnelRejectsTheIstioDefaultsThatStalledMovie() {
        // 100 × 4MiB 远超 16MiB：2026-11-12 实测第 4 条暂停的流就卡死整条连接
        assertThat(HboneWindows.violation("ztunnel",
                ztunnel("100", String.valueOf(4 * MIB), String.valueOf(16 * MIB))))
                .contains("必须小于整条连接的缓冲上限")
                .contains("100 × 4MiB = 400MiB");
    }

    @Test
    void ztunnelRejectsEqualProductBecauseThePausedStreamsWouldStillFillIt() {
        assertThat(HboneWindows.violation("ztunnel",
                ztunnel("16", String.valueOf(4 * MIB), String.valueOf(64 * MIB)))).isNotNull();
    }

    @Test
    void ztunnelRejectsOutOfRangeStreamCount() {
        assertThat(HboneWindows.violation("ztunnel",
                ztunnel("0", String.valueOf(4 * MIB), String.valueOf(128 * MIB)))).contains("1 到 1000");
        assertThat(HboneWindows.violation("ztunnel",
                ztunnel("abc", String.valueOf(4 * MIB), String.valueOf(128 * MIB)))).contains("1 到 1000");
    }

    @Test
    void windowsMustBeIntegersWithinHttp2Range() {
        assertThat(HboneWindows.violation("ztunnel",
                ztunnel("16", "1.5", String.valueOf(128 * MIB)))).contains("64KiB 到 2047MiB");
        assertThat(HboneWindows.violation("ztunnel",
                ztunnel("16", String.valueOf(4 * MIB), String.valueOf(4096 * MIB)))).contains("64KiB 到 2047MiB");
        assertThat(HboneWindows.violation("istiod",
                istiod("1024", String.valueOf(256 * MIB)))).contains("64KiB 到 2047MiB");
    }

    @Test
    void istiodNeedsConnectionWindowAtLeastSixteenTimesTheStreamWindow() {
        assertThat(HboneWindows.violation("istiod",
                istiod(String.valueOf(MIB), String.valueOf(256 * MIB)))).isNull();
        assertThat(HboneWindows.violation("istiod",
                istiod(String.valueOf(MIB), String.valueOf(16 * MIB)))).isNull();
        // Envoy 默认（16MiB / 24MiB）：几次中断的下载就卡死连接
        assertThat(HboneWindows.violation("istiod",
                istiod(String.valueOf(16 * MIB), String.valueOf(24 * MIB))))
                .contains("至少要是单个请求的 16 倍");
    }

    @Test
    void numbersAreAcceptedAsWellAsStrings() {
        assertThat(HboneWindows.violation("ztunnel", ztunnel(16, 4 * MIB, 128 * MIB))).isNull();
    }

    @Test
    void missingValuesAndOtherReleasesAreNotChecked() {
        assertThat(HboneWindows.violation("ztunnel", Map.of())).isNull();
        assertThat(HboneWindows.violation("istiod", istiod(null, null))).isNull();
        assertThat(HboneWindows.violation("external-gateway",
                ztunnel("100", String.valueOf(4 * MIB), String.valueOf(16 * MIB)))).isNull();
    }
}
