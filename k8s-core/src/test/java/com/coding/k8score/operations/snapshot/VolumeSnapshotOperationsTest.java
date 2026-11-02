package com.coding.k8score.operations.snapshot;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.VolumeSnapshotDTO;
import com.coding.k8score.converter.impl.snapshot.VolumeSnapshotConverter;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AnyNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VolumeSnapshotOperationsTest {

    private KubernetesClient client;

    private MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList,
            Resource<GenericKubernetesResource>> op;

    private VolumeSnapshotOperations ops;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(KubernetesClient.class);
        op = mock(MixedOperation.class);
        when(client.genericKubernetesResources(any(ResourceDefinitionContext.class))).thenReturn(op);
        ops = new VolumeSnapshotOperations(client, new VolumeSnapshotConverter());
    }

    private static GenericKubernetesResource item(String ns, String name) {
        GenericKubernetesResource r = new GenericKubernetesResource();
        r.setMetadata(new ObjectMetaBuilder().withNamespace(ns).withName(name).build());
        return r;
    }

    private static GenericKubernetesResourceList listOf(GenericKubernetesResource... items) {
        GenericKubernetesResourceList list = new GenericKubernetesResourceList();
        list.setItems(List.of(items));
        return list;
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesSnapshotCrd() {
        NonNamespaceOperation<GenericKubernetesResource, GenericKubernetesResourceList,
                Resource<GenericKubernetesResource>> inNs = mock(NonNamespaceOperation.class);
        when(op.inNamespace("ns1")).thenReturn(inNs);
        when(inNs.list(any(ListOptions.class))).thenReturn(listOf());

        ops.list("ns1", null, null);

        ArgumentCaptor<ResourceDefinitionContext> crd = ArgumentCaptor.forClass(ResourceDefinitionContext.class);
        verify(client).genericKubernetesResources(crd.capture());
        assertThat(crd.getValue().getGroup()).isEqualTo("snapshot.storage.k8s.io");
        assertThat(crd.getValue().getVersion()).isEqualTo("v1");
        assertThat(crd.getValue().getPlural()).isEqualTo("volumesnapshots");
        assertThat(crd.getValue().isNamespaceScoped()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void listReturnsItems() {
        NonNamespaceOperation<GenericKubernetesResource, GenericKubernetesResourceList,
                Resource<GenericKubernetesResource>> inNs = mock(NonNamespaceOperation.class);
        when(op.inNamespace("ns1")).thenReturn(inNs);
        when(inNs.list(any(ListOptions.class))).thenReturn(listOf(item("ns1", "a"), item("ns1", "b")));

        assertThat(ops.list("ns1", null, null)).extracting(VolumeSnapshotDTO::getName).containsExactly("a", "b");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listAllSpansNamespaces() {
        AnyNamespaceOperation any = mock(AnyNamespaceOperation.class);
        when(op.inAnyNamespace()).thenReturn(any);
        when(any.list(any(ListOptions.class))).thenReturn(listOf(item("ns-a", "a"), item("ns-b", "b")));

        List<VolumeSnapshotDTO> out = ops.listAll(null, null);

        assertThat(out).extracting(VolumeSnapshotDTO::getNamespace).containsExactly("ns-a", "ns-b");
        verify(op, never()).inNamespace(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void createRejectsExistingName() {
        NonNamespaceOperation<GenericKubernetesResource, GenericKubernetesResourceList,
                Resource<GenericKubernetesResource>> inNs = mock(NonNamespaceOperation.class);
        Resource<GenericKubernetesResource> named = mock(Resource.class);
        when(op.inNamespace("ns1")).thenReturn(inNs);
        when(inNs.withName("snap")).thenReturn(named);
        when(named.get()).thenReturn(item("ns1", "snap"));

        VolumeSnapshotDTO dto = new VolumeSnapshotDTO();
        dto.setNamespace("ns1");
        dto.setName("snap");
        dto.setSourcePvcName("data");

        assertThatThrownBy(() -> ops.create(dto))
                .isInstanceOf(CloudPlatformException.class)
                .satisfies(e -> assertThat(((CloudPlatformException) e).getCode())
                        .isEqualTo(EnumResponseType.RESOURCE_EXIST.getCode()));
    }

    @Test
    void updateIsRejected() {
        assertThatThrownBy(() -> ops.update(new VolumeSnapshotDTO()))
                .isInstanceOf(CloudPlatformException.class)
                .satisfies(e -> assertThat(((CloudPlatformException) e).getCode())
                        .isEqualTo(EnumResponseType.OPERATION_NOT_SUPPORTED.getCode()));
    }
}
