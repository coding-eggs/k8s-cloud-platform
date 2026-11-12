package com.coding.platformapi.components;

import java.util.Map;

/**
 * 网格 HBONE 隧道（HTTP/2 多路复用）的缓冲窗口：可编辑字段的 key 与取值约束。
 *
 * <p><b>为什么要约束</b>（2026-11-12 真集群实测，movie 看视频一会儿全站不可访问）：
 * 一条 HBONE 连接上跑着很多请求，暂停（播放器缓冲够了）或被中断后泄漏的请求会各自占住
 * 「单个请求的缓冲上限」那么多的连接额度；占满「整条连接的缓冲上限」后，这条连接上的
 * 其它请求全部挂起。所以两层都必须让连接额度明显大于单个请求能占的量：
 * <ul>
 *   <li><b>ztunnel</b>（Pod 之间）：每条连接最多 N 个请求，要求 {@code N × 单请求上限 < 连接上限}，
 *       这样即使 N 个请求全部暂停也挤不死别人；</li>
 *   <li><b>waypoint / 入口网关</b>（Envoy）：中断的下载会泄漏 1 小时（istio/istio#62641），
 *       数量没有上界，只能要求连接上限至少是单请求上限的 {@value #ENVOY_MIN_RATIO} 倍。</li>
 * </ul>
 *
 * <p>值在 values 里是<b>字节数的字符串</b>（chart 把 env 写成 {@code value: "{{ $val }}"}，
 * 数字会被 Helm 渲染成科学计数法）；表单按 MiB 展示。
 */
public final class HboneWindows {

    public static final long MIB = 1024L * 1024;

    /** istiod → waypoint / 入口网关的 connect_originate（Istio 1.31 的 feature flag） */
    public static final String ISTIOD_STREAM_KEY = "pilot.env.PILOT_HBONE_INITIAL_STREAM_WINDOW_SIZE";
    public static final String ISTIOD_CONNECTION_KEY = "pilot.env.PILOT_HBONE_INITIAL_CONNECTION_WINDOW_SIZE";

    /** ztunnel 自身（ztunnel 1.31 的 env） */
    public static final String ZTUNNEL_STREAMS_KEY = "env.POOL_MAX_STREAMS_PER_CONNECTION";
    public static final String ZTUNNEL_STREAM_KEY = "env.HTTP2_STREAM_WINDOW_SIZE";
    public static final String ZTUNNEL_CONNECTION_KEY = "env.HTTP2_CONNECTION_WINDOW_SIZE";

    public static final String ENVOY_GROUP = "网格传输：入口网关与 waypoint";
    public static final String ZTUNNEL_GROUP = "网格传输：Pod 之间（ztunnel）";

    /** HTTP/2 窗口的合法范围：下限取 64KiB（再小吞吐会明显下降），上限是协议规定的 2^31-1 */
    static final long MIN_WINDOW = 64 * 1024;
    static final long MAX_WINDOW = Integer.MAX_VALUE;
    static final long MAX_STREAMS = 1000;
    static final long ENVOY_MIN_RATIO = 16;

    private HboneWindows() {
    }

    /** 是不是这几个窗口字段（前端按 MiB 展示） */
    public static boolean isWindowKey(String key) {
        return ISTIOD_STREAM_KEY.equals(key) || ISTIOD_CONNECTION_KEY.equals(key)
                || ZTUNNEL_STREAM_KEY.equals(key) || ZTUNNEL_CONNECTION_KEY.equals(key);
    }

    /**
     * 检查一个 release 合并后的 values；违反约束返回给用户看的原因，否则 null。
     * 只认 istiod / ztunnel 两个 release，其它 release 直接放过。
     */
    public static String violation(String releaseName, Map<String, Object> values) {
        if ("istiod".equals(releaseName)) {
            Long stream = number(values, ISTIOD_STREAM_KEY);
            Long connection = number(values, ISTIOD_CONNECTION_KEY);
            String problem = windowRange(ENVOY_GROUP, stream, connection);
            if (problem != null) {
                return problem;
            }
            if (stream != null && connection != null && connection < stream * ENVOY_MIN_RATIO) {
                return ENVOY_GROUP + "：整条连接的缓冲上限至少要是单个请求的 " + ENVOY_MIN_RATIO + " 倍"
                        + "（现在是 " + mib(connection) + " / " + mib(stream) + "）。"
                        + "被中断的下载会占着额度 1 小时不放，倍数太小，几次中断就会让服务无法访问";
            }
            return null;
        }
        if ("ztunnel".equals(releaseName)) {
            Long streams = number(values, ZTUNNEL_STREAMS_KEY);
            Long stream = number(values, ZTUNNEL_STREAM_KEY);
            Long connection = number(values, ZTUNNEL_CONNECTION_KEY);
            if (streams == INVALID || (streams != null && (streams < 1 || streams > MAX_STREAMS))) {
                return ZTUNNEL_GROUP + "：每条连接最多同时承载的请求数要在 1 到 " + MAX_STREAMS + " 之间";
            }
            String problem = windowRange(ZTUNNEL_GROUP, stream, connection);
            if (problem != null) {
                return problem;
            }
            if (streams != null && stream != null && connection != null && streams * stream >= connection) {
                return ZTUNNEL_GROUP + "：「请求数 × 单个请求的缓冲上限」必须小于整条连接的缓冲上限"
                        + "（现在是 " + streams + " × " + mib(stream) + " = " + mib(streams * stream)
                        + "，连接上限 " + mib(connection) + "）。否则这些请求同时暂停时，"
                        + "同一条连接上的其它请求会全部卡住";
            }
            return null;
        }
        return null;
    }

    private static String windowRange(String group, Long stream, Long connection) {
        for (Long v : new Long[]{stream, connection}) {
            if (v == INVALID || (v != null && (v < MIN_WINDOW || v > MAX_WINDOW))) {
                return group + "：缓冲上限要在 64KiB 到 2047MiB 之间，且必须是整数";
            }
        }
        return null;
    }

    /** 不是整数的取值统一用这个哨兵表示（与「没填」= null 区分开） */
    private static final Long INVALID = Long.MIN_VALUE;

    @SuppressWarnings("unchecked")
    private static Long number(Map<String, Object> values, String path) {
        Object cursor = values;
        for (String part : path.split("\\.")) {
            if (!(cursor instanceof Map<?, ?> map)) {
                return null;
            }
            cursor = ((Map<String, Object>) map).get(part);
        }
        if (cursor == null) {
            return null;
        }
        if (cursor instanceof Number n) {
            return n.doubleValue() == Math.rint(n.doubleValue()) ? n.longValue() : INVALID;
        }
        try {
            return Long.parseLong(cursor.toString().trim());
        } catch (NumberFormatException e) {
            return INVALID;
        }
    }

    private static String mib(long bytes) {
        return bytes % MIB == 0 ? (bytes / MIB) + "MiB" : String.format("%.2fMiB", bytes / (double) MIB);
    }
}
