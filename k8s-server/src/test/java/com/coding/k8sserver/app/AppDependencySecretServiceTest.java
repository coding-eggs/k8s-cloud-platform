package com.coding.k8sserver.app;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.app.AppDependencySecretRequestDTO;
import com.coding.k8score.factory.KubernetesClientFactory;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.kubernetes.api.model.SecretListBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 跨命名空间依赖的凭据副本：复制到位、清理遗留副本、不覆盖用户自建的 Secret。 */
@SuppressWarnings("unchecked")
class AppDependencySecretServiceTest {

    static final String TARGET = "mwdep-app1-cache-cache-credentials";

    KubernetesClientFactory clients;
    KubernetesClient client;
    MixedOperation<Secret, SecretList, Resource<Secret>> secrets;
    NonNamespaceOperation<Secret, SecretList, Resource<Secret>> sourceNs;
    NonNamespaceOperation<Secret, SecretList, Resource<Secret>> targetNs;
    FilterWatchListDeletable<Secret, SecretList, Resource<Secret>> listed;
    Resource<Secret> targetResource;
    Resource<Secret> targetExisting;
    SecretList stale = new SecretListBuilder().build();

    @BeforeEach
    void setUp() {
        clients = mock(KubernetesClientFactory.class);
        client = mock(KubernetesClient.class);
        secrets = mock(MixedOperation.class);
        sourceNs = mock(NonNamespaceOperation.class);
        targetNs = mock(NonNamespaceOperation.class);
        listed = mock(FilterWatchListDeletable.class);
        targetResource = mock(Resource.class);
        targetExisting = mock(Resource.class);
        when(clients.getAdminClient("c1")).thenReturn(client);
        when(client.secrets()).thenReturn(secrets);
        when(secrets.inNamespace("middleware")).thenReturn(sourceNs);
        when(secrets.inNamespace("demo")).thenReturn(targetNs);
        when(targetNs.withLabels(any())).thenReturn(listed);
        when(listed.list()).thenReturn(stale);
        when(targetNs.resource(any())).thenReturn(targetResource);
        when(targetResource.fieldManager(any())).thenReturn(targetResource);
        when(targetResource.forceConflicts()).thenReturn(targetResource);
        when(targetNs.withName(TARGET)).thenReturn(targetExisting);
    }

    AppDependencySecretRequestDTO.Copy copy() {
        return AppDependencySecretRequestDTO.Copy.of("middleware", "cache-credentials", TARGET, true);
    }

    AppDependencySecretRequestDTO req(AppDependencySecretRequestDTO.Copy copy) {
        AppDependencySecretRequestDTO r = new AppDependencySecretRequestDTO();
        r.setClusterId("c1");
        r.setNamespace("demo");
        r.setAppId("app1");
        r.setCopies(copy == null ? List.of() : List.of(copy));
        return r;
    }

    void sourceSecret(String data) {
        Resource<Secret> source = mock(Resource.class);
        when(sourceNs.withName("cache-credentials")).thenReturn(source);
        when(source.get()).thenReturn(new SecretBuilder().withData(Map.of("password", data)).build());
    }

    @Test
    void copiesTheSourceSecretAndDeletesStaleCopies() {
        sourceSecret("c2VjcmV0");
        String staleName = "mwdep-app1-db-old-credentials";
        Resource<Secret> staleResource = mock(Resource.class);
        when(targetNs.withName(staleName)).thenReturn(staleResource);
        when(listed.list()).thenReturn(new SecretListBuilder()
                .withItems(new SecretBuilder().withNewMetadata().withName(staleName).endMetadata().build())
                .build());

        new AppDependencySecretService(clients).sync(req(copy()));

        verify(targetNs).resource(any(Secret.class));
        verify(targetResource).serverSideApply();
        verify(staleResource).delete();
    }

    @Test
    void keepsTheCopyThatIsStillWanted() {
        sourceSecret("eA==");
        when(listed.list()).thenReturn(new SecretListBuilder()
                .withItems(new SecretBuilder().withNewMetadata().withName(TARGET).endMetadata().build())
                .build());

        new AppDependencySecretService(clients).sync(req(copy()));

        verify(targetResource).serverSideApply();
        verify(targetExisting, never()).delete();
    }

    @Test
    void missingSourceSecretFailsWhenTheDependencyIsUsed() {
        when(sourceNs.withName("cache-credentials")).thenReturn(mock(Resource.class));

        assertThatThrownBy(() -> new AppDependencySecretService(clients).sync(req(copy())))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("middleware/cache-credentials");
    }

    @Test
    void missingSourceSecretIsSkippedWhenTheDependencyIsOnlyRegistered() {
        // 实例还在创建、凭据 Secret 尚未生成：这次发布没用它，不能因此挡住发布
        when(sourceNs.withName("cache-credentials")).thenReturn(mock(Resource.class));
        AppDependencySecretRequestDTO.Copy registered =
                AppDependencySecretRequestDTO.Copy.of("middleware", "cache-credentials", TARGET, false);

        new AppDependencySecretService(clients).sync(req(registered));

        verify(targetNs, never()).resource(any(Secret.class));
    }

    @Test
    void doesNotOverwriteAUserSecretWithTheSameName() {
        sourceSecret("eA==");
        when(targetExisting.get()).thenReturn(new SecretBuilder()
                .withNewMetadata().withName(TARGET).endMetadata()
                .withData(Map.of("password", "dXNlcg==")).build());

        assertThatThrownBy(() -> new AppDependencySecretService(clients).sync(req(copy())))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("不能覆盖");
    }

    @Test
    void emptyCopiesOnlyPrunes() {
        new AppDependencySecretService(clients).sync(req(null));

        verify(secrets, never()).inNamespace("middleware");
        verify(listed, atLeastOnce()).list();
    }
}
