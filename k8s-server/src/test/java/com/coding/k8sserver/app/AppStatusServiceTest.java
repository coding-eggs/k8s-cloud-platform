package com.coding.k8sserver.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppStatusServiceTest {

    @Test
    void deploymentRequiresObservedGenerationAndEnoughReplicas() {
        assertThat(AppStatusService.deploymentState(2, 2, true)).isEqualTo("READY");
        assertThat(AppStatusService.deploymentState(2, 1, true)).isEqualTo("PENDING");
        // 代际还没被控制器观测到：即便副本数暂时对得上也不能算就绪
        assertThat(AppStatusService.deploymentState(2, 2, false)).isEqualTo("PENDING");
    }

    @Test
    void statefulSetOnlyLooksAtReadyReplicas() {
        assertThat(AppStatusService.statefulSetState(3, 3)).isEqualTo("READY");
        assertThat(AppStatusService.statefulSetState(3, 2)).isEqualTo("PENDING");
        assertThat(AppStatusService.statefulSetState(1, 0)).isEqualTo("PENDING");
    }
}
