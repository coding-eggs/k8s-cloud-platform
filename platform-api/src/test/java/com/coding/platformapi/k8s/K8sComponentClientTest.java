package com.coding.platformapi.k8s;

import com.coding.common.models.k8s.dto.ComponentInstallRequestDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 跨文件接口：platform-api 发给 k8s-server 的安装请求必须带 componentCode 与 action ——
 * k8s-server 用 componentCode 拼 release 标签，漏了会在 release secret 上留下 component=null。
 */
class K8sComponentClientTest {

    @Test
    void itSendsTheComponentCodeAndAction() {
        K8sServerGateway gateway = mock(K8sServerGateway.class);

        new K8sComponentClient(gateway).applyComponent("c1", "metrics-server", "INSTALL", List.of());

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(gateway).exchangeSlow(eq(HttpMethod.POST), eq("/components/apply"),
                isNull(), body.capture(), any());
        ComponentInstallRequestDTO req = (ComponentInstallRequestDTO) body.getValue();
        assertThat(req.getClusterId()).isEqualTo("c1");
        assertThat(req.getComponentCode()).isEqualTo("metrics-server");
        assertThat(req.getAction()).isEqualTo("INSTALL");
    }
}
