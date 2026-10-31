package com.coding.k8score.operations.core;

import com.coding.k8score.converter.impl.core.CoreV1ServiceConverter;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code spec.ports} 是 apiserver 里按 {@code port} 合并的 map list（{@code listMapKeys: [port]}）。
 * SSA 的「省略即删除」只对本 manager 拥有的条目生效，所以改端口号会留下旧条目 ——
 * 判定「有没有陈旧条目」是这条修复的开关，单独钉住。
 */
class CoreV1ServiceOperationsTest {

    private final KubernetesClient client = mock(KubernetesClient.class);
    private final CoreV1ServiceOperations ops = new CoreV1ServiceOperations(client, new CoreV1ServiceConverter());

    @Test
    void api_version_is_core_v1() {
        assertThat(ops.apiVersion()).isEqualTo("v1");
    }

    /** 6379 → 16379：旧条目没人删，会和新条目同 nodePort（2026-10-31 的 duplicate nodePort）。 */
    @Test
    void renaming_a_port_leaves_a_stale_entry() {
        assertThat(CoreV1ServiceOperations.hasStalePorts(ports(6379), ports(16379))).isTrue();
    }

    @Test
    void unchanged_ports_are_not_stale() {
        assertThat(CoreV1ServiceOperations.hasStalePorts(ports(6379), ports(6379))).isFalse();
    }

    @Test
    void removing_one_of_two_ports_is_stale_only_for_the_removed_one() {
        assertThat(CoreV1ServiceOperations.hasStalePorts(ports(6379, 16379), ports(16379))).isTrue();
        assertThat(CoreV1ServiceOperations.hasStalePorts(ports(6379, 16379), ports(16379, 6379))).isFalse();
    }

    private static List<ServicePort> ports(int... numbers) {
        return java.util.Arrays.stream(numbers)
                .mapToObj(p -> new ServicePortBuilder().withPort(p).build())
                .toList();
    }
}
