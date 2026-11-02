package com.coding.platformapi.app;

import com.coding.common.models.app.AppInjectDTO;
import com.coding.common.models.app.AppProblemDTO;
import com.coding.common.models.middleware.MiddlewareConnectionDTO.ConnInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 环境变量行的引用解析。纯函数（无 Spring、不碰集群/库）。
 *
 * <p>引用写法 {@code ${依赖.字段}}：中间件/外部连接可引用 host/port/address/username/password/url，
 * 有端口的服务可引用 host/port/url。username/password 永远是 Secret 引用，不会变成明文值。
 * 拼接串里含凭据时，先生成隐藏变量 {@code _REF_<n>}（排在使用它的变量之前），
 * 再以 {@code $(_REF_<n>)} 展开，结果标 {@code inline=true}。
 */
public final class AppEnvResolver {

    /** kind：LITERAL / REF / SECRET。含 "${" 的 LITERAL 视作 REF。 */
    public record AppEnvRow(String name, String kind, String value, String secretName, String secretKey) {
    }

    public record ServiceAddr(String host, int port) {
    }

    public record Result(List<AppInjectDTO> env, List<AppProblemDTO> problems) {
    }

    /** 文本替换结果：有问题时 value 为 null。 */
    public record TextResult(String value, List<AppProblemDTO> problems) {
    }

