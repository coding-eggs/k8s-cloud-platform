package com.coding.platformapi.app;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class AppCanaryTest {

    private static Map<String, Object> svc(String tag) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("imageRepo", "harbor.local/demo/api");
        d.put("imageTag", tag);
        d.put("port", 8080);
        d.put("expose", "gateway-http");
        return d;
    }

    private static AppCanary.Spec spec() {
        return new AppCanary.Spec(svc("2"), 1, 10, new AppCanary.Header("x-canary", "true"));
    }

    @Test
    void roundTripsThroughSnapshotJson() {
        Map<String, Map<String, Object>> services = new TreeMap<>();
        services.put("api", AppCanary.with(svc("1"), spec()));
        AppSnapshot back = AppSnapshot.parse(new AppSnapshot(services, List.of()).toJson());

        AppCanary.Spec s = AppCanary.of(back.services().get("api"));
        assertThat(s).isNotNull();
        assertThat(s.weight()).isEqualTo(10);
        assertThat(s.replicas()).isEqualTo(1);
        assertThat(s.header()).isEqualTo(new AppCanary.Header("x-canary", "true"));
        assertThat(s.desired()).containsEntry("imageTag", "2");
    }

    @Test
    void headerlessSpecRoundTripsAsNull() {
        Map<String, Object> withCanary = AppCanary.with(svc("1"),
                new AppCanary.Spec(svc("2"), 2, 30, null));
        AppSnapshot back = AppSnapshot.parse(new AppSnapshot(new TreeMap<>(Map.of("api", withCanary)), List.of()).toJson());
        assertThat(AppCanary.of(back.services().get("api")).header()).isNull();
    }

    @Test
    void ofReturnsNullWithoutCanaryBlock() {
        assertThat(AppCanary.of(svc("1"))).isNull();
        assertThat(AppCanary.of(null)).isNull();
    }

    @Test
    void withoutLeavesStableUntouched() {
        Map<String, Object> stable = svc("1");
        Map<String, Object> withCanary = AppCanary.with(stable, spec());
        assertThat(stable).doesNotContainKey(AppCanary.KEY);   // with 不改入参
        assertThat(AppCanary.without(withCanary)).isEqualTo(stable);
    }

    @Test
    void stripRemovesEveryCanaryBlock() {
        Map<String, Map<String, Object>> services = new TreeMap<>();
        services.put("api", AppCanary.with(svc("1"), spec()));
        services.put("web", svc("1"));
        AppSnapshot stripped = AppCanary.strip(new AppSnapshot(services, List.of()));
        assertThat(stripped.services().get("api")).doesNotContainKey(AppCanary.KEY);
        assertThat(stripped.services().get("web")).isEqualTo(svc("1"));
    }

    @Test
    void activeKeysListsOnlyServicesInCanary() {
        Map<String, Map<String, Object>> services = new TreeMap<>();
        services.put("web", AppCanary.with(svc("1"), spec()));
        services.put("api", AppCanary.with(svc("1"), spec()));
        services.put("job", svc("1"));
        assertThat(AppCanary.activeKeys(new AppSnapshot(services, List.of()))).containsExactly("api", "web");
        assertThat(AppCanary.nodeKey("api")).isEqualTo("api-canary");
    }

    @Test
    void externalDefaultsToTrueForOldBlocks() {
        Map<String, Object> svc = new LinkedHashMap<>(Map.of("imageTag", "1"));
        svc.put("canary", Map.of("desired", Map.of("imageTag", "2"), "replicas", 1, "weight", 10));
        assertThat(AppCanary.of(svc).external()).isTrue();
    }

    @Test
    void externalRoundTrips() {
        Map<String, Object> out = AppCanary.with(Map.of("imageTag", "1"),
                new AppCanary.Spec(Map.of("imageTag", "2"), 1, 10, null, false));
        assertThat(AppCanary.of(out).external()).isFalse();
    }
}
