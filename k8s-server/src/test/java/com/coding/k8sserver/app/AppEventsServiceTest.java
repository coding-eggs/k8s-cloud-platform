package com.coding.k8sserver.app;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AppEventsServiceTest {

    @Test
    void belongsMatchesExactObjectNames() {
        Set<String> exact = Set.of("movie-web", "movie-api");
        assertThat(AppEventsService.belongs("movie-web", exact, List.of())).isTrue();
        assertThat(AppEventsService.belongs("movie-api", exact, List.of())).isTrue();
    }

    @Test
    void belongsMatchesReplicaSetsAndPodsByWorkloadPrefix() {
        List<String> prefixes = List.of("movie-web");
        assertThat(AppEventsService.belongs("movie-web-7d9f8c", Set.of(), prefixes)).isTrue();
        assertThat(AppEventsService.belongs("movie-web-7d9f8c-abcde", Set.of(), prefixes)).isTrue();
    }

    @Test
    void belongsRejectsOtherAppsAndBarePrefix() {
        // 另一个应用 / 另一个服务：前缀必须带 "-"，名字本身不算命中前缀
        assertThat(AppEventsService.belongs("movie-worker-1", Set.of(), List.of("movie-web"))).isFalse();
        assertThat(AppEventsService.belongs("movie-web", Set.of(), List.of("movie-web"))).isFalse();
        assertThat(AppEventsService.belongs(null, Set.of("movie-web"), List.of())).isFalse();
        assertThat(AppEventsService.belongs("  ", Set.of("movie-web"), List.of())).isFalse();
    }

    @Test
    void stampFallsBackToCreationTimestamp() {
        Event withLast = new EventBuilder().withLastTimestamp("2026-11-17T10:00:00Z").build();
        assertThat(AppEventsService.stampOf(withLast)).isEqualTo("2026-11-17T10:00:00Z");

        Event noLast = new EventBuilder()
                .withMetadata(new ObjectMetaBuilder().withCreationTimestamp("2026-11-17T09:00:00Z").build())
                .build();
        assertThat(AppEventsService.stampOf(noLast)).isEqualTo("2026-11-17T09:00:00Z");
    }
}
