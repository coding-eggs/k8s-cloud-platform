package com.coding.k8sserver.app;

import com.coding.common.models.k8s.dto.IngressListenerDTO;
import com.coding.common.models.k8s.dto.IngressListenerSetRequestDTO;
import com.coding.k8sserver.components.ExternalGatewayObjects;
import com.coding.k8sserver.components.IngressListenerSetObjects;
import com.coding.k8score.factory.KubernetesClientFactory;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.V1ApiextensionAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.ApiextensionsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class AppIngressServiceTest {

    @Test
    void applyReportsUnsupportedWhenTheParentGatewayIsMissing() {
        // 父 Gateway 不存在时 SSA ListenerSet 也会成功，但永远不会被接受：要在 PRECHECK 就报出来（终审 I6）
        KubernetesClientFactory clients = mock(KubernetesClientFactory.class);
        KubernetesClient client = mock(KubernetesClient.class);
        when(clients.getAdminClient("c1")).thenReturn(client);

        ApiextensionsAPIGroupDSL ext = mock(ApiextensionsAPIGroupDSL.class);
        V1ApiextensionAPIGroupDSL v1 = mock(V1ApiextensionAPIGroupDSL.class);
        NonNamespaceOperation<CustomResourceDefinition, CustomResourceDefinitionList, Resource<CustomResourceDefinition>>
                crds = mock(NonNamespaceOperation.class);
        Resource<CustomResourceDefinition> crd = mock(Resource.class);
        when(client.apiextensions()).thenReturn(ext);
        when(ext.v1()).thenReturn(v1);
        when(v1.customResourceDefinitions()).thenReturn(crds);
        when(crds.withName(IngressListenerSetObjects.CRD_NAME)).thenReturn(crd);
        when(crd.get()).thenReturn(new CustomResourceDefinition());

        MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>>
                gateways = mock(MixedOperation.class);
        NonNamespaceOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>>
                inNs = mock(NonNamespaceOperation.class);
        Resource<GenericKubernetesResource> gateway = mock(Resource.class);
        when(client.genericKubernetesResources(ExternalGatewayObjects.CRD)).thenReturn(gateways);
        when(gateways.inNamespace("istio-system")).thenReturn(inNs);
        when(inNs.withName("external-gateway")).thenReturn(gateway);
        when(gateway.get()).thenReturn(null);

        boolean ok = new AppIngressService(clients).apply(request());

        assertThat(ok).isFalse();
        verify(client, never()).namespaces();
    }

    private static IngressListenerSetRequestDTO request() {
        IngressListenerDTO l = new IngressListenerDTO();
        l.setName("p20001");
        l.setPort(20001);
        l.setProtocol("HTTP");
        IngressListenerSetRequestDTO r = new IngressListenerSetRequestDTO();
        r.setClusterId("c1");
        r.setNamespace("demo");
        r.setName("platform-ingress");
        r.setParentNamespace("istio-system");
        r.setParentName("external-gateway");
        r.setNamespaceLabels(Map.of("ingress.platform.coding/ports", "true"));
        r.setListeners(List.of(l));
        return r;
    }
}
