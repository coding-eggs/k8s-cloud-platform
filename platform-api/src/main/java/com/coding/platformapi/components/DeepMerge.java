package com.coding.platformapi.components;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * values 的深合并（S3.6 批②）。
 *
 * <p>为什么不能用 {@code putAll}：契约值是嵌套的
 * （{@code prometheus.prometheusSpec.externalLabels.cluster_name}）。浅合并会把整个
 * {@code prometheus} 子树替换掉 —— 升级时用户的 {@code retention} 会把契约里的
 * selector 开关一起抹掉，症状是"升级之后告警全没了"。
 *
 * <p>List 整体替换（不做按下标合并）：Helm values 里 list 的语义就是整体覆盖。
 */
public final class DeepMerge {

    private DeepMerge() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> overlay) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        overlay.forEach((key, value) -> {
            Object existing = out.get(key);
            if (existing instanceof Map<?, ?> baseMap && value instanceof Map<?, ?> overlayMap) {
                out.put(key, merge((Map<String, Object>) baseMap, (Map<String, Object>) overlayMap));
            } else {
                out.put(key, value);
            }
        });
        return out;
    }
}
