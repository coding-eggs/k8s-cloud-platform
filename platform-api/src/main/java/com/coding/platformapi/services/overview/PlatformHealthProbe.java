package com.coding.platformapi.services.overview;

import com.coding.platformapi.configs.OverviewProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * 平台自身服务的健康（总览顶部「平台服务 x/y」）。
 * <p>并行 GET 各服务的 {@code /actuator/health}（auth / k8s-server 都已匿名放行 {@code /actuator/**}），
 * 每个 2s 超时；2xx 且 body 的 status 为 UP 才算 UP —— 只看状态码会把被网关重定向到登录页的 200 也算成健康。
 * platform-api 自身能回答这个请求，固定算 UP。
 */
@Component
public class PlatformHealthProbe {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final Pattern UP = Pattern.compile("\"status\"\s*:\s*\"UP\"");

    public record ServiceHealth(int up, int total, List<String> down) {
    }

    private final Map<String, String> targets;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    @Autowired
    public PlatformHealthProbe(OverviewProperties properties) {
        this(properties.getHealthTargets());
    }

    PlatformHealthProbe(Map<String, String> targets) {
        this.targets = targets == null ? Map.of() : targets;
    }

    public ServiceHealth probe() {
        Map<String, CompletableFuture<Boolean>> calls = new LinkedHashMap<>();
        targets.forEach((name, url) -> {
            if (url != null && !url.isBlank()) {
                calls.put(name, check(url));
            }
        });
        List<String> down = new ArrayList<>();
        calls.forEach((name, f) -> {
            if (!f.join()) {
                down.add(name);
            }
        });
        int total = calls.size() + 1;
        return new ServiceHealth(total - down.size(), total, down);
    }

    private CompletableFuture<Boolean> check(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build();
            return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenApply(r -> r.statusCode() / 100 == 2 && r.body() != null && UP.matcher(r.body()).find())
                    .exceptionally(e -> false);
        } catch (RuntimeException e) {
            // URL 写错（URI.create 抛）也只算 down，不让总览整页失败
            return CompletableFuture.completedFuture(false);
        }
    }
}
