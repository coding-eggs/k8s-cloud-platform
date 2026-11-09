package com.coding.platformapi.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 入口灰度在发布快照里的形状：{@code services.<key>.canary} 块（设计 {@code 2026-11-09-app-canary-release-design.md} §4.1）。
 *
 * <pre>{"desired":{...canary 的完整期望配置...},"replicas":1,"weight":10,"header":{"name":"x-canary","value":"true"},
 *  "external":true}</pre>
 *
 * {@code external}（内部灰度设计 {@code 2026-11-09-app-internal-canary-design.md} §4.1）：网关进来的流量是否参与灰度；
 * 旧块没有这个字段按 true（与入口灰度行为一致）。
 *
 * 线上快照里某服务带这个块 = 该服务正在灰度；不单独存状态。服务台账 {@code desired} 始终是 stable，不含此块。
 * 纯函数，不改入参。
 */
public final class AppCanary {

    public static final String KEY = "canary";
    /** canary 节点 / 对象名的后缀：节点 {@code <key>-canary}，对象 {@code <slug>-<key>-canary} */
    public static final String SUFFIX = "-canary";

    public static final int DEFAULT_REPLICAS = 1;
    public static final int DEFAULT_WEIGHT = 10;
    public static final String DEFAULT_HEADER_NAME = "x-canary";
    public static final String DEFAULT_HEADER_VALUE = "true";

    /** header 为 null = 不开 header 直达（TCP 恒为 null）；external = 网关流量参与灰度 */
    public record Spec(Map<String, Object> desired, int replicas, int weight, Header header, boolean external) {
        /** external 缺省 true（入口灰度的语义） */
        public Spec(Map<String, Object> desired, int replicas, int weight, Header header) {
            this(desired, replicas, weight, header, true);
        }
    }

    public record Header(String name, String value) {
    }

    private AppCanary() {
    }

    /** 服务快照里的灰度块；没有返回 null */
    @SuppressWarnings("unchecked")
    public static Spec of(Map<String, Object> serviceSnapshot) {
        if (serviceSnapshot == null || !(serviceSnapshot.get(KEY) instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> c = (Map<String, Object>) raw;
        Map<String, Object> desired = c.get("desired") instanceof Map<?, ?> d
                ? new LinkedHashMap<>((Map<String, Object>) d) : new LinkedHashMap<>();
        Header header = null;
        if (c.get("header") instanceof Map<?, ?> h && h.get("name") != null) {
            header = new Header(String.valueOf(h.get("name")),
                    h.get("value") == null ? "" : String.valueOf(h.get("value")));
        }
        return new Spec(desired, intOr(c.get("replicas"), DEFAULT_REPLICAS), intOr(c.get("weight"), DEFAULT_WEIGHT),
                header, !Boolean.FALSE.equals(c.get("external")));
    }

    /** stable 原样 + 灰度块（新 map） */
    public static Map<String, Object> with(Map<String, Object> stable, Spec spec) {
        Map<String, Object> out = without(stable);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("desired", new LinkedHashMap<>(spec.desired()));
        c.put("replicas", spec.replicas());
        c.put("weight", spec.weight());
        if (spec.header() == null) {
            c.put("header", null);
        } else {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("name", spec.header().name());
            h.put("value", spec.header().value());
            c.put("header", h);
        }
        c.put("external", spec.external());
        out.put(KEY, c);
        return out;
    }

    /** 去掉灰度块（新 map） */
    public static Map<String, Object> without(Map<String, Object> serviceSnapshot) {
        Map<String, Object> out = new LinkedHashMap<>(serviceSnapshot == null ? Map.of() : serviceSnapshot);
        out.remove(KEY);
        return out;
    }

    /** 所有服务去掉灰度块（回滚用：回滚只还原 stable） */
    public static AppSnapshot strip(AppSnapshot s) {
        Map<String, Map<String, Object>> services = new TreeMap<>();
        s.services().forEach((k, v) -> services.put(k, without(v)));
        return new AppSnapshot(services, s.dependencies(), s.app());
    }

    /** 正在灰度的服务 key，按字母序 */
    public static List<String> activeKeys(AppSnapshot s) {
        List<String> out = new ArrayList<>();
        new TreeMap<>(s.services()).forEach((k, v) -> {
            if (of(v) != null) {
                out.add(k);
            }
        });
        return out;
    }

    public static String nodeKey(String key) {
        return key + SUFFIX;
    }

    private static int intOr(Object o, int dflt) {
        return o instanceof Number n ? n.intValue() : dflt;
    }
}
