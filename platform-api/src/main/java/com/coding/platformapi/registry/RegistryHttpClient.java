package com.coding.platformapi.registry;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.platformapi.outbound.OutboundProxy;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * 镜像仓库的只读出站。设计见 spec §2.4。
 *
 * <p>与 S0.2 的 {@code CredentialProbeClient} **同一条路径与同一套规矩**：显式超时、
 * <b>重定向 NEVER</b>、Basic 认证、失败转业务码。
 *
 * <p>⚠️ <b>重定向必须真正关掉</b>：{@code SimpleClientHttpRequestFactory} 基于
 * {@code HttpURLConnection}，**没有**关重定向的 API 且默认跟随 —— S0.2 终审抓到过一个 Critical
 * 就是「javadoc 声称 NEVER、代码却在跟随」。这里直接用 {@link JdkClientHttpRequestFactory} +
 * 显式 {@code HttpClient.Redirect.NEVER}。
 *
 * <p>⚠️ <b>URL 由调用方给，且必须由来源的 endpoint 拼出来</b> —— 本类不做任何拼接，
 * 也就不可能拿用户输入里的 host 去发请求（spec §4.2 的硬边界 1）。
 *
 * <p><b>代理</b>：由 {@link OutboundProxy} 注入的 {@code ProxySelector} 决定 ——
 * **只有白名单里的目标走代理**（Docker Hub / 外网 git host），内网 Harbor 与其余出站一律直连。
 * 配置见 {@code platform.outbound.proxy.*}。
 */
@Slf4j
@Component
public class RegistryHttpClient {

    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private static final int READ_TIMEOUT_MS = 10_000;

    private final RestClient restClient;

    /** ⚠️ 必须标 {@code @Autowired} —— 本类还有第二个（包级私有、给测试注入 RestClient 的）构造器。 */
    @Autowired
    public RegistryHttpClient(OutboundProxy proxy) {
        HttpClient http = HttpClient.newBuilder()
                .proxy(proxy.selector())
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(READ_TIMEOUT_MS));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** 测试用：注入一个绑定了 {@code MockRestServiceServer} 的 RestClient。 */
    RegistryHttpClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * GET 一个绝对 URL，返回响应体。
     *
     * <p>非 2xx 一律抛 {@link CloudPlatformException}：调用方（suggest / browse）各自决定
     * 是**静默吞掉**（补全）还是**往上抛**（浏览）—— 见 spec §5.3 的姿态差异。
     */
    public String get(CredentialRef cred, String url) {
        return get(cred, url, null);
    }

    /**
     * GET，**用一个 Bearer token 而不是凭据本身**。
     *
     * <p>存在的理由：Docker Hub 的站点 API（{@code hub.docker.com/v2}）**不认 Basic** ——
     * 2026-10-15 实测：带一对错误凭据的 Basic 头照样 200，等于被当成匿名请求。
     * 它只认 {@code POST /v2/users/login/} 换来的 JWT（错误 Bearer → 401）。
     * 所以那一档必须先换 token，再用这个入口发请求。
     */
    public String getWithBearer(String url, String bearerToken) {
        return get(null, url, "Bearer " + bearerToken);
    }

    private String get(CredentialRef cred, String url, String authorizationHeader) {
        try {
            RestClient.RequestHeadersSpec<?> spec = restClient.get().uri(URI.create(url));
            if (authorizationHeader != null) {
                spec.header(HttpHeaders.AUTHORIZATION, authorizationHeader);
            } else if (hasCredential(cred)) {
                spec.header(HttpHeaders.AUTHORIZATION, basic(cred));
            }
            return send(spec, url, "凭据不通过，或该凭据无权访问此仓库");
        } catch (CloudPlatformException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            // URI.create 对「不是绝对地址 / 含非法字符」抛这个 —— 那是**地址格式**问题，不是网络问题。
            // S0.2 的教训：把它报成「网络不可达」会把管理员引去查平台出站策略。
            log.warn("镜像仓库地址格式非法 url={}", url, e);
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                    "镜像仓库地址格式不对：请检查该连接配置的 endpoint");
        } catch (Exception e) {
            log.warn("镜像仓库请求失败 url={}", url, e);
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                    "无法连接镜像仓库：" + e.getMessage());
        }
    }

    /**
     * POST 一个 JSON 体，返回响应体。**不带 Authorization**（凭据在 body 里）——
     * 只为 Docker Hub 的登录换 token 那一步存在。
     *
     * @param unauthorizedMessage 401/403 时的文案。这一跳的 401 含义很明确（用户名/令牌不对），
     *                            用通用那句「凭据不通过，或该凭据无权访问此仓库」会把管理员
     *                            引去查仓库权限
     */
    public String postJson(String url, Object body, String unauthorizedMessage) {
        try {
            return send(restClient.post().uri(URI.create(url))
                            .contentType(MediaType.APPLICATION_JSON).body(body),
                    url, unauthorizedMessage);
        } catch (CloudPlatformException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            log.warn("登录地址格式非法 url={}", url, e);
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                    "镜像仓库地址格式不对：请检查该连接配置的 endpoint");
        } catch (Exception e) {
            log.warn("镜像仓库登录请求失败 url={}", url, e);
            throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                    "无法连接镜像仓库：" + e.getMessage());
        }
    }

    /**
     * 两个入口共用的状态处理。
     *
     * <p>⚠️ 注册顺序要紧：RestClient 按**注册顺序**取第一个匹配的 handler。
     * 所以**窄的（401/403）必须先注册**，宽的（非 2xx）后注册 ——
     * 反过来的话 401 会拿到「返回 401」而不是那句二义文案，
     * 而二义文案正是本模块最要防的误判（spec §5.4）。实测确认过。
     */
    private String send(RestClient.RequestHeadersSpec<?> spec, String url, String unauthorizedMessage) {
        return spec.retrieve()
                .onStatus(status -> status.value() == 401 || status.value() == 403, (req, res) -> {
                    throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID, unauthorizedMessage);
                })
                .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                    throw new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                            "镜像仓库返回 " + res.getStatusCode().value());
                })
                .body(String.class);
    }

    /**
     * 匿名的 ref（username 与 secret 都空）**不带 Authorization 头** ——
     * 否则会发出 `Basic Og==`，一个只有冒号的空 Basic，有些 registry 会把它判成「凭据错误」。
     */
    private static boolean hasCredential(CredentialRef cred) {
        return StringUtils.hasText(cred.username()) || StringUtils.hasText(cred.secret());
    }

    private static String basic(CredentialRef cred) {
        String raw = (cred.username() == null ? "" : cred.username())
                + ":" + (cred.secret() == null ? "" : cred.secret());
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
