package com.coding.platformapi.services.overview;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformHealthProbeTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void respond(String path, int code, String body) {
        server.createContext(path, ex -> {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
    }

    @Test
    void countsSelfAndSkipsBlankTargets() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        respond("/good", 200, "{\"status\":\"UP\"}");
        respond("/bad", 503, "{\"status\":\"DOWN\"}");
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        Map<String, String> targets = new LinkedHashMap<>();
        targets.put("good", base + "/good");
        targets.put("bad", base + "/bad");
        targets.put("gateway", "");

        PlatformHealthProbe.ServiceHealth h = new PlatformHealthProbe(targets).probe();

        assertThat(h.up()).isEqualTo(2);
        assertThat(h.total()).isEqualTo(3);
        assertThat(h.down()).containsExactly("bad");
    }

    @Test
    void okStatusWithoutUpBodyCountsAsDown() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        respond("/login", 200, "<html>login</html>");
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        PlatformHealthProbe.ServiceHealth h = new PlatformHealthProbe(Map.of("auth", base + "/login")).probe();

        assertThat(h.up()).isEqualTo(1);
        assertThat(h.total()).isEqualTo(2);
        assertThat(h.down()).containsExactly("auth");
    }
}
