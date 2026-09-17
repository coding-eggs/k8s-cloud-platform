package com.coding.k8score.util;

import io.fabric8.kubernetes.api.model.Quantity;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * fabric8 {@link Quantity} ⇄ 基础单位 {@link BigDecimal} 转换。
 * <p>约定：CPU=核数（"7920m"→7.92），内存/存储=字节（"16Gi"→17179869184）。
 * 读用 {@link Quantity#getNumericalAmount()}；写回用裸基础单位数字（{@code new Quantity(bd.toPlainString())}），
 * 总是合法且精确、无舍入。全程 null-safe。
 */
public final class QuantityUtil {

    private QuantityUtil() {}

    /** Quantity → 基础单位 BigDecimal；null 入参返回 null。 */
    public static BigDecimal toBase(Quantity q) {
        return q == null ? null : q.getNumericalAmount();
    }

    /** 基础单位 BigDecimal → Quantity（裸基础单位数字）；null 入参返回 null。 */
    public static Quantity fromBase(BigDecimal bd) {
        return bd == null ? null : new Quantity(bd.toPlainString());
    }

    public static Map<String, BigDecimal> toBaseMap(Map<String, Quantity> m) {
        if (m == null) return null;
        Map<String, BigDecimal> r = new LinkedHashMap<>();
        for (Map.Entry<String, Quantity> e : m.entrySet())
            r.put(e.getKey(), toBase(e.getValue()));
        return r;
    }

    public static Map<String, Quantity> fromBaseMap(Map<String, BigDecimal> m) {
        if (m == null) return null;
        Map<String, Quantity> q = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : m.entrySet())
            q.put(e.getKey(), fromBase(e.getValue()));
        return q;
    }
}
