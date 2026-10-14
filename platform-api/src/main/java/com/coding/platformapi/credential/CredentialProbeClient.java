package com.coding.platformapi.credential;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * 测试连接的**出站**那一半。规则全在 {@link CredentialProbe}（纯函数、可单测），
 * 本类只负责「发请求 + 把响应喂给规则」。
 *
 * <p>超时范式与 {@code PromDiscoveryClient} 一致（{@code SimpleClientHttpRequestFactory} 显式设置），
 * **绝不用默认的无限等待** —— 对端黑洞会把请求线程挂住（spec §5.5）。
 *
 * <p><b>重定向策略 {@code NEVER}</b>（spec §5.2）：跟随重定向会让「通不通」变成「对端愿不愿意带你跳」，
 * 而且 {@code Authorization} 头在重定向中的传递规则是 host / 跳转类型相关的 ——
 * 一旦被丢弃就是假阴性，现象是「地址明明对、凭据明明对，就是不通」，极难排查。
 * 因此 3xx 一律按状态码返回，由 {@link CredentialProbe} 转成「对端要求地址为 X」的可操作提示。
 *
 * <p>⚠️ 这条要求**必须由构造器里的 {@code followRedirects(NEVER)} 真正落实** ——
 * 早期版本用 {@code SimpleClientHttpRequestFactory}，而它基于 {@code HttpURLConnection}，
 * **没有任何关重定向的 API** 且默认跟随，于是「文档写 NEVER、代码在跟随」。
 * 现在改用 {@link JdkClientHttpRequestFactory} + 显式的 {@code HttpClient.Redirect.NEVER}。
 * {@code CredentialProbeClientTest} 里有一条打本地 {@code HttpServer} 的用例钉住它。
 */
@Slf4j
@Component
public class CredentialProbeClient {

    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private static final int READ_TIMEOUT_MS = 10_000;

    private final RestClient restClient;

    public CredentialProbeClient() {
        // ⚠️ 重定向必须**真正**关掉 —— 这是 spec §5.2 的硬要求，而
        // SimpleClientHttpRequestFactory（HttpURLConnection）**根本没有**关重定向的 API，
        // 它的默认行为就是跟随。曾经因此出现过「javadoc 声称 NEVER、代码却在跟随」。
        // 跟随的代价：跳转后 Authorization 可能被丢弃 → 目标返 401 → 报「凭据不通过」（假阴性，
        // 正是本模块最要防的那类误判），且「把 Location 给用户一键回填」那条路径永不触发。
        // JDK HttpClient 默认就是 NEVER，这里显式写出来让它有据可查。
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(READ_TIMEOUT_MS));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 测试用：注入一个绑定了 {@code MockRestServiceServer} 的 RestClient。
     * 默认构造器造的客户端不可注入，URL 拼接与 Basic 头就只能靠部署后手验。
     */
    CredentialProbeClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public ProbeResult probeGit(String repoUrl, String username, String secret) {
        return probe(CredentialProbe.gitProbeUrl(repoUrl), username, secret, true);
    }

    /**
     * 探测 registry。**URL 由调用方（各档的 {@code probeSpec}）给** ——
     * 不同档的 API 站点不一样，{@code <endpoint>/v2/} 只对 Harbor 成立
     * （Docker Hub 的 API 站在 {@code hub.docker.com}，不是它的 endpoint {@code docker.io}）。
     */
    public ProbeResult probeRegistry(String url, String username, String secret) {
        return probe(url, username, secret, false);
    }

    private ProbeResult probe(String url, String username, String secret, boolean git) {
        try {
            ProbeResult response = restClient.get()
                    .uri(java.net.URI.create(url))
                    .header(HttpHeaders.AUTHORIZATION, basic(username, secret))
                    .exchange((request, res) -> {
                        String body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        String location = res.getHeaders().getFirst(HttpHeaders.LOCATION);
                        int status = res.getStatusCode().value();
                        return git
                                ? CredentialProbe.classifyGit(status, body, location)
                                : CredentialProbe.classifyRegistry(status);
                    });
            return response != null ? response : ProbeResult.fail("对端返回空响应");
        } catch (IllegalArgumentException e) {
            // URI.create 对「不是绝对地址 / 含非法字符」抛这个：那是**地址格式**问题，不是网络问题。
            // 报成「网络不可达（请确认平台服务器能出站访问该地址）」会把管理员引去查平台出站策略，
            // 而且那句话会被写进 last_verify_error 长期显示在列表里 —— 错误归因的伤害。
            log.warn("凭据测试地址格式非法 url={}", url, e);
            return ProbeResult.fail("地址格式不对：请填以 http:// 或 https:// 开头的完整地址");
        } catch (Exception e) {
            // 不可达是最需要提示到位的一类：平台服务器必须能出站访问该地址（spec §0.1 的环境提醒）
            log.warn("凭据测试连接失败 url={}", url, e);
            return ProbeResult.fail("网络不可达：" + e.getMessage()
                    + "（请确认平台服务器能出站访问该地址）");
        }
    }

    private static String basic(String username, String secret) {
        String raw = (username == null ? "" : username) + ":" + (secret == null ? "" : secret);
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
