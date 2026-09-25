package com.coding.k8score.operations.core;

import com.coding.k8score.converter.impl.core.CoreV1ResourceQuotaConverter;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CoreV1ResourceQuotaOperationsTest {

    private final KubernetesClient client = mock(KubernetesClient.class);
    private final CoreV1ResourceQuotaOperations ops =
            new CoreV1ResourceQuotaOperations(client, new CoreV1ResourceQuotaConverter());

    @Test
    void api_version_is_core_v1() {
        assertThat(ops.apiVersion()).isEqualTo("v1");
    }
}
