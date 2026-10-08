package com.coding.k8sserver.controllers.base;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.ConfigMapDTO;
import com.coding.common.models.k8s.dto.SecretDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.converter.impl.core.CoreV1SecretConverter;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.core.CoreV1SecretOperations;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.components.ResourceAccessResolver.AccessContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /list-all}（跨命名空间列举）的两条不变式：
 * <ol>
 *   <li><b>返回项各自保留自己的 namespace</b> —— 跨命名空间时每项不同，统一回填会把它们全写成同一个；</li>
 *   <li><b>未实现该能力的资源返回业务错误，而不是 500 或静默降级</b> ——
 *       "悄悄退化成单命名空间 list"会把"不支持"伪装成"支持"，返回一份看起来正常却不完整的数据。</li>
 * </ol>
 * 端点本身对<b>所有</b>命名空间级资源都存在（闸门是 ops 实现 + platform-api 权限表），故这里不需要
 * 用"某个资源开没开放"来分支。
 */
class AbstractNamespacedResourceControllerListAllTest {

    /** 最小 controller：什么都不用声明 —— /list-all 由基类提供，身份闸门在 resolvePlatformNamespacedAccess */
    private static final class TestController extends AbstractNamespacedResourceController<ConfigMapDTO> {
        TestController(KubernetesOperationsFactory f, ResourceAccessResolver r) {
            super(f, r);
        }

        @Override
        protected ResourceType resourceType() {
            return ResourceType.CONFIGMAP;
        }
    }

    @SuppressWarnings("unchecked")
    private KubernetesOperationsFactory factoryReturning(List<ConfigMapDTO> items) {
        KubernetesOperationsFactory factory = mock(KubernetesOperationsFactory.class);
        NamespacedOperations<ConfigMapDTO> ops = mock(NamespacedOperations.class);
        when(ops.listAll(any(), any())).thenReturn(items);
        // 显式类型见证：getAdminNamespacedOperation 是 <T> 泛型，无见证时 T 推断为 Object，thenReturn 不匹配
        when(factory.<ConfigMapDTO>getAdminNamespacedOperation(any(), anyString())).thenReturn(ops);
        return factory;
    }

    private ResourceAccessResolver resolverAsPlatform() {
        ResourceAccessResolver resolver = mock(ResourceAccessResolver.class);
        when(resolver.resolvePlatformNamespacedAccess(anyString())).thenReturn(new AccessContext(null, true));
        return resolver;
    }

    private ConfigMapDTO item(String namespace, String name) {
        ConfigMapDTO d = new ConfigMapDTO();
        d.setNamespace(namespace);
        d.setName(name);
        return d;
    }

    private ConfigMapDTO query() {
        ConfigMapDTO q = new ConfigMapDTO();
        q.setClusterId("c1");
        q.setLabelSelector("app=web");
        return q;
    }

    @Test
    void returns_items_keeping_their_own_namespace() {
        KubernetesOperationsFactory factory =
                factoryReturning(List.of(item("default", "cm-a"), item("kube-system", "cm-b")));
        TestController controller = new TestController(factory, resolverAsPlatform());

        ResponseData<List<ConfigMapDTO>> resp = controller.listAll(query());

        assertThat(resp.getData()).extracting(ConfigMapDTO::getNamespace)
                .containsExactly("default", "kube-system");
        // clusterId 回填（供前端拼后续请求），但 namespace 必须保持原样
        assertThat(resp.getData()).allSatisfy(d -> assertThat(d.getClusterId()).isEqualTo("c1"));
    }

    @Test
    void selectors_from_body_are_passed_through() {
        KubernetesOperationsFactory factory = factoryReturning(List.of());
        TestController controller = new TestController(factory, resolverAsPlatform());

        controller.listAll(query());

        verify(factory.getAdminNamespacedOperation(ResourceType.CONFIGMAP, "c1")).listAll("app=web", null);
    }

    @Test
    void unimplemented_resource_reports_operation_not_supported() {
        // CoreV1SecretOperations 没有实现 listAll → 走接口 default 实现
        NamespacedOperations<SecretDTO> unimplemented =
                new CoreV1SecretOperations(null, new CoreV1SecretConverter());

        assertThatThrownBy(() -> unimplemented.listAll(null, null))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("不支持跨命名空间列举");
    }
}
