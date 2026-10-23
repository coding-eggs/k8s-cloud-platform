package com.coding.k8sserver.services;

import com.coding.k8score.factory.KubernetesClientFactory;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 定时任务只是调用者：探活与状态回写都在 {@link KubernetesClientFactory#probeAllClusters()} 里。 */
class ClusterAvailabilityProberTest {

    @Test
    void 定时任务委托给工厂探活() {
        KubernetesClientFactory clientFactory = mock(KubernetesClientFactory.class);

        new ClusterAvailabilityProber(clientFactory).probeAll();

        verify(clientFactory).probeAllClusters();
    }
}
