package com.coding.platformapi.components;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * recipe values 的占位符替换（S3.6 批②）。
 *
 * <p>存在的理由：有些契约值按集群变化，写不进静态目录 ——
 * {@code prometheus.prometheusSpec.externalLabels.cluster_name} 是集群名，
 * {@code remoteWrite[0].url} 是全局配置里的 Thanos receive 地址。
 *
 * <p><b>解析失败必须报错</b>：尤其是 URL 解析成空串 —— 装出来的 Prometheus 永远上报不了，
 * 而 helm 会显示安装成功、Pod 全绿，运行时也没有任何报错。这类"装上了但功能不生效"
 * 正是本批最想避免的。
 */
public final class ValueTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-zA-Z0-9._-]+)}");

    private ValueTemplate() {
    }

    /** 递归替换 Map / List / String 里的占位符；其余类型原样返回 */
    public static Object resolve(Object value, Map<String, String> context) {
        return resolve(value, context, false);
    }

    /**
     * @param preserveUnknown 未知占位符是否原样保留。只有 Tempo 这类把凭证占位符留给运行时
     *                        {@code -config.expand-env} 展开的 release 才使用；已知占位符为空仍报错。
     */
    public static Object resolve(Object value, Map<String, String> context, boolean preserveUnknown) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), resolve(v, context, preserveUnknown)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(resolve(item, context, preserveUnknown));
            }
            return out;
        }
        if (value instanceof String template) {
            return resolveString(template, context, preserveUnknown);
        }
        return value;
    }

    private static String resolveString(String template, Map<String, String> context, boolean preserveUnknown) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = context.get(key);
            if (replacement == null) {
                if (preserveUnknown) {
                    // 留给运行时展开（如 Tempo 的 ${MINIO_ACCESS_KEY}）
                    matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                    continue;
                }
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "values 引用了未配置的占位符：" + key);
            }
            if (replacement.isBlank()) {
                // 空值一律失败：空 remoteWrite url 的 Prometheus 永远上报不了、空 CIDR 的 Calico 会断网，
                // 而 helm 都会显示"安装成功"，运行时也没有任何报错
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "values 占位符解析为空：" + key + "（请配置对应的平台参数）");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
