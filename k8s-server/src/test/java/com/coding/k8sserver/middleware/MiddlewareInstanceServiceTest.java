package com.coding.k8sserver.middleware;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ManagedFieldsEntry;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MiddlewareInstanceServiceTest {

    @Test
    void stripApplyMetadataRemovesManagedFields() {
        // 真集群验证：GET 到的 CR 原样 SSA 回去会被 apiserver 拒（metadata.managedFields must be nil）
        var live = new GenericKubernetesResource();
        var meta = new ObjectMeta();
        meta.setName("mw-redis-x");
        meta.setManagedFields(List.of(new ManagedFieldsEntry()));
        live.setMetadata(meta);

        MiddlewareInstanceService.stripApplyMetadata(live);

        assertThat(live.getMetadata().getManagedFields()).isNull();
        assertThat(live.getMetadata().getName()).isEqualTo("mw-redis-x");
    }

    @Test
    void stripApplyMetadataToleratesMissingMetadata() {
        MiddlewareInstanceService.stripApplyMetadata(new GenericKubernetesResource());
    }
}
