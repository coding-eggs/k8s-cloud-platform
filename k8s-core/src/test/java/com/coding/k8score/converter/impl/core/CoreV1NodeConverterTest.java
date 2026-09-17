package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.NodeDTO;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeAddressBuilder;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.NodeConditionBuilder;
import io.fabric8.kubernetes.api.model.NodeSpecBuilder;
import io.fabric8.kubernetes.api.model.NodeStatusBuilder;
import io.fabric8.kubernetes.api.model.NodeSystemInfoBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.TaintBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CoreV1NodeConverterTest {

    private final CoreV1NodeConverter c = new CoreV1NodeConverter();

    @Test
    void revert_maps_identity_status_ips_and_capacity() {
        Node n = new NodeBuilder()
                .withMetadata(new ObjectMetaBuilder().withName("node1")
                        .withLabels(Map.of(
                                "node-role.kubernetes.io/control-plane", "",
                                "kubernetes.io/os", "linux")).build())
                .withSpec(new NodeSpecBuilder().withUnschedulable(true)
                        .withTaints(List.of(
                                new TaintBuilder().withKey("dedicated").withValue("gpu").withEffect("NoSchedule").build()))
                        .build())
                .withStatus(new NodeStatusBuilder()
                        .withNodeInfo(new NodeSystemInfoBuilder()
                                .withKubeletVersion("v1.29.0").withOsImage("Ubuntu 22.04")
                                .withArchitecture("amd64").withKernelVersion("5.15")
                                .withContainerRuntimeVersion("containerd://1.7").build())
                        .withAddresses(List.of(
                                new NodeAddressBuilder().withType("InternalIP").withAddress("192.168.85.162").build(),
                                new NodeAddressBuilder().withType("ExternalIP").withAddress("1.2.3.4").build()))
                        .addToCapacity("cpu", Quantity.parse("8"))
                        .addToCapacity("memory", Quantity.parse("16Gi"))
                        .addToAllocatable("cpu", Quantity.parse("7920m"))
                        .addToAllocatable("memory", Quantity.parse("15Gi"))
                        .addToAllocatable("pods", Quantity.parse("110"))
                        .withConditions(List.of(
                                new NodeConditionBuilder().withType("Ready").withStatus("True")
                                        .withReason("KubeletReady").withMessage("kubelet is ready").build()))
                        .build())
                .build();

        NodeDTO d = c.revert(n);
        assertThat(d.getName()).isEqualTo("node1");
        assertThat(d.getStatus()).isEqualTo("True"); // 透传 Ready 条件的 status 值（True=Ready）
        assertThat(d.getUnschedulable()).isTrue();
        assertThat(d.getRoles()).containsExactly("control-plane");
        assertThat(d.getKubeletVersion()).isEqualTo("v1.29.0");
        assertThat(d.getOs()).isEqualTo("linux");
        assertThat(d.getInternalIp()).isEqualTo("192.168.85.162");
        assertThat(d.getExternalIp()).isEqualTo("1.2.3.4");
        assertThat(d.getCpuCapacity()).isEqualByComparingTo("8");
        assertThat(d.getMemoryAllocatable()).isEqualByComparingTo("16106127360"); // 15Gi → 字节
        assertThat(d.getCpuAllocatable()).isEqualByComparingTo("7.92");            // 7920m → 核数
        assertThat(d.getPodsLimit()).isEqualTo(110L);
        assertThat(d.getConditions()).hasSize(1);
        assertThat(d.getTaints()).hasSize(1).first().extracting("key").isEqualTo("dedicated");
    }

    @Test
    void revert_null_returns_null() {
        assertThat(c.revert(null)).isNull();
    }
}
