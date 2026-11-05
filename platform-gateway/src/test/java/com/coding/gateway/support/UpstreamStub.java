package com.coding.gateway.support;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 记录收到的请求（方法 + 原始 URI + Authorization 头 + 网关附的原始路径头）并回 200 的上游桩。测试专用。 */
public final class UpstreamStub implements AutoCloseable {

    public record Received(String method, String uri, String authorization, String originalPath) {}

    private final BlockingQueue<Received> received = new LinkedBlockingQueue<>();
    private final DisposableServer server;

    public UpstreamStub() {
        server = HttpServer.create().port(0)
                .handle((req, res) -> {
                    received.add(new Received(req.method().name(), req.uri(), req.requestHeaders().get("Authorization"),
                            req.requestHeaders().get("X-Platform-Original-Path")));
                    return res.header("Content-Type", "application/json")
                            .sendString(Mono.just("{\"code\":200,\"msg\":\"ok\",\"data\":null}"));
                })
                .bindNow();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.port();
    }

    public Received take() throws InterruptedException {
        Received r = received.poll(5, TimeUnit.SECONDS);
        if (r == null) {
            throw new AssertionError("上游 5 秒内没有收到请求");
        }
        return r;
    }

    public boolean nothingReceived() {
        return received.isEmpty();
    }

    @Override
    public void close() {
        server.disposeNow();
    }
}
