package com.coding.platformapi.logs;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class EsLogClientTest {

    @Test
    void searchPostsToTheDailyIndicesAndIgnoresMissingOnes() {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        EsLogClient client = new EsLogClient((p, b) -> {
            path.set(p);
            body.set(b);
            return "{\"hits\":{\"hits\":[]}}";
        }, "http://es:9200");

        client.search("otel-logs-2026.10.19", Map.of("size", 1));

        assertThat(path.get())
                .isEqualTo("/otel-logs-2026.10.19/_search?ignore_unavailable=true&allow_no_indices=true");
        assertThat(body.get()).contains("\"size\":1");
    }
}
