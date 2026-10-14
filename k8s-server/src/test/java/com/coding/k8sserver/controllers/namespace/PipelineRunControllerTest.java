package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.pipeline.PipelineCanvasRequest;
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

    /**
     * 这两个是「渲染搬进 k8s-server」之后新加的（spec §7 的改定块）。
     * 断言**路径**而不只是方法存在 —— platform-api 会照字面调它们，路径写错就是 404。
     */
    @Test
    void nodeTypesIsExposedAtThePathPlatformApiCalls() throws Exception {
        var m = PipelineRunController.class.getMethod("nodeTypes");
        assertThat(m.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class).value())
                .containsExactly("/node-types");
    }

    @Test
    void renderIsExposedAtThePathPlatformApiCalls() throws Exception {
        var m = PipelineRunController.class.getMethod("render", PipelineCanvasRequest.class);
        assertThat(m.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class).value())
                .containsExactly("/render");
    }
}
