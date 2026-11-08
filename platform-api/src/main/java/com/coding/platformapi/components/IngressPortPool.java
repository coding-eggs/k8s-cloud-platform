package com.coding.platformapi.components;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.platformapi.services.IngressPortService.PortRange;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用端口池（Istio 组件表单里的一个范围，spec §5.1）——纯函数。
 *
 * <p>为什么要把池里的端口<b>逐个</b>写进网关 Service：ListenerSet 用了 Service 上没声明的端口，
 * 整个 Gateway 会变成 {@code Programmed=False / AddressNotUsable}（2026-11-08 实测）。
 * 所以池 = 预声明端口；nodePort = port，访问地址就是「任一节点 IP:端口」。
 *
 * <p>池的值放在 values 的 {@code platform.ingressPortPool} 下：随快照保存（编辑表单回填、
 * 应用侧读当前范围都从这里取），下发 helm 前由 {@link #render} 展开并删掉 {@code platform} 键。
 */
public final class IngressPortPool {

    public static final String KEY = "platform.ingressPortPool";
    /** 上限：每个端口都是一条 Service 端口 + kube-proxy 规则 */
    public static final int MAX = 5000;
    /** 超过这个数前端提示核对 kube-proxy 规则同步耗时 */
    public static final int WARN = 1000;

    private static final String GATEWAY_RELEASE = "external-gateway";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private IngressPortPool() {
    }

    /** null / 空 / 两端都空 = 停用（返回 null）；否则校验并返回范围 */
    public static PortRange parse(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Integer from = intOf(map.get("from"));
        Integer to = intOf(map.get("to"));
        if (from == null && to == null) {
            return null;
        }
        if (from == null || to == null) {
            throw invalid("应用端口池需要同时填写起始端口和结束端口（都留空 = 停用）");
        }
        if (from < 1 || from > 65535 || to < 1 || to > 65535) {
            throw invalid("应用端口池的端口必须在 1-65535 之间");
        }
        if (from > to) {
            throw invalid("应用端口池的起始端口不能大于结束端口");
        }
        PortRange range = new PortRange(from, to);
        if (range.size() > MAX) {
            throw invalid("应用端口池最多 " + MAX + " 个端口（当前 " + range.size() + " 个）");
        }
        return range;
    }

    /** 池不能和端口表里任何一行的 port / nodePort 重叠（同一个 Service 上端口必须唯一） */
    public static void assertNoOverlap(PortRange pool, List<Map<String, Object>> portRows) {
        if (pool == null || portRows == null) {
            return;
        }
        for (Map<String, Object> row : portRows) {
            for (String key : List.of("port", "nodePort")) {
                Integer p = intOf(row.get(key));
                if (p != null && pool.contains(p)) {
                    throw invalid("应用端口池 " + pool.from() + "-" + pool.to() + " 与端口表「"
                            + row.get("name") + "」的 " + key + " " + p + " 重叠");
                }
            }
        }
    }

    /** 每个端口一行 Service 端口（无 listener：listener 由各命名空间的 ListenerSet 提供） */
    public static List<Map<String, Object>> expand(PortRange pool) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (pool == null) {
            return rows;
        }
        for (int p = pool.from(); p <= pool.to(); p++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "pool-" + p);
            row.put("port", p);
            row.put("targetPort", p);
            row.put("nodePort", p);
            row.put("protocol", "TCP");
            rows.add(row);
        }
        return rows;
    }

    /** 下发 helm 前：池展开追加到 service.ports 末尾，删掉 platform 键；不修改入参 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> render(Map<String, Object> values) {
        Map<String, Object> out = new LinkedHashMap<>(values);
        Object platform = out.remove("platform");
        PortRange pool = platform instanceof Map<?, ?> p ? parse(p.get("ingressPortPool")) : null;
        if (pool == null) {
            return out;
        }
        Map<String, Object> svc = out.get("service") instanceof Map<?, ?> s
                ? new LinkedHashMap<>((Map<String, Object>) s) : new LinkedHashMap<>();
        List<Object> ports = svc.get("ports") instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>();
        ports.addAll(expand(pool));
        svc.put("ports", ports);
        out.put("service", svc);
        return out;
    }

    /** 从 external-gateway release 的 values 里读池（values = 合并后的有效值或快照里那份） */
    public static PortRange fromValues(Map<String, Object> values) {
        if (values == null || !(values.get("platform") instanceof Map<?, ?> platform)) {
            return null;
        }
        return parse(platform.get("ingressPortPool"));
    }

    /** 从 istio 组件操作记录的 values 快照里读池；读不到 / 解析失败返回 null */
    @SuppressWarnings("unchecked")
    public static PortRange fromSnapshot(String valuesSnapshot) {
        if (valuesSnapshot == null || valuesSnapshot.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> snapshot = JSON.readValue(valuesSnapshot, Map.class);
            Object gateway = snapshot.get(GATEWAY_RELEASE);
            return gateway instanceof Map<?, ?> m ? fromValues((Map<String, Object>) m) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Integer intOf(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw invalid("应用端口池的端口必须是整数：" + s);
            }
        }
        return null;
    }

    private static CloudPlatformException invalid(String message) {
        return new CloudPlatformException(EnumResponseType.ERROR, message);
    }
}
