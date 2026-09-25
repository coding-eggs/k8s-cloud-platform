package com.coding.k8score.operations.core;

import com.coding.k8score.converter.impl.core.CoreV1NamespaceConverter;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CoreV1NamespaceOperationsTest {

    private final KubernetesClient client = mock(KubernetesClient.class);
    private final CoreV1NamespaceOperations ops =
            new CoreV1NamespaceOperations(client, new CoreV1NamespaceConverter());

    @Test
    void api_version_is_core_v1() {
        assertThat(ops.apiVersion()).isEqualTo("v1");
    }
}
