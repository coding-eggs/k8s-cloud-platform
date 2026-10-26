package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.middleware.MiddlewareUserView;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch（ECK）的用户管理——走 {@code _security} API。
 *
 * <p><b>用户名空间是全局的</b>：平台建的账号统一加 {@code platform_} 前缀，列表再剥掉显示；
 * 只展示 {@code elastic} 与 {@code platform_} 开头的账号。
 *
 * <p><b>TLS</b>：从 {@code <实例名>-es-http-certs-public} 的 {@code ca.crt} 建信任链
 * （**不** trust-all）；两个 TLS Secret 都不存在才说明实例没开 TLS，走 http。
 *
 * <p>⚠️ 别去 {@code -es-http-ca-internal} 找 {@code ca.crt}：ECK 那个 Secret 装的是 **CA 自己的
 * 密钥对**（{@code tls.crt} / {@code tls.key}），公钥证书在 {@code -es-http-certs-public}。
 * 读错地方的后果不是"连不上"，而是误判成没开 TLS、拿 http 去撞 TLS 端口（真集群实锤，2026-10-26）。
 */
@Component
public class ElasticsearchUserAdapter implements MiddlewareUserAdapter {

    static final String PLATFORM_PREFIX = "platform_";
    static final String BUILTIN = "elastic";
    static final String ELASTIC_SECRET_SUFFIX = "-es-elastic-user";
    /** 公钥证书：{@code ca.crt} 在这里 */
    static final String CA_SECRET_SUFFIX = "-es-http-certs-public";
    /** CA 的密钥对（只有 tls.crt / tls.key）—— 仅用来判断"实例开了 TLS" */
    static final String CA_KEYPAIR_SUFFIX = "-es-http-ca-internal";
    static final String CA_KEY = "ca.crt";
    static final String SERVICE_SUFFIX = "-es-http";
    static final List<String> ROLES = List.of("只读", "读写", "管理员");

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String code() {
        return "elasticsearch";
    }

