package com.coding.k8score.operations.core;

import com.coding.common.models.k8s.dto.NodePodStatDTO;
import com.coding.k8score.converter.impl.core.CoreV1NodeConverter;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CoreV1NodeOperationsTest {

    private final CoreV1NodeOperations ops = new CoreV1NodeOperations(mock(KubernetesClient.class), new CoreV1NodeConverter());

    private Pod pod(String ns, String name, String nodeName, String cpuReq, String memReq) {
        ResourceRequirementsBuilder rr = new ResourceRequirementsBuilder().addToRequests("cpu", Quantity.parse(cpuReq));
        if (memReq != null) {
            rr.addToRequests("memory", Quantity.parse(memReq));
        }
        Container c = new ContainerBuilder().withName("c").withResources(rr.build()).build();
        return new PodBuilder()
                .withMetadata(new ObjectMetaBuilder().withNamespace(ns).withName(name).build())
                .withSpec(new PodSpecBuilder().withNodeName(nodeName).withContainers(c).build())
                .build();
    }

    @Test
    void aggregate_groups_by_nodeName_and_sums_requests() {
        List<Pod> pods = List.of(
                pod("a", "p1", "n1", "200m", "128Mi"),
                pod("b", "p2", "n1", "1", null),      // 无 memory request
                pod("c", "p3", "n2", "500m", "1Gi")
        );

        List<NodePodStatDTO> stats = ops.aggregate(pods);

        assertThat(stats).hasSize(2);
        NodePodStatDTO n1 = stats.stream().filter(s -> s.getNodeName().equals("n1")).findFirst().orElseThrow();
        NodePodStatDTO n2 = stats.stream().filter(s -> s.getNodeName().equals("n2")).findFirst().orElseThrow();

        assertThat(n1.getPodCount()).isEqualTo(2L);
        assertThat(n1.getCpuRequestMillicores()).isEqualTo(1200L); // 200m + 1000m
        assertThat(n1.getMemRequestBytes()).isEqualTo(128L * 1024 * 1024);

        assertThat(n2.getPodCount()).isEqualTo(1L);
        assertThat(n2.getCpuRequestMillicores()).isEqualTo(500L);
        assertThat(n2.getMemRequestBytes()).isEqualTo(1024L * 1024 * 1024);
    }

    @Test
    void aggregate_ignores_pods_without_nodeName() {
        Pod noNode = new PodBuilder()
                .withMetadata(new ObjectMetaBuilder().withNamespace("a").withName("p0").build())
                .withSpec(new PodSpecBuilder().build()) // 无 nodeName
                .build();
        assertThat(ops.aggregate(List.of(noNode))).isEmpty();
    }
}