    /**
     * 把整段文本（配置文件内容）里的 {@code ${x.y}} 原地替换成值。文本会明文进 ConfigMap，
     * 所以只认解析成普通值的字段；解析成 Secret 位置的（username / password）一律报错，不展开。
     */
    public static TextResult resolveText(String label, String text, Map<String, ConnInfo> deps,
                                         Map<String, ServiceAddr> services) {
        String value = text == null ? "" : text;
        List<AppProblemDTO> problems = new ArrayList<>();
        Map<String, ConnInfo> d = deps == null ? Map.of() : deps;
        Map<String, ServiceAddr> s = services == null ? Map.of() : services;
        Matcher m = REF.matcher(value);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(value, last, m.start());
            last = m.end();
            Resolved r = lookup("配置文件 " + label, m.group(1), m.group(2), d, s, problems);
            if (r == null) {
                continue;
            }
            if (r.isSecret()) {
                problems.add(AppProblemDTO.error(null, "配置文件 " + label + "：" + m.group()
                        + " 是凭据，配置文件内容会明文写进 ConfigMap，不能引用；凭据请用环境变量"));
                continue;
            }
            sb.append(r.value());
        }
        sb.append(value.substring(last));
        return new TextResult(problems.isEmpty() ? sb.toString() : null, problems);
    }

    /** 文本里引用到的依赖/服务 key。 */
    public static Set<String> referencesIn(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text != null) {
            Matcher m = REF.matcher(text);
            while (m.find()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    /** 文本里的全部引用 {@code [key, field]}，按出现顺序。 */
    public static List<String[]> referenceParts(String text) {
        List<String[]> out = new ArrayList<>();
        if (text != null) {
            Matcher m = REF.matcher(text);
            while (m.find()) {
                out.add(new String[]{m.group(1), m.group(2)});
            }
        }
        return out;
    }

    static final String HIDDEN_PREFIX = "_REF_";

    private static final Pattern REF = Pattern.compile("\\$\\{([^.${}]+)\\.([^${}]+)}");

    private AppEnvResolver() {
    }

    /** 一个已解析的字段：普通值或 Secret 位置。 */
    private record Resolved(String value, String secretName, String secretKey) {
        boolean isSecret() {
            return secretName != null;
        }
    }

    public static Result resolve(List<AppEnvRow> rows, Map<String, ConnInfo> deps,
                                 Map<String, ServiceAddr> services) {
        List<AppInjectDTO> env = new ArrayList<>();
        List<AppProblemDTO> problems = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Map<String, String> hiddenBySecret = new HashMap<>();
        int hiddenSeq = 0;
        Map<String, ConnInfo> d = deps == null ? Map.of() : deps;
        Map<String, ServiceAddr> s = services == null ? Map.of() : services;

        for (AppEnvRow row : rows == null ? List.<AppEnvRow>of() : rows) {
            String name = row.name();
            if (name == null || name.isBlank()) {
                problems.add(AppProblemDTO.error(null, "环境变量名不能为空"));
                continue;
            }
            if (name.startsWith(HIDDEN_PREFIX)) {
                problems.add(AppProblemDTO.error(null, "环境变量 " + name + " 使用了保留前缀 " + HIDDEN_PREFIX));
                continue;
            }
            if (!names.add(name)) {
                problems.add(AppProblemDTO.error(null, "环境变量 " + name + " 重复定义"));
                continue;
            }
            if ("SECRET".equals(row.kind())) {
                env.add(AppInjectDTO.secret(name, row.secretName(), row.secretKey()));
                continue;
            }
            String value = row.value() == null ? "" : row.value();
            Matcher m = REF.matcher(value);
            List<Resolved> refs = new ArrayList<>();
            List<int[]> spans = new ArrayList<>();
            boolean failed = false;
            while (m.find()) {
                Resolved r = lookup("环境变量 " + name, m.group(1), m.group(2), d, s, problems);
                if (r == null) {
                    failed = true;
                    continue;
                }
                refs.add(r);
                spans.add(new int[]{m.start(), m.end()});
            }
            if (failed) {
                continue;
            }
            if (refs.isEmpty()) {
                env.add(AppInjectDTO.value(name, value));
                continue;
            }
            boolean whole = refs.size() == 1 && spans.get(0)[0] == 0 && spans.get(0)[1] == value.length();
            if (whole) {
                Resolved r = refs.get(0);
                env.add(r.isSecret() ? AppInjectDTO.secret(name, r.secretName(), r.secretKey())
                        : AppInjectDTO.value(name, r.value()));
                continue;
            }
            StringBuilder sb = new StringBuilder();
            int last = 0;
            boolean inline = false;
            for (int i = 0; i < refs.size(); i++) {
                Resolved r = refs.get(i);
                sb.append(value, last, spans.get(i)[0]);
                if (r.isSecret()) {
                    String id = r.secretName() + "\u0000" + r.secretKey();
                    String hidden = hiddenBySecret.get(id);
                    if (hidden == null) {
                        hidden = HIDDEN_PREFIX + hiddenSeq++;
                        hiddenBySecret.put(id, hidden);
                        env.add(AppInjectDTO.secret(hidden, r.secretName(), r.secretKey()));
                    }
                    sb.append("$(").append(hidden).append(')');
                    inline = true;
                } else {
                    sb.append(r.value());
                }
                last = spans.get(i)[1];
            }
            sb.append(value.substring(last));
            AppInjectDTO dto = AppInjectDTO.value(name, sb.toString());
            dto.setInline(inline);
            env.add(dto);
        }
        return new Result(env, problems);
    }

    /** 值里是否含合法的 {@code ${x.y}} 引用。 */
    public static boolean hasReference(String value) {
        return value != null && REF.matcher(value).find();
    }

    /** 去掉合法引用后剩下的字面文本；用来判断一个「引用」值里是否夹带了明文。 */
    public static String stripReferences(String value) {
        return value == null ? "" : REF.matcher(value).replaceAll("");
    }

    /** 被引用的依赖/服务 key（供「使用方」反查与拓扑）。SECRET 行不含引用。 */
    public static Set<String> references(List<AppEnvRow> rows) {
        Set<String> out = new LinkedHashSet<>();
        for (AppEnvRow row : rows == null ? List.<AppEnvRow>of() : rows) {
            if ("SECRET".equals(row.kind()) || row.value() == null) {
                continue;
            }
            Matcher m = REF.matcher(row.value());
            while (m.find()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    private static Resolved lookup(String who, String dep, String field, Map<String, ConnInfo> deps,
                                   Map<String, ServiceAddr> services, List<AppProblemDTO> problems) {
        ConnInfo c = deps.get(dep);
        if (c != null) {
            Resolved r = connField(c, field);
            if (r == null) {
                problems.add(AppProblemDTO.error(null, who + "：依赖 " + dep + " 没有字段 " + field
                        + "（或该字段无值）"));
            }
            return r;
        }
        ServiceAddr a = services.get(dep);
        if (a != null) {
            Resolved r = switch (field) {
                case "host" -> new Resolved(a.host(), null, null);
                case "port" -> new Resolved(String.valueOf(a.port()), null, null);
                case "url" -> new Resolved("http://" + a.host() + ":" + a.port(), null, null);
                default -> null;
            };
            if (r == null) {
                problems.add(AppProblemDTO.error(null, who + "：服务 " + dep + " 没有字段 " + field));
            }
            return r;
        }
        problems.add(AppProblemDTO.error(null, who + "：引用了不存在的依赖或服务 " + dep));
        return null;
    }

    private static Resolved connField(ConnInfo c, String field) {
        boolean hasSecret = c.getSecretName() != null;
        return switch (field) {
            case "host" -> c.getHost() == null ? null : new Resolved(c.getHost(), null, null);
            case "port" -> c.getPort() == null ? null : new Resolved(String.valueOf(c.getPort()), null, null);
            case "address" -> c.getHost() == null ? null : new Resolved(address(c), null, null);
            case "url" -> c.getHost() == null ? null : new Resolved(url(c), null, null);
            case "username" -> hasSecret && c.getUsernameKey() != null
                    ? new Resolved(null, c.getSecretName(), c.getUsernameKey()) : null;
            case "password" -> hasSecret && c.getPasswordKey() != null
                    ? new Resolved(null, c.getSecretName(), c.getPasswordKey()) : null;
            default -> null;
        };
    }

    private static boolean isUrl(String host) {
        return host.startsWith("http://") || host.startsWith("https://");
    }

    /** host 可能是完整 URL：已带协议则原样返回（端口已在里面），否则补 http:// 与端口。 */
    private static String url(ConnInfo c) {
        if (isUrl(c.getHost())) {
            return c.getHost();
        }
        return "http://" + c.getHost() + (c.getPort() == null ? "" : ":" + c.getPort());
    }

    /** host:port；host 若是完整 URL 则去掉协议与路径，已带端口就不再追加。 */
    private static String address(ConnInfo c) {
        String h = c.getHost();
        if (isUrl(h)) {
            h = h.substring(h.indexOf("://") + 3);
        }
        int slash = h.indexOf('/');
        if (slash >= 0) {
            h = h.substring(0, slash);
        }
        return h.contains(":") || c.getPort() == null ? h : h + ":" + c.getPort();
    }
}
