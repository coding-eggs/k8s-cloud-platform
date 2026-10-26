package com.coding.k8sserver.controllers.base;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.ConfigMapDTO;
import com.coding.common.models.k8s.dto.PodDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8sserver.components.ManagedResourceGuard;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.components.ResourceAccessResolver.AccessContext;
import com.coding.k8sserver.controllers.namespace.PodController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 删除闸的两条口径：
 * <ol>
 *   <li><b>默认</b>：受管资源不可删（与 update 同闸）—— 防止在通用资源页把组件/中间件对象删坏；</li>
 *   <li><b>Pod 例外</b>：受管工作负载的 Pod 也允许删除 —— 删 Pod 等于重启副本，控制器按原模板重建，
 *       不改变任何配置；"其他配置不能改"由 Pod 无 create/update + 工作负载自身的写闸保证。</li>
 * </ol>
 */
class AbstractNamespacedResourceControllerDeleteTest {

    private static final String CLUSTER = "c1";
    private static final String NS = "demo";

    /** 最小 controller：不带 Pod 的例外，走基类默认口径 */
    private static final class TestController extends AbstractNamespacedResourceController<ConfigMapDTO> {
        TestController(KubernetesOperationsFactory f, ResourceAccessResolver r) {
            super(f, r);
        }

        @Override
        protected ResourceType resourceType() {
            return ResourceType.CONFIGMAP;
        }
    }

    private ResourceAccessResolver resolverAsTenant() {
        ResourceAccessResolver resolver = mock(ResourceAccessResolver.class);
        // tenantId 传 null（租户身份以 JWT claim 为准），anyString() 匹配不到 null
        when(resolver.resolveNamespacedAccess(nullable(String.class), anyString(), anyString()))
                .thenReturn(new AccessContext("t1", false));
        return resolver;
    }

    /** 生产由 Spring 注入；单测直接塞进去 */
    private void injectGuard(AbstractNamespacedResourceController<?> controller) throws Exception {
        Field field = AbstractNamespacedResourceController.class.getDeclaredField("managedResourceGuard");
        field.setAccessible(true);
        field.set(controller, new ManagedResourceGuard("platform-system,monitor,istio-system"));
    }

    private static Map<String, String> managedLabels() {
        // 中间件实例创建的对象：命中 ManagedResourceGuard 第 1 条
        return Map.of("platform.coding/managed-by", ManagedResourceGuard.MIDDLEWARE);
    }

    @SuppressWarnings("unchecked")
    private KubernetesOperationsFactory factoryWithLiveConfigMap(ConfigMapDTO live) {
        KubernetesOperationsFactory factory = mock(KubernetesOperationsFactory.class);
        NamespacedOperations<ConfigMapDTO> ops = mock(NamespacedOperations.class);
        when(ops.get(NS, live.getName())).thenReturn(live);
        when(factory.<ConfigMapDTO>getNamespacedOperation(ResourceType.CONFIGMAP, CLUSTER, "t1")).thenReturn(ops);
        return factory;
    }

    @Test
    void defaultDeleteStillRejectsManagedResources() throws Exception {
        ConfigMapDTO live = new ConfigMapDTO();
        live.setName("mw-redis-config");
        live.setLabels(managedLabels());
        KubernetesOperationsFactory factory = factoryWithLiveConfigMap(live);

        TestController controller = new TestController(factory, resolverAsTenant());
        injectGuard(controller);

        assertThatThrownBy(() -> controller.delete("mw-redis-config", null, CLUSTER, NS))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("平台组件或中间件实例");
        verify(factory.getNamespacedOperation(ResourceType.CONFIGMAP, CLUSTER, "t1"), never())
                .delete(NS, "mw-redis-config");
    }

    @Test
    @SuppressWarnings("unchecked")
    void podDeleteIsAllowedEvenWhenManaged() throws Exception {
        PodDTO live = new PodDTO();
        live.setName("mw-redis-0");
        live.setLabels(managedLabels());
        KubernetesOperationsFactory factory = mock(KubernetesOperationsFactory.class);
        NamespacedOperations<PodDTO> ops = mock(NamespacedOperations.class);
        when(ops.get(NS, "mw-redis-0")).thenReturn(live);
        when(factory.<PodDTO>getNamespacedOperation(ResourceType.POD, CLUSTER, "t1")).thenReturn(ops);

        PodController controller = new PodController(factory, resolverAsTenant(), null);
        injectGuard(controller);

        // 不抛异常 = 放行
        controller.delete("mw-redis-0", null, CLUSTER, NS);

        verify(ops).delete(NS, "mw-redis-0");
    }

    @Test
    void podStillCannotBeCreatedOrUpdated() {
        PodController controller = new PodController(
                mock(KubernetesOperationsFactory.class), resolverAsTenant(), null);
        PodDTO body = new PodDTO();
        body.setName("mw-redis-0");
        body.setNamespace(NS);
        body.setLabels(managedLabels());

        assertThatThrownBy(() -> controller.update("mw-redis-0", null, CLUSTER, body))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("不支持");
        assertThatThrownBy(() -> controller.create(null, CLUSTER, body))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("不支持");
    }
}
