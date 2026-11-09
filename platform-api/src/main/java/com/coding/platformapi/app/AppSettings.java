package com.coding.platformapi.app;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 应用级设置：链路追踪与服务网格（纯函数）。
 *
 * <p>为什么是应用级：一条链路跨多个服务，只开一部分链路就断；采样由入口决定（parent-based），
 * 各服务各填采样率没有意义。网格同理：mTLS / L7 策略要调用双方都在网格里才成立，一个应用里半进半出没有意义。
 * 日志采集、HPA、指标仍是服务级（各服务确实不同）。
 *
 * <p>形状：{@code {tracing: true, sampling: 1-100?, mesh: {mode: none|l4|l7, waypoint?: 名称}}}；
 * 全部缺省 = 空 Map（「跟随」，不注入任何东西）。网格三档（缺 mesh = 跟随命名空间）：
 * <ul>
 *   <li>{@code none}：Pod 标 {@code istio.io/dataplane-mode=none}，不加入网格；</li>
 *   <li>{@code l4}：Pod 标 {@code ambient}（ztunnel：mTLS、L4 授权、TCP 指标）；有端口的 Service 标
 *       {@code istio.io/use-waypoint=none}，即使命名空间配了 waypoint 也不走；</li>
 *   <li>{@code l7}：Pod 标 {@code ambient}，有端口的 Service 标 {@code istio.io/use-waypoint=<waypoint>}（必填）——
 *       HTTP 路由、重试超时、L7 授权、请求级指标；后台进程没有 Service，只有 L4。</li>
 * </ul>存在应用行（{@code settings_json}）和发布快照（{@code app}）里。
 *
 * <p>渲染与门禁都先用 {@link #effective} 把应用级设置合并进每个服务的期望配置，再走原有的服务级逻辑
 * （{@link AppServiceInjection} / {@link AppServiceFeatures}）——那两处不需要知道设置来自哪一级。
 */
public final class AppSettings {

    /** 发布勾选与待发布列表里代表「应用设置」的伪 key（服务 key 是 DNS 标签，不可能与它相同） */
    public static final String KEY = "@app";

    private static final Set<String> FIELDS = Set.of("tracing", "sampling", "mesh");
    private static final Set<String> MESH_FIELDS = Set.of("mode", "waypoint");
    private static final Set<String> MESH_MODES = Set.of("none", "l4", "l7");
    private static final Pattern DNS_LABEL = Pattern.compile("^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$");

    private AppSettings() {
    }

    public static Map<String, Object> normalize(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        for (String k : raw.keySet()) {
            if (!FIELDS.contains(k)) {
                throw bad("不支持的应用设置项：" + k);
            }
        }
        boolean tracing = Boolean.TRUE.equals(raw.get("tracing"));
        if (tracing) {
            out.put("tracing", true);
            Object s = raw.get("sampling");
            if (s != null && !String.valueOf(s).isBlank()) {
                int sampling;
                try {
                    sampling = s instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(s).trim());
                } catch (NumberFormatException e) {
                    throw bad("采样率必须是整数");
                }
                if (sampling < 1 || sampling > 100) {
                    throw bad("采样率必须在 1-100 之间，留空跟随组件默认");
                }
                out.put("sampling", sampling);
            }
        }
        Object m = raw.get("mesh");
        if (m != null) {
            if (!(m instanceof Map<?, ?> mesh)) {
                throw bad("服务网格配置格式错误");
            }
            for (Object k : mesh.keySet()) {
                if (!MESH_FIELDS.contains(String.valueOf(k))) {
                    throw bad("不支持的服务网格配置项：" + k);
                }
            }
            String mode = text(mesh.get("mode")).toLowerCase();
            String waypoint = text(mesh.get("waypoint"));
            if (!mode.isEmpty()) {
                if (!MESH_MODES.contains(mode)) {
                    throw bad("不支持的网格模式：" + mesh.get("mode") + "（none / l4 / l7）");
                }
                Map<String, Object> mo = new LinkedHashMap<>();
                mo.put("mode", mode);
                if ("l7".equals(mode)) {
                    if (waypoint.isEmpty()) {
                        throw bad("L7 需要选择 waypoint");
                    }
                    if (!DNS_LABEL.matcher(waypoint).matches()) {
                        throw bad("waypoint 名称不合法：" + waypoint);
                    }
                    mo.put("waypoint", waypoint);
                }
                out.put("mesh", mo);
            }
        }
        return out;
    }

    /**
     * 服务期望配置 + 应用设置 → 渲染 / 门禁用的「生效配置」（不改入参）。
     * <ul>
     *   <li>链路追踪：应用开启、且该服务选了语言（{@code otel.language}）才生效，采样率取应用级；
     *       没选语言的服务（nginx、Go……）不注入，也就不需要组件；</li>
     *   <li>网格：数据面模式打到所有服务；waypoint 只打到有端口的服务（没有端口就没有 Service）。</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> effective(Map<String, Object> desired, Map<String, Object> settings) {
        if (desired == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>(desired);
        Map<String, Object> s = settings == null ? Map.of() : settings;
        if (Boolean.TRUE.equals(s.get("tracing")) && desired.get("otel") instanceof Map<?, ?> otel
                && otel.get("language") != null) {
            Map<String, Object> o = new LinkedHashMap<>((Map<String, Object>) otel);
            o.put("tracing", true);
            if (s.get("sampling") != null) {
                o.put("sampling", s.get("sampling"));
            }
            out.put("otel", o);
        }
        String mode = meshMode(s);
        if (mode != null) {
            // 翻译成服务级的 {dataplaneMode, useWaypoint}：注入（AppServiceInjection）与旗标判定只认这一种形状
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dataplaneMode", "none".equals(mode) ? "none" : "ambient");
            if (desired.get("port") != null && !"none".equals(mode)) {
                m.put("useWaypoint", "l7".equals(mode) ? waypoint(s) : "none");
            }
            out.put("mesh", m);
        }
        return out;
    }

    /** none / l4 / l7；没设（跟随命名空间）返回 null */
    public static String meshMode(Map<String, Object> settings) {
        return settings != null && settings.get("mesh") instanceof Map<?, ?> mesh && mesh.get("mode") != null
                ? String.valueOf(mesh.get("mode")) : null;
    }

    /** 应用级网格为 L7 且选了 waypoint：内部灰度分流的前提（内部灰度设计 §3） */
    public static boolean isL7(Map<String, Object> settings) {
        return "l7".equals(meshMode(settings)) && waypoint(settings) != null;
    }

    /** L7 选的 waypoint 名；其余模式返回 null */
    public static String waypoint(Map<String, Object> settings) {
        return "l7".equals(meshMode(settings)) && settings.get("mesh") instanceof Map<?, ?> mesh
                && mesh.get("waypoint") != null ? String.valueOf(mesh.get("waypoint")) : null;
    }

    /** 设置本身生效时需要的组件旗标（保存应用设置时的门禁用） */
    public static Set<String> required(Map<String, Object> settings) {
        Set<String> out = new LinkedHashSet<>();
        if (settings == null) {
            return out;
        }
        if (Boolean.TRUE.equals(settings.get("tracing"))) {
            out.add(AppServiceFeatures.OTEL_COLLECTION);
        }
        String mode = meshMode(settings);
        if ("l4".equals(mode) || "l7".equals(mode)) {
            out.add(AppServiceFeatures.AMBIENT_MESH);
        }
        return out;
    }

    /** 相对 before 新开启的旗标；waypoint 换成另一个具体名字也算开启 */
    public static Set<String> newlyEnabled(Map<String, Object> before, Map<String, Object> after) {
        Set<String> out = required(after);
        out.removeAll(required(before));
        String wp = waypoint(after);
        String old = waypoint(before);
        if (wp != null && old != null && !wp.equals(old)) {
            out.add(AppServiceFeatures.AMBIENT_MESH);
        }
        return out;
    }

    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static CloudPlatformException bad(String message) {
        return new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, message);
    }
}