    @Override
    public List<MiddlewareUserView> list(KubernetesClient client, MiddlewareUserRef ref) {
        Map<String, String> roles = UsersSecret.roles(
                UsersSecret.read(client, ref.namespace(), ref.instanceName()));
        List<MiddlewareUserView> out = new ArrayList<>();
        out.add(view(BUILTIN, "管理员", true, ref));

        String json = call(client, ref, "GET", "/_security/user", null);
        try {
            Iterator<String> names = JSON.readTree(json).fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!name.startsWith(PLATFORM_PREFIX)) {
                    continue;   // elastic 已在最前；其它系统账号不展示
                }
                String display = displayName(name);
                out.add(view(display, roles.getOrDefault(display, ""), false, ref));
            }
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "解析 Elasticsearch 用户列表失败：" + e.getMessage());
        }
        return out;
    }

    @Override
    public String create(KubernetesClient client, MiddlewareUserRef ref, String username, String password, String role) {
        requireRole(role);
        call(client, ref, "PUT", "/_security/user/" + esUsername(username), userBody(password, role));
        return password;
    }

    @Override
    public void drop(KubernetesClient client, MiddlewareUserRef ref, String username) {
        call(client, ref, "DELETE", "/_security/user/" + esUsername(username), null);
    }

    @Override
    public String changePassword(KubernetesClient client, MiddlewareUserRef ref, String username, String newPassword) {
        String esName = BUILTIN.equals(username) ? BUILTIN : esUsername(username);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("password", newPassword);
        call(client, ref, "POST", "/_security/user/" + esName + "/_password", body);
        if (BUILTIN.equals(username)) {
            writeSecret(client, ref, ref.instanceName() + ELASTIC_SECRET_SUFFIX, BUILTIN, newPassword);
        }
        return newPassword;
    }

    @Override
    public List<String> roles(KubernetesClient client, MiddlewareUserRef ref) {
        return ROLES;
    }

    // ---------- 纯函数 ----------

    static List<String> esRoles(String role) {
        return switch (role == null ? "" : role) {
            case "只读" -> List.of("viewer");
            case "读写" -> List.of("editor");
            case "管理员" -> List.of("superuser");
            // 非法角色不能落回最宽或最窄的默认值：直接拒绝
            default -> throw new CloudPlatformException(EnumResponseType.ERROR,
                    "不支持的角色：" + role + "（可选：" + String.join(" / ", ROLES) + "）");
        };
    }

    static void requireRole(String role) {
        esRoles(role);   // 只做校验
    }

    static Map<String, Object> userBody(String password, String role) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("password", password);
        body.put("roles", esRoles(role));
        return body;
    }

    static String esUsername(String username) {
        return PLATFORM_PREFIX + username;
    }

    static String displayName(String esName) {
        return esName != null && esName.startsWith(PLATFORM_PREFIX)
                ? esName.substring(PLATFORM_PREFIX.length()) : esName;
    }

    static boolean isShown(String esName) {
        return BUILTIN.equals(esName) || (esName != null && esName.startsWith(PLATFORM_PREFIX));
    }

    // ---------- 内部 ----------

    private static MiddlewareUserView view(String username, String role, boolean builtin,
                                           MiddlewareUserRef ref) {
        MiddlewareUserView v = new MiddlewareUserView();
        v.setUsername(username);
        v.setRole(role);
        v.setBuiltin(builtin);
        v.setSecretName(builtin ? ref.instanceName() + ELASTIC_SECRET_SUFFIX
                : UsersSecret.name(ref.instanceName()));
        // 平台侧的 <实例名>-users 用"平台用户名"作 key（不带 ES 的前缀）
        v.setSecretKey(username);
        return v;
    }

    private String call(KubernetesClient client, MiddlewareUserRef ref,
                        String method, String path, Object body) {
        Secret elastic = client.secrets().inNamespace(ref.namespace())
                .withName(ref.instanceName() + ELASTIC_SECRET_SUFFIX).get();
        String password = elastic == null || elastic.getData() == null
                ? null : decode(elastic.getData().get(BUILTIN));
        if (password == null || password.isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "找不到 Elasticsearch 的超管凭据 Secret：" + ref.instanceName() + ELASTIC_SECRET_SUFFIX);
        }
        Secret publicCerts = client.secrets().inNamespace(ref.namespace())
                .withName(ref.instanceName() + CA_SECRET_SUFFIX).get();
        Secret caKeyPair = client.secrets().inNamespace(ref.namespace())
                .withName(ref.instanceName() + CA_KEYPAIR_SUFFIX).get();
        String caPem = caPem(publicCerts, caKeyPair);
        boolean https = caPem != null;

        String base = (https ? "https://" : "http://")
                + ref.instanceName() + SERVICE_SUFFIX + "." + ref.namespace() + ".svc:9200";
        String auth = Base64.getEncoder().encodeToString(
                (BUILTIN + ":" + password).getBytes(StandardCharsets.UTF_8));

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(TIMEOUT)
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/json");
        try {
            HttpClient http = https
                    ? HttpClient.newBuilder().connectTimeout(TIMEOUT)
                            .sslContext(sslContext(caPem)).build()
                    : HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
            HttpRequest request = switch (method) {
                case "GET" -> builder.GET().build();
                case "DELETE" -> builder.DELETE().build();
                default -> builder.method(method, HttpRequest.BodyPublishers.ofString(
                        body == null ? "" : JSON.writeValueAsString(body), StandardCharsets.UTF_8)).build();
            };
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "Elasticsearch 拒绝了请求（HTTP " + response.statusCode() + "）：" + brief(response.body()));
            }
            return response.body();
        } catch (CloudPlatformException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "调用 Elasticsearch 接口失败（" + base + "）：" + e.getMessage());
        }
    }

    /**
     * 取实例的 CA（Base64 PEM）：有 TLS 返回 CA，明确没开 TLS 返回 null。
     *
     * <p>开了 TLS 却拿不到 {@code ca.crt} 时直接报错 —— 静默退回 http 只会得到一个没有任何
     * 线索的 {@code null}。
     */
    static String caPem(Secret publicCerts, Secret caKeyPair) {
        String ca = publicCerts == null || publicCerts.getData() == null
                ? null : publicCerts.getData().get(CA_KEY);
        if (ca != null && !ca.isBlank()) {
            return ca;
        }
        if (publicCerts != null || caKeyPair != null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "该 Elasticsearch 实例开了 HTTP TLS，但找不到 CA 证书：期望 Secret <实例名>"
                            + CA_SECRET_SUFFIX + " 里有 " + CA_KEY
                            + "（ECK 把 CA 公钥放这里，" + CA_KEYPAIR_SUFFIX + " 里只有 tls.crt/tls.key）");
        }
        return null;
    }

    /** 只用实例自己的 CA 建信任链（PEM 里可能有多张证书）。 */
    static SSLContext sslContext(String caPemBase64) throws Exception {
        byte[] pem = Base64.getDecoder().decode(caPemBase64);
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        int i = 0;
        try (var in = new ByteArrayInputStream(pem)) {
            for (Certificate cert : factory.generateCertificates(in)) {
                store.setCertificateEntry("ca-" + i++, cert);
            }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), null);
        return context;
    }

    private static void writeSecret(KubernetesClient client, MiddlewareUserRef ref,
                                    String secretName, String key, String value) {
        Secret current = client.secrets().inNamespace(ref.namespace()).withName(secretName).get();
        Map<String, String> data = current == null || current.getData() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(current.getData());
        data.put(key, Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        Secret target = current == null
                ? new SecretBuilder().withNewMetadata().withName(secretName).withNamespace(ref.namespace())
                        .endMetadata().withData(data).build()
                : new SecretBuilder(current).withData(data).build();
        client.secrets().inNamespace(ref.namespace()).resource(target).createOrReplace();
    }

    private static String decode(String base64) {
        return base64 == null ? "" : new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    }

    private static String brief(String body) {
        if (body == null) {
            return "";
        }
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "…";
    }
}
