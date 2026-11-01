package com.coding.platformapi.app;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.platformapi.app.AppEnvResolver.AppEnvRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 服务期望配置（desired_json）的规范化与读取。纯函数。
 *
 * <p>白名单：未知字段一律拒绝，而不是悄悄丢弃或放行 —— 快照会原样存库，
 * 放行一个叫 password 的字段就等于把口令写进了不可变的发布记录。
 * 环境变量里凭据应是 Secret 名 + 键，或 {@code ${依赖.字段}} 引用；
 * 按名字猜出的「疑似明文凭据」只给 {@link #warnings} 提示，不阻断保存和发布。
 */
public final class AppServiceDesired {

    /** 除 imageRepo/imageTag/port/replicas/env 外，原样透传给 AppWorkloadValues 的参数。 */
    private static final Set<String> PASSTHROUGH = Set.of(
            "command", "args", "imagePullPolicy", "imagePullSecrets",
            "cpuRequest", "memoryRequest", "cpuLimit", "memoryLimit",
            "persistenceEnabled", "storageSize", "storageClass", "mountPath",
            "autoscalingEnabled", "minReplicas", "maxReplicas", "targetCpu",
            "probeEnabled", "probePath", "metricsEnabled", "metricsPath", "expose");

    private static final Set<String> CORE = Set.of("imageRepo", "imageTag", "port", "replicas", "env");

    private static final Pattern ENV_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Set<String> SENSITIVE_LAST = Set.of("PASSWORD", "PWD", "PASSWD", "SECRET", "TOKEN", "APIKEY");

    private AppServiceDesired() {
    }

    public static Map<String, Object> normalize(Map<String, Object> raw) {
        if (raw == null) {
            throw bad("服务配置为空");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        String repo = text(raw.get("imageRepo"));
        String tag = text(raw.get("imageTag"));
        if (repo.isEmpty() || repo.matches(".*\\s.*")) {
            throw bad("请填写镜像仓库地址（不含 tag）");
        }
        if (tag.isEmpty() || tag.matches(".*\\s.*")) {
            throw bad("请填写镜像 tag");
        }
        out.put("imageRepo", repo);
        out.put("imageTag", tag);
        Integer port = integer(raw.get("port"), "端口");
        if (port != null) {
            if (port < 1 || port > 65535) {
                throw bad("端口必须在 1-65535 之间");
            }
            out.put("port", port);
        }
        Integer replicas = integer(raw.get("replicas"), "副本数");
        if (replicas == null) {
            replicas = 1;
        }
        if (replicas < 0) {
            throw bad("副本数不能为负");
        }
        out.put("replicas", replicas);
        out.put("env", normalizeEnv(raw.get("env")));
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String k = e.getKey();
            if (CORE.contains(k)) {
                continue;
            }
            if (!PASSTHROUGH.contains(k)) {
                throw bad("不支持的配置项：" + k);
            }
            if (e.getValue() != null) {
                out.put(k, e.getValue());
            }
        }
        return out;
    }

    private static List<Map<String, Object>> normalizeEnv(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof List<?> list)) {
            throw bad("环境变量必须是列表");
        }
        Set<String> names = new LinkedHashSet<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw bad("环境变量行格式错误");
            }
            String name = text(m.get("name"));
            if (!ENV_NAME.matcher(name).matches()) {
                throw bad("环境变量名不合法：" + name);
            }
            if (!names.add(name)) {
                throw bad("环境变量重复：" + name);
            }
            String kind = text(m.get("kind")).isEmpty() ? "LITERAL" : text(m.get("kind")).toUpperCase();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            switch (kind) {
                case "SECRET" -> {
                    String secretName = text(m.get("secretName"));
                    String secretKey = text(m.get("secretKey"));
                    if (secretName.isEmpty() || secretKey.isEmpty()) {
                        throw bad("环境变量 " + name + " 引用 Secret 时必须填 Secret 名和键");
                    }
                    row.put("kind", "SECRET");
                    row.put("secretName", secretName);
                    row.put("secretKey", secretKey);
                }
                case "LITERAL", "REF" -> {
                    String value = m.get("value") == null ? "" : String.valueOf(m.get("value"));
                    // 含 "${" 却没有一个合法引用、或去掉合法引用后还残留 "${"：写错了，不能悄悄当字面值或引用存下去
                    if (value.contains("${")
                            && (!AppEnvResolver.hasReference(value)
                            || AppEnvResolver.stripReferences(value).contains("${"))) {
                        throw bad("环境变量 " + name + " 的引用格式不正确，应为 ${依赖.字段}");
                    }
                    row.put("kind", AppEnvResolver.hasReference(value) ? "REF" : "LITERAL");
                    row.put("value", value);
                }
                default -> throw bad("环境变量 " + name + " 的类型不支持：" + kind);
            }
            out.add(row);
        }
        return out;
    }

    /**
     * 提示（WARN，不阻断发布）：名字以 PASSWORD / PWD / PASSWD / SECRET / TOKEN / API_KEY 结尾、
     * 且值里除合法 {@code ${x.y}} 引用外还有字面文本的变量。按 {@code _} 分词看末尾词，
     * 所以 TOKEN_TTL、MAX_TOKENS、PASSWORD_MIN_LENGTH 不会误报。
     */
    public static List<String> warnings(Map<String, Object> desired) {
        List<String> out = new ArrayList<>();
        for (AppEnvRow row : envRows(desired)) {
            if ("SECRET".equals(row.kind()) || !looksSensitive(row.name())) {
                continue;
            }
            if (!AppEnvResolver.stripReferences(row.value()).isEmpty()) {
                out.add("环境变量 " + row.name() + " 看起来是凭据却写了明文，建议改用依赖引用（${依赖.password}）或已有 Secret");
            }
        }
        return out;
    }

    static boolean looksSensitive(String name) {
        String[] t = name.toUpperCase().split("_");
        String last = t[t.length - 1];
        if (SENSITIVE_LAST.contains(last)) {
            return true;
        }
        return t.length >= 2 && "KEY".equals(last) && "API".equals(t[t.length - 2]);
    }

    /** 有端口 → SERVICE，没有 → WORKER。原样传给 k8s-server。 */
    public static String kindOf(Map<String, Object> desired) {
        return desired != null && desired.get("port") instanceof Number ? "SERVICE" : "WORKER";
    }

    public static String image(Map<String, Object> desired) {
        return text(desired.get("imageRepo")) + ":" + text(desired.get("imageTag"));
    }

    public static Integer port(Map<String, Object> desired) {
        return desired.get("port") instanceof Number n ? n.intValue() : null;
    }

    public static List<AppEnvRow> envRows(Map<String, Object> desired) {
        List<AppEnvRow> rows = new ArrayList<>();
        if (desired != null && desired.get("env") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    rows.add(new AppEnvRow(text(m.get("name")), text(m.get("kind")),
                            m.get("value") == null ? null : String.valueOf(m.get("value")),
                            text(m.get("secretName")), text(m.get("secretKey"))));
                }
            }
        }
        return rows;
    }

    public static Set<String> references(Map<String, Object> desired) {
        return AppEnvResolver.references(envRows(desired));
    }

    private static Integer integer(Object o, String label) {
        if (o == null || (o instanceof String s && s.isBlank())) {
            return null;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            throw bad(label + "必须是数字");
        }
    }

    private static String text(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static CloudPlatformException bad(String message) {
        return new CloudPlatformException(EnumResponseType.ERROR, message);
    }
}
