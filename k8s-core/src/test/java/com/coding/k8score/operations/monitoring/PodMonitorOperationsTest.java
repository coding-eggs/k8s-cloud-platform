package com.coding.k8score.operations.monitoring;

import com.coding.k8score.converter.impl.monitoring.PodMonitorConverter;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PodMonitorOperationsTest {

    @Test
    void apiVersion_is_monitoring_v1() {
        PodMonitorOperations ops = new PodMonitorOperations(mock(KubernetesClient.class), new PodMonitorConverter());
        assertThat(ops.apiVersion()).isEqualTo("monitoring.coreos.com/v1");
    }
}
