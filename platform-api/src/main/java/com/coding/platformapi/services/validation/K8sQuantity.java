package com.coding.platformapi.services.validation;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;

import java.math.BigDecimal;
import java.util.Map;

/**K8s quantity 严格解析：整数/小数 + SI(m/k/M/G/T/P/E) / 二进制(Ki/Mi/Gi/Ti/Pi/Ei) 后缀 → 基础单位 BigDecimal */
public final class K8sQuantity {

    private static final Map<String, Integer> SI = Map.of("m", -3, "k", 3, "M", 6, "G", 9, "T", 12, "P", 15, "E", 18);
    private static final Map<String, Integer> BIN = Map.of("Ki", 10, "Mi", 20, "Gi", 30, "Ti", 40, "Pi", 50, "Ei", 60);

    private K8sQuantity() {}

    public static BigDecimal parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "资源量不能为空");
        }
        String s = raw.trim();
        int i = 0;
        boolean neg = false;
        if (s.charAt(0) == '-') { neg = true; i++; }
        else if (s.charAt(0) == '+') { i++; }
        int start = i;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
        if (i == start) throw new CloudPlatformException(EnumResponseType.ERROR, "无法解析资源量: " + raw);
        BigDecimal base = new BigDecimal(s.substring(start, i));
        String suffix = s.substring(i).trim();
        BigDecimal factor;
        if (suffix.isEmpty()) factor = BigDecimal.ONE;
        else if (SI.containsKey(suffix)) factor = BigDecimal.TEN.pow(SI.get(suffix));
        else if (BIN.containsKey(suffix)) factor = BigDecimal.valueOf(2).pow(BIN.get(suffix));
        else throw new CloudPlatformException(EnumResponseType.ERROR, "无法解析资源量: " + raw);
        return neg ? base.multiply(factor).negate() : base.multiply(factor);
    }

    /**比较 a ≤ b；任一无法解析抛错（由 parse 抛出） */
    public static int compare(String a, String b) {
        return parse(a).compareTo(parse(b));
    }
}
