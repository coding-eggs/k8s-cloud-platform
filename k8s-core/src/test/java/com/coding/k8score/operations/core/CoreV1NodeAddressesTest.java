package com.coding.k8score.operations.core;

import com.coding.common.models.k8s.dto.NodeAddressDTO;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeAddressBuilder;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.NodeConditionBuilder;
import io.fabric8.kubernetes.api.model.NodeStatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 节点地址窄投影：只回 name + InternalIP/ExternalIP + Ready —— 容量/标签/污点/Pod 数一概不出。
 * 消费者是租户可达的工作负载列表页（点 nodePort 直连），不是节点管理页。
 */
class CoreV1NodeAddressesTest {

    private final CoreV1NodeOperations ops = new CoreV1NodeOperations(mock(KubernetesClient.class), null);

    private Node node(String name, String internalIp, String externalIp, String readyStatus) {
        NodeStatusBuilder st = new NodeStatusBuilder();
        if (internalIp != null) {
            st.addToAddresses(new NodeAddressBuilder().withType("InternalIP").withAddress(internalIp).build());
        }
        if (externalIp != null) {
            st.addToAddresses(new NodeAddressBuilder().withType("ExternalIP").withAddress(externalIp).build());
        }
        if (readyStatus != null) {
            st.addToConditions(new NodeConditionBuilder().withType("Ready").withStatus(readyStatus).build());
        }
        return new NodeBuilder().withNewMetadata().withName(name).endMetadata().withStatus(st.build()).build();
    }

    @Test
    void projects_name_and_both_ips() {
        List<NodeAddressDTO> out = ops.projectAddresses(List.of(
                node("n1", "10.0.0.11", "1.2.3.4", "True"),
                node("n2", "10.0.0.12", null, "True")));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getName()).isEqualTo("n1");
        assertThat(out.get(0).getInternalIp()).isEqualTo("10.0.0.11");
        assertThat(out.get(0).getExternalIp()).isEqualTo("1.2.3.4");
        // 没有 ExternalIP 的节点：外网字段留空，不是回填内网
        assertThat(out.get(1).getExternalIp()).isNull();
        assertThat(out.get(1).getInternalIp()).isEqualTo("10.0.0.12");
    }

    @Test
    void ready_flag_comes_from_Ready_condition() {
        List<NodeAddressDTO> out = ops.projectAddresses(List.of(
                node("up", "10.0.0.11", null, "True"),
                node("down", "10.0.0.12", null, "False"),
                node("unknown", "10.0.0.13", null, "Unknown")));

        assertThat(out).extracting(NodeAddressDTO::getReady).containsExactly(true, false, false);
    }

    /** 节点刚注册、status 还没填：不能 NPE，两个 IP 都为 null（前端会跳过它） */
    @Test
    void node_without_status_yields_null_ips() {
        Node bare = new NodeBuilder().withNewMetadata().withName("bare").endMetadata().build();

        List<NodeAddressDTO> out = ops.projectAddresses(List.of(bare));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getName()).isEqualTo("bare");
        assertThat(out.get(0).getInternalIp()).isNull();
        assertThat(out.get(0).getExternalIp()).isNull();
        assertThat(out.get(0).getReady()).isFalse();
    }
}
