package com.coding.k8sserver.controllers;

import com.coding.k8sserver.controllers.cluster.NodeController;
import com.coding.k8sserver.controllers.namespace.PodController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 声明级测试：两个端点各多了一个 {@code previous} 参数。
 *
 * <p>⚠️ 这里测的是**声明**而不是行为 —— 行为（映射成 {@code terminated()}）由
 * {@code PodLogQueryTest} 钉住。两边合起来才完整：这个防"参数被人删掉/改了类型"，
 * 那个防"映射写错"。路径与参数名 platform-api / 前端会照字面用，写错就是 404 或静默丢参。
 */
class PodLogEndpointsAcceptPreviousTest {

    @Test
    void namespaceScopedLogsDeclareAPreviousParam() throws Exception {
        Method m = PodController.class.getMethod("logs", String.class, String.class, String.class,
                String.class, String.class, Integer.class, String.class, Integer.class, Boolean.class);
        assertThat(m.getParameters()[8].getAnnotation(RequestParam.class)).isNotNull();
        assertThat(m.getParameters()[8].getType()).isEqualTo(Boolean.class);
    }

    @Test
    void clusterScopedPodLogsDeclareAPreviousParam() throws Exception {
        Method m = NodeController.class.getMethod("podLogs", String.class, String.class, String.class,
                String.class, String.class, Integer.class, String.class, Integer.class, Boolean.class);
        assertThat(m.getParameters()[8].getAnnotation(RequestParam.class)).isNotNull();
        assertThat(m.getParameters()[8].getType()).isEqualTo(Boolean.class);
    }
}
