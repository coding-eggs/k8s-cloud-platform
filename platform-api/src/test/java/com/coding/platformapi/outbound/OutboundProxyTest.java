package com.coding.platformapi.outbound;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出站代理的**分流规则**。纯函数，无 IO。
 *
 * <p>它守的核心不变量只有一条：<b>只有白名单里的 host 走代理，其余一律直连</b> ——
 * 尤其内网那几个（自建 Harbor / gitlab、k8s-server、Thanos、Prometheus）**绝不能被导出去**。
 * 把内网流量导到一个外网代理上是最难查的一类故障。
 */
class OutboundProxyTest {

    private static ProxySelector selector(String hosts) {
        return new OutboundProxy("192.168.85.1", 7890, hosts).selector();
    }

    @Test
    void listedHostsGoThroughTheProxy() {
        assertThat(selector("hub.docker.com,github.com").select(URI.create("https://hub.docker.com/v2/x")))
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.type()).isEqualTo(Proxy.Type.HTTP);
                    assertThat(p.address())
                            .isEqualTo(InetSocketAddress.createUnresolved("192.168.85.1", 7890));
                });
    }

    @Test
    void everythingElseStaysDirect() {
        ProxySelector sel = selector("hub.docker.com,github.com");

        assertThat(sel.select(URI.create("https://harbor.internal/api/v2.0/projects")))
                .containsExactly(Proxy.NO_PROXY);
        assertThat(sel.select(URI.create("http://192.168.31.80:19090/api/v1/query")))
                .containsExactly(Proxy.NO_PROXY);
        assertThat(sel.select(URI.create("http://127.0.0.1:8080/admin/pods")))
                .containsExactly(Proxy.NO_PROXY);
        assertThat(sel.select(URI.create("https://gitlab.internal/group/repo.git/info/refs")))
                .containsExactly(Proxy.NO_PROXY);
    }

    @Test
    void matchingIsExactNotSuffix() {
        // ⚠️ 不搞后缀匹配：宁可让人多写一行，也不要「以为配了一个域名、其实整个子域都被导出去了」
        ProxySelector sel = selector("github.com");

        assertThat(sel.select(URI.create("https://github.com/x"))).extracting(Proxy::type)
                .containsExactly(Proxy.Type.HTTP);
        assertThat(sel.select(URI.create("https://raw.githubusercontent.com/x")))
                .containsExactly(Proxy.NO_PROXY);
        assertThat(sel.select(URI.create("https://notgithub.com/x")))
                .containsExactly(Proxy.NO_PROXY);
        assertThat(sel.select(URI.create("https://github.com.evil.com/x")))
                .containsExactly(Proxy.NO_PROXY);
    }

    @Test
    void emptyAllowListMeansDirectEvenWhenAProxyAddressIsConfigured() {
        // 白名单为空 = 全直连。**不给「替所有出站」留口子** ——
        // 那正是「代理填错时把内网流量也导出去」的来源。
        assertThat(selector("").select(URI.create("https://hub.docker.com/v2/x")))
                .containsExactly(Proxy.NO_PROXY);
    }

    @Test
    void incompleteProxyAddressMeansDirect() {
        assertThat(new OutboundProxy("", 7890, "hub.docker.com").selector()
                .select(URI.create("https://hub.docker.com/v2/x")))
                .containsExactly(Proxy.NO_PROXY);
        assertThat(new OutboundProxy("192.168.85.1", 0, "hub.docker.com").selector()
                .select(URI.create("https://hub.docker.com/v2/x")))
                .containsExactly(Proxy.NO_PROXY);
    }

    @Test
    void hostMatchingIsCaseInsensitiveAndTrimsSpaces() {
        ProxySelector sel = selector(" GitHub.com , hub.docker.com ");
        assertThat(sel.select(URI.create("https://github.com/x")).get(0).type())
                .isEqualTo(Proxy.Type.HTTP);
        assertThat(sel.select(URI.create("https://HUB.DOCKER.COM/v2/x")).get(0).type())
                .isEqualTo(Proxy.Type.HTTP);
    }

    @Test
    void disabledMeansNeverProxy() {
        assertThat(OutboundProxy.disabled().selector().select(URI.create("https://hub.docker.com/v2/x")))
                .containsExactly(Proxy.NO_PROXY);
    }
}
