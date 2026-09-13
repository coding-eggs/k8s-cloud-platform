package com.coding.auth.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;

/**
 * 带缓存的远程 JWK Set 获取服务
 * 支持：
 * - Caffeine 本地缓存
 * - 自定义缓存时间
 * - 刷新提前（refresh ahead）
 * - 超时与连接配置
 */
@Slf4j
@Component
public class RemoteJwkSetCache {
    /**
     * 默认缓存时间：10 分钟
     */
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(10);

    /**
     * 缓存刷新提前时间（在缓存过期前多久开始异步刷新）
     */
    private static final Duration DEFAULT_REFRESH_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Caffeine Cache：key = jwkSetUrl，value = JWKSet
     */
    private final Cache<String, JWKSet> jwkSetCache;

    public RemoteJwkSetCache() {
        this.jwkSetCache = Caffeine.newBuilder()
                .expireAfterWrite(DEFAULT_CACHE_TTL)
                .maximumSize(500)                    // 最多缓存 500 个不同客户端的 JWK Set
                .recordStats()                       // 开启统计（可选，用于监控）
                .build();
    }

    /**
     * 获取 JWKSet（带缓存）
     */
    public JWKSet getJwkSet(String jwkSetUrl) {
        if (!StringUtils.hasText(jwkSetUrl)) {
            throw new IllegalArgumentException("jwkSetUrl 不能为空");
        }

        return jwkSetCache.get(jwkSetUrl, this::loadJwkSetFromRemote);
    }

    /**
     * 从远程加载 JWK Set（核心逻辑）
     */
    private JWKSet loadJwkSetFromRemote(String jwkSetUrl) {
        try {
            URL url = URI.create(jwkSetUrl).toURL();

            log.info("从远程加载 JWK Set: {}", jwkSetUrl);

            // 使用 JWKSourceBuilder 创建带缓存和刷新机制的 JWKSource
            JWKSet jwkSet = new JWKSet(createRemoteJWKSet(jwkSetUrl)
                    .get(new JWKSelector(new JWKMatcher.Builder().build()), null)) ;

            log.debug("成功加载并缓存 JWK Set: {}, keys count: {}", jwkSetUrl, jwkSet.getKeys().size());
            return jwkSet;

        } catch (Exception e) {
            log.error("加载远程 JWK Set 失败: {}", jwkSetUrl, e);
            throw new RuntimeException("无法从 " + jwkSetUrl + " 获取 JWK Set", e);
        }
    }

    public static JWKSource<SecurityContext> createRemoteJWKSet(String jwkSetUri) throws MalformedURLException {
        DefaultResourceRetriever retriever = new DefaultResourceRetriever(30000, 30000);

        return JWKSourceBuilder
                .create(URI.create(jwkSetUri).toURL(), retriever)
                //缓存事件，刷新超时时间
                .cache(10L * 60 * 1000, 5 * 60 * 1000)
                .refreshAheadCache(5L * 60 * 1000, true)
                .build();
    }

    /**
     * 手动清除指定 URL 的缓存（密钥轮换后可调用）
     */
    public void evictCache(String jwkSetUrl) {
        if (StringUtils.hasText(jwkSetUrl)) {
            jwkSetCache.invalidate(jwkSetUrl);
            log.info("已清除 JWK Set 缓存: {}", jwkSetUrl);
        }
    }

    /**
     * 清除所有缓存
     */
    public void clearAllCache() {
        jwkSetCache.invalidateAll();
        log.info("已清除所有远程 JWK Set 缓存");
    }
}
