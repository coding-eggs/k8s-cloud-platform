package com.coding.platformapi.app;

import com.coding.data.models.system.PlatformAppService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppPendingTest {

    @Test
    void canaryBlockIsNotAPendingChange() {
        Map<String, Object> stable = new LinkedHashMap<>(Map.of("imageRepo", "nginx", "imageTag", "1"));
        Map<String, Object> live = AppCanary.with(stable,
                new AppCanary.Spec(Map.of("imageRepo", "nginx", "imageTag", "2"), 1, 10, null));
        PlatformAppService row = new PlatformAppService();
        row.setServiceKey("api");
        row.setDesiredJson("{\"imageRepo\":\"nginx\",\"imageTag\":\"1\"}");

        assertThat(AppPending.compute(Map.of("api", live), List.of(row))).isEmpty();
    }
}
