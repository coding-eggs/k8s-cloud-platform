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
            "autoscalingEnabled", "minReplicas", "maxReplicas", "targetCpu", "targetMemory",
            "probeEnabled", "probePath", "metricsEnabled", "metricsPath");

    private static final Set<String> CORE = Set.of("imageRepo", "imageTag", "port", "replicas", "env", "configFiles",
            "expose", "nodePort", "otel", "probeType");

    /** 接入 OTel 后由平台写的环境变量：用户同名变量与平台值谁生效不可预期，故接入时拒绝 */
    public static final Set<String> OTEL_RESERVED_ENV = Set.of(
            "OTEL_SERVICE_NAME", "OTEL_TRACES_SAMPLER", "OTEL_TRACES_SAMPLER_ARG");
    private static final Set<String> OTEL_FIELDS = Set.of("language", "logs", "logMode", "logFile");
    private static final Set<String> OTEL_LANGUAGES = Set.of("java", "python");
    private static final List<String> HPA_KEYS = List.of(
            "autoscalingEnabled", "minReplicas", "maxReplicas", "targetCpu", "targetMemory");

    /** 对外暴露方式：目前只有 NodePort（HTTPRoute 等网关方式以后再加）。不暴露 = 不存这个键 */
    public static final String EXPOSE_NODEPORT = "nodeport";
    /** 网关端口池暴露（HTTP → HTTPRoute，TCP → TCPRoute）；端口锁在分配表里，不进期望配置 */
    public static final String EXPOSE_GATEWAY_HTTP = "gateway-http";
    public static final String EXPOSE_GATEWAY_TCP = "gateway-tcp";
    /** K8s 默认 service-node-port-range */
    static final int NODE_PORT_MIN = 30000;
    static final int NODE_PORT_MAX = 32767;

    /** 配置文件内容可引用的字段：都解析成普通值。username / password 是 Secret 位置，进不了 ConfigMap。 */
    public static final List<String> FILE_REF_FIELDS = List.of("host", "port", "address", "url");

    /** 一个路径段：不含空白、引号、反斜杠等，避免挂载路径被 shell / YAML 误读。 */
    private static final Pattern PATH_SEGMENT = Pattern.compile("^[A-Za-z0-9._@+=,-]+$");
    private static final int FILES_MAX = 20;
    /** 配置文件总大小上限：ConfigMap 硬上限 1MiB，快照与清单里还要各存一份 */
    private static final int FILES_TOTAL_BYTES_MAX = 512 * 1024;

    /** K8s 1.34 之前的规则（之后放宽到除 = 外的可打印 ASCII）：取它，新老集群都收；允许 spring.datasource.url 这类带点的名字 */
    private static final Pattern ENV_NAME = Pattern.compile("^[-._A-Za-z][-._A-Za-z0-9]*$");
    private static final Set<String> SENSITIVE_LAST = Set.of("PASSWORD", "PWD", "PASSWD", "SECRET", "TOKEN", "APIKEY");

    private AppServiceDesired() {
    }

    @SuppressWarnings("unchecked")
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
        List<Map<String, Object>> files = normalizeConfigFiles(raw.get("configFiles"), dataMountPath(raw, port));
        if (!files.isEmpty()) {
            out.put("configFiles", files);   // 空列表不存：否则「没有 vs 空」会被当成待发布改动
        }
        normalizeExpose(raw, port, out);
        normalizeProbeType(raw.get("probeType"), out);
        Map<String, Object> otel = normalizeOtel(raw.get("otel"), dataMountPath(raw, port));
        if (otel != null) {
            for (Map<String, Object> row : (List<Map<String, Object>>) out.get("env")) {
                if (OTEL_RESERVED_ENV.contains(row.get("name"))) {
                    throw bad("已接入可观测，环境变量 " + row.get("name") + " 由平台设置，请删除");
                }
            }
            out.put("otel", otel);
        }
        if (raw.containsKey("mesh")) {
            throw bad("服务网格是应用级设置，请在应用的「应用设置」里配置");
        }
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

    /** 探针类型：http（缺省，不存——旧数据没有这个键）/ tcp（tcpSocket 探服务端口，纯 TCP 服务用）。 */
    private static void normalizeProbeType(Object raw, Map<String, Object> out) {
        String t = text(raw).toLowerCase();
        if (t.isEmpty() || "http".equals(t)) {
            return;
        }
        if (!"tcp".equals(t)) {
            throw bad("不支持的探针类型：" + raw + "（可选 HTTP / TCP）");
        }
        out.put("probeType", "tcp");
    }

    /**
     * 对外暴露：{@code expose} 取 none / nodeport / gateway-http / gateway-tcp（大小写不敏感），
     * none 与空都不存（否则「没有 vs none」会成待发布改动）。都只对有端口的服务有意义。
     * {@code nodePort} 只属于 NodePort 方式：填了必须在 30000-32767；可空 = 保存时由平台挑一个空闲的写进来
     * （{@code AppNodePortAllocator}，不交给 K8s 随机）。
     * 网关方式的端口由平台从应用端口池分配（保存时锁定），这里不收。
     */
    private static void normalizeExpose(Map<String, Object> raw, Integer port, Map<String, Object> out) {
        String expose = text(raw.get("expose")).toLowerCase();
        Integer nodePort = integer(raw.get("nodePort"), "NodePort 端口");
        if (expose.isEmpty() || "none".equals(expose)) {
            if (nodePort != null) {
                throw bad("只有选择 NodePort 暴露时才能填 NodePort 端口");
            }
            return;
        }
        boolean gateway = EXPOSE_GATEWAY_HTTP.equals(expose) || EXPOSE_GATEWAY_TCP.equals(expose);
        if (!EXPOSE_NODEPORT.equals(expose) && !gateway) {
            throw bad("不支持的暴露方式：" + raw.get("expose") + "（可选 NodePort / 网关 HTTP / 网关 TCP）");
        }
        if (port == null) {
            throw bad("后台进程没有端口，不能对外暴露；请先填写端口");
        }
        if (gateway) {
            if (nodePort != null) {
                throw bad("只有选择 NodePort 暴露时才能填 NodePort 端口（网关方式的端口由平台从端口池分配）");
            }
            out.put("expose", expose);
            return;
        }
        out.put("expose", EXPOSE_NODEPORT);
        if (nodePort != null) {
            if (nodePort < NODE_PORT_MIN || nodePort > NODE_PORT_MAX) {
                throw bad("NodePort 端口必须在 " + NODE_PORT_MIN + "-" + NODE_PORT_MAX + " 之间，留空由平台分配");
            }
            out.put("nodePort", nodePort);
        }
    }

    /**
     * 服务级可观测：应用语言 + 日志采集。链路追踪是应用级设置（{@link AppSettings}），这里只存语言——
     * 应用开了追踪时，选了语言的服务才注入探针。语言与日志都没有 = 不存（返回 null）。
     * 语义与工作负载页「可观测接入」一致（{@code OtelInjection}）。
     */
    private static Map<String, Object> normalizeOtel(Object raw, String dataMount) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> m)) {
            throw bad("可观测配置格式错误");
        }
        for (Object k : m.keySet()) {
            if ("tracing".equals(k) || "sampling".equals(k)) {
                throw bad("链路追踪是应用级设置，请在应用的「应用设置」里开关");
            }
            if (!OTEL_FIELDS.contains(String.valueOf(k))) {
                throw bad("不支持的可观测配置项：" + k);
            }
        }
        boolean logs = Boolean.TRUE.equals(m.get("logs"));
        String language = text(m.get("language")).toLowerCase();
        if (!language.isEmpty() && !OTEL_LANGUAGES.contains(language)) {
            throw bad("不支持的应用语言：" + m.get("language"));
        }
        if (!logs && language.isEmpty()) {
            return null;
        }
        String logMode = text(m.get("logMode")).isEmpty() ? "app" : text(m.get("logMode")).toLowerCase();
        if (!"app".equals(logMode) && !"file".equals(logMode)) {
            throw bad("不支持的日志采集方式：" + m.get("logMode"));
        }
        boolean fileLogs = logs && "file".equals(logMode);
        if (logs && !fileLogs && language.isEmpty()) {
            throw bad("「日志框架」方式采集日志时请选择应用语言（java / python）");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (!language.isEmpty()) {
            out.put("language", language);
        }
        out.put("logs", logs);
        if (logs) {
            out.put("logMode", logMode);
        }
        if (fileLogs) {
            String file = text(m.get("logFile"));
            int slash = file.lastIndexOf('/');
            if (!file.startsWith("/") || slash <= 0 || slash == file.length() - 1) {
                throw bad("日志文件必须是容器内具体文件的绝对路径（如 /var/log/nginx/access.log）");
            }
            // 日志目录会挂一个 emptyDir：与数据卷同一个挂载点会被 apiserver 拒绝（重复 mountPath），保存时就拦
            if (dataMount != null && file.substring(0, slash).equals(dataMount)) {
                throw bad("日志目录 " + dataMount + " 与持久化数据卷的挂载路径相同，请把日志写到子目录（如 " + dataMount + "/logs/）");
            }
            out.put("logFile", file);
        }
        return out;
    }

    /**
     * HPA 区间校验：只在 HPA 相关键有变化时跑（{@code before} 为 null = 新建），
     * 与 HPA 无关的保存（比如只改镜像）不因存量脏数据被拒。
     */
    public static void validateHpaChange(Map<String, Object> before, Map<String, Object> after) {
        if (after == null || !Boolean.TRUE.equals(after.get("autoscalingEnabled"))) {
            return;
        }
        if (before != null && HPA_KEYS.stream().allMatch(k -> java.util.Objects.equals(before.get(k), after.get(k)))) {
            return;
        }
        Integer min = integer(after.get("minReplicas"), "最小副本");
        Integer max = integer(after.get("maxReplicas"), "最大副本");
        int lo = min == null ? 1 : min;
        int hi = max == null ? 10 : max;
        if (lo < 1 || lo > hi) {
            throw bad("自动扩缩容的副本范围不合法：需要 1 ≤ 最小副本 ≤ 最大副本");
        }
        for (String k : List.of("targetCpu", "targetMemory")) {
            Integer t = integer(after.get(k), "目标利用率");
            if (t != null && (t < 1 || t > 100)) {
                throw bad("目标利用率必须在 1-100 之间");
            }
        }
    }

    /** 固定的 NodePort（没暴露或自动分配返回 null） */
    /** 网关方式的协议：HTTP / TCP；不是网关方式返回 null */
    public static String gatewayProtocol(Map<String, Object> desired) {
        Object expose = desired == null ? null : desired.get("expose");
        if (EXPOSE_GATEWAY_HTTP.equals(expose)) {
            return "HTTP";
        }
        return EXPOSE_GATEWAY_TCP.equals(expose) ? "TCP" : null;
    }

    public static Integer nodePort(Map<String, Object> desired) {
        return desired != null && EXPOSE_NODEPORT.equals(desired.get("expose"))
                && desired.get("nodePort") instanceof Number n ? n.intValue() : null;
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

    /** 持久化数据卷的挂载点；只有「有端口 + 开持久化」才会建 StatefulSet 数据卷，其余返回 null。 */
    private static String dataMountPath(Map<String, Object> raw, Integer port) {
        if (port == null || !Boolean.TRUE.equals(raw.get("persistenceEnabled"))) {
            return null;
        }
        String p = text(raw.get("mountPath"));
        return p.isEmpty() ? "/data" : p;
    }

    /**
     * 配置文件：每个文件以只读 subPath 挂到容器里。路径必须是绝对路径、指向文件（不以 / 结尾）、
     * 不含 . / .. / 空段；同一服务内不能重复、不能一个是另一个的上级目录，也不能落在持久化数据卷里。
     * 内容可引用 {@code ${x.host|port|address|url}}，渲染时替换；不能引用凭据。
     */
    private static List<Map<String, Object>> normalizeConfigFiles(Object raw, String dataMount) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof List<?> list)) {
            throw bad("配置文件必须是列表");
        }
        if (list.size() > FILES_MAX) {
            throw bad("配置文件最多 " + FILES_MAX + " 个");
        }
        List<String> paths = new ArrayList<>();
        long total = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw bad("配置文件格式错误");
            }
            String path = text(m.get("path"));
            String content = m.get("content") == null ? "" : String.valueOf(m.get("content"));
            assertFilePath(path);
            if (paths.contains(path)) {
                throw bad("配置文件路径重复：" + path);
            }
            for (String other : paths) {
                if (path.startsWith(other + "/") || other.startsWith(path + "/")) {
                    throw bad("配置文件 " + path + " 与 " + other + " 互相嵌套：文件不能放在另一个文件的路径下");
                }
            }
            if (dataMount != null) {
                String mount = dataMount.endsWith("/") && dataMount.length() > 1
                        ? dataMount.substring(0, dataMount.length() - 1) : dataMount;
                if (path.equals(mount) || path.startsWith(mount + "/") || mount.startsWith(path + "/")) {
                    throw bad("配置文件 " + path + " 与持久化挂载路径 " + mount + " 冲突");
                }
            }
            assertFileContent(path, content);
            total += content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            paths.add(path);
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("path", path);
            f.put("content", content);
            out.add(f);
        }
        if (total > FILES_TOTAL_BYTES_MAX) {
            throw bad("配置文件总大小不能超过 " + FILES_TOTAL_BYTES_MAX / 1024 + " KiB");
        }
        return out;
    }

    private static void assertFilePath(String path) {
        if (path.isEmpty()) {
            throw bad("请填写配置文件路径");
        }
        if (!path.startsWith("/")) {
            throw bad("配置文件路径必须是绝对路径（以 / 开头）：" + path);
        }
        if (path.length() > 255) {
            throw bad("配置文件路径过长：" + path);
        }
        if (path.endsWith("/")) {
            throw bad("配置文件路径要指向文件，不能以 / 结尾：" + path);
        }
        for (String seg : path.substring(1).split("/", -1)) {
            if (seg.equals("..")) {
                throw bad("配置文件路径不能包含 ..：" + path);
            }
            if (seg.isEmpty() || seg.equals(".") || !PATH_SEGMENT.matcher(seg).matches()) {
                throw bad("配置文件路径不合法（只允许字母、数字和 . _ - @ + = ,，不能有空段或 .）：" + path);
            }
        }
    }

    private static void assertFileContent(String path, String content) {
        if (!content.contains("${")) {
            return;
        }
        if (AppEnvResolver.stripReferences(content).contains("${")) {
            throw bad("配置文件 " + path + " 的引用格式不正确，应为 ${依赖.字段}");
        }
        for (String[] ref : AppEnvResolver.referenceParts(content)) {
            if (!FILE_REF_FIELDS.contains(ref[1])) {
                throw bad("配置文件 " + path + " 引用了 ${" + ref[0] + "." + ref[1] + "}：配置文件内容会明文写进 ConfigMap，"
                        + "只能引用 " + String.join("、", FILE_REF_FIELDS) + "；用户名、密码等凭据请用环境变量引用");
            }
        }
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
        String[] t = name.toUpperCase().split("[_.-]");
        if (t.length == 0) {
            return false;   // 只由分隔符组成的名字，split 得到空数组
        }
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

    /** 配置文件 [{path, content}]（原文，引用未替换）；没有则为空列表。 */
    public static List<Map<String, String>> configFiles(Map<String, Object> desired) {
        List<Map<String, String>> out = new ArrayList<>();
        if (desired != null && desired.get("configFiles") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add(Map.of("path", text(m.get("path")),
                            "content", m.get("content") == null ? "" : String.valueOf(m.get("content"))));
                }
            }
        }
        return out;
    }

    /** 被引用的依赖/服务 key：环境变量 + 配置文件内容（供使用方反查、移除守卫、拓扑与就绪检查）。 */
    public static Set<String> references(Map<String, Object> desired) {
        Set<String> out = new LinkedHashSet<>(AppEnvResolver.references(envRows(desired)));
        configFiles(desired).forEach(f -> out.addAll(AppEnvResolver.referencesIn(f.get("content"))));
        return out;
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
