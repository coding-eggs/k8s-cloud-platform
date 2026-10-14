package com.coding.platformapi.outbound;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 出站代理：<b>只有白名单里的目标走代理，其余一律直连</b>。
 *
 * <h3>为什么是白名单而不是「一个全局代理」</h3>
 * 平台只有两类出站是朝公网的（Docker Hub / 外网 git host），其余全是内网
 * （k8s-server {@code 127.0.0.1:8080}、Thanos、Prometheus）。配一个"替所有出站"的代理
 * 意味着**代理填错时内网流量也被导出去** —— 那是最难查的一类故障。
 * 白名单为空 = 全直连，不给这个口子。
 *
 * <h3>精确匹配，不做后缀</h3>
 * {@code github.com} 就是 {@code github.com}，{@code raw.githubusercontent.com} **不会**命中。
 * 宁可让人多写一行，也不要"以为配了一个域名、其实整个子域都被导出去了"。
 *
 * <p>配置见 {@code application.yaml} 的 {@code platform.outbound.proxy.*}。
 * 使用者：{@link com.coding.platformapi.registry.RegistryHttpClient}（Docker Hub / Harbor）与
 * {@link com.coding.platformapi.credential.CredentialProbeClient}（「测试连接」打 git host / registry）。
 */
@Slf4j
@Component
public class OutboundProxy {

    private final String host;

    private final int port;

    private final Set<String> hosts;

    /**
     * @param hostsCsv 逗号分隔的 host 白名单（用字符串而不是 YAML list，
     *                 是为了让 {@code @Value} 的行为毫无歧义）
     */
    public OutboundProxy(@Value("${platform.outbound.proxy.host:}") String host,
                         @Value("${platform.outbound.proxy.port:0}") int port,
                         @Value("${platform.outbound.proxy.hosts:}") String hostsCsv) {
        this.host = host == null ? "" : host.trim();
        this.port = port;
        this.hosts = parse(hostsCsv);
    }

    /** 没配代理的形态 —— 测试用，也是「白名单为空」的正常表达。 */
    public static OutboundProxy disabled() {
        return new OutboundProxy("", 0, "");
    }

    public ProxySelector selector() {
        InetSocketAddress addr = (StringUtils.hasText(host) && port > 0)
                ? InetSocketAddress.createUnresolved(host, port)
                : null;
        if (addr == null && !hosts.isEmpty()) {
            log.warn("配了走代理的 host 白名单，但 proxy.host/port 不完整 —— 一律直连。hosts={}", hosts);
        }
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                if (addr != null && uri != null && uri.getHost() != null
                        && hosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
                    return List.of(new Proxy(Proxy.Type.HTTP, addr));
                }
                return List.of(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                // 这条很容易被忽略，而它正是「配了代理还是不通」的第一现场
                log.warn("出站代理连接失败 uri={} proxy={}", uri, sa, ioe);
            }
        };
    }

    private static Set<String> parse(String csv) {
        if (!StringUtils.hasText(csv)) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
