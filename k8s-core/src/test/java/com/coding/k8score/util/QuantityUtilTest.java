package com.coding.k8score.util;

import io.fabric8.kubernetes.api.model.Quantity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QuantityUtilTest {

    @Test
    void toBase_cpu_cores() {
        assertThat(QuantityUtil.toBase(new Quantity("7920m"))).isEqualByComparingTo("7.92");
        assertThat(QuantityUtil.toBase(new Quantity("8"))).isEqualByComparingTo("8");
        assertThat(QuantityUtil.toBase(new Quantity("0.5"))).isEqualByComparingTo("0.5");
    }

    @Test
    void toBase_memory_bytes() {
        assertThat(QuantityUtil.toBase(new Quantity("16Gi"))).isEqualByComparingTo("17179869184");
        assertThat(QuantityUtil.toBase(new Quantity("512Mi"))).isEqualByComparingTo("536870912");
    }

    @Test
    void toBase_null() {
        assertThat(QuantityUtil.toBase(null)).isNull();
    }

    @Test
    void fromBase_roundTrips_baseUnits() {
        // 写回裸基础单位：值精确往返，且是合法 Quantity
        assertThat(QuantityUtil.fromBase(new BigDecimal("0.5")).getNumericalAmount()).isEqualByComparingTo("0.5");
        assertThat(QuantityUtil.fromBase(new BigDecimal("17179869184")).getNumericalAmount())
                .isEqualByComparingTo("17179869184");
    }

    @Test
    void fromBase_usesPlainString_noScientificNotation() {
        // 大数值不能用 toString()（会出 1E+10），必须 toPlainString()
        Quantity q = QuantityUtil.fromBase(new BigDecimal("17179869184"));
        assertThat(q.toString()).doesNotContain("E");
    }

    @Test
    void fromBase_null() {
        assertThat(QuantityUtil.fromBase(null)).isNull();
    }

    @Test
    void maps_roundTrip() {
        Map<String, Quantity> q = new LinkedHashMap<>();
        q.put("cpu", new Quantity("7920m"));
        q.put("memory", new Quantity("16Gi"));
        Map<String, BigDecimal> base = QuantityUtil.toBaseMap(q);
        assertThat(base.get("cpu")).isEqualByComparingTo("7.92");
        assertThat(base.get("memory")).isEqualByComparingTo("17179869184");

        Map<String, Quantity> back = QuantityUtil.fromBaseMap(base);
        assertThat(back.get("cpu").getNumericalAmount()).isEqualByComparingTo("7.92");
        assertThat(back.get("memory").getNumericalAmount()).isEqualByComparingTo("17179869184");
    }

    @Test
    void maps_null() {
        assertThat(QuantityUtil.toBaseMap(null)).isNull();
        assertThat(QuantityUtil.fromBaseMap(null)).isNull();
    }
}
