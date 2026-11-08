package com.coding.k8score.operations.gateway;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.ListenerSetDTO;
import com.coding.k8score.converter.impl.gateway.ListenerSetConverter;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ListenerSetOperationsTest {

    private final KubernetesClient client = mock(KubernetesClient.class);
    private final ListenerSetOperations ops = new ListenerSetOperations(client, new ListenerSetConverter());

    @Test
    void writesAreNotSupported() {
        // 平台里 ListenerSet 只读：platform-ingress 由应用发布整份重算，页面写了也会被覆盖
        ListenerSetDTO dto = new ListenerSetDTO();
        dto.setNamespace("shop");
        dto.setName("platform-ingress");

        assertThatThrownBy(() -> ops.create(dto)).isInstanceOf(CloudPlatformException.class);
        assertThatThrownBy(() -> ops.update(dto)).isInstanceOf(CloudPlatformException.class);
        assertThatThrownBy(() -> ops.delete("shop", "platform-ingress")).isInstanceOf(CloudPlatformException.class);
        verifyNoInteractions(client);
    }
}
