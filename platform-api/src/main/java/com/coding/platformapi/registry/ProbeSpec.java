package com.coding.platformapi.registry;

/**
 * 一次「测试连接」的规格。由各档自己给，因为下面两件事都是方言相关的：
 *
 * <ul>
 *   <li><b>URL</b> —— Docker Hub 的 API 站点是 {@code hub.docker.com}，**不是**它的 endpoint
 *       {@code docker.io}；只有 Harbor 才是 {@code <endpoint>/v2/}。</li>
 *   <li><b>404 的含义</b> —— 对 Harbor 是「这个地址不是个 registry」，
 *       对 Docker Hub 是「公共 API 被中间件改写了」。同一句写死会把管理员**引去查一个没问题的地址**。</li>
 * </ul>
 *
 * <p>⚠️ 出站那一半（连接/读超时、{@code Redirect.NEVER}、{@code URI.create} 的格式错归因）
 * <b>仍然只在 {@code CredentialProbeClient} 一处</b>，各档只给 URL 与文案 ——
 * 那几条安全要求散到各档去实现，迟早会漏掉一条。
 *
 * <p>设计见 docs/superpowers/specs/2026-10-14-registry-dockerhub-design.md §4。
 */
public record ProbeSpec(String url, String notFoundMessage) {
}
