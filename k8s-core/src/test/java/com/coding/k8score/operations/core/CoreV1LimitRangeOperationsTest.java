package com.coding.k8score.operations.core;

import com.coding.k8score.converter.impl.core.CoreV1LimitRangeConverter;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CoreV1LimitRangeOperationsTest {

    private final KubernetesClient client = mock(KubernetesClient.class);
    private final CoreV1LimitRangeOperations ops =
            new CoreV1LimitRangeOperations(client, new CoreV1LimitRangeConverter());

    @Test
    void api_version_is_core_v1() {
        assertThat(ops.apiVersion()).isEqualTo("v1");
    }
}
