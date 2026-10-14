package com.coding.k8sserver.controllers.namespace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineRunControllerTest {

    @Test
    void declaresThePipelineRunResourceTypeAndUrlPrefix() {
        assertThat(PipelineRunController.class.getAnnotation(
                org.springframework.web.bind.annotation.RequestMapping.class).value())
                .containsExactly("/pipelineruns");
    }

    @Test
    void cancelIsDeclaredAsANonStandardEndpoint() throws Exception {
        // 基类给 6 个标准端点，cancel 是本类加的 —— 它必须真的存在
        assertThat(PipelineRunController.class.getMethod(
                "cancel", String.class, String.class, String.class, String.class)).isNotNull();
    }
}
