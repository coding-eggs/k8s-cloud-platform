package com.coding.k8sserver.middleware;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ManagedFieldsEntry;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
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

    // ---------------- 资源归属（详情页「资源对象」用） ----------------

    @Test
    void workloadIsOwnedWhenNameStartsWithInstanceName() {
        // Operator 普遍用 <CR 名>-<角色> 命名（<name>-pxc / <name>-es-default / <name>-1）
        var meta = meta("mw-mysql-abc12345-pxc");
        assertThat(MiddlewareInstanceService.belongsToInstance(meta, "mw-mysql-abc12345", "mwi_1")).isTrue();
    }

    @Test
    void workloadIsOwnedWhenOwnerReferencePointsAtTheCr() {
        // ECK 的 Kibana Deployment 名字是 <name>-kb，但 ES/Kibana 的 STS/Deployment 都以 CR 为属主
        var owner = new OwnerReferenceBuilder()
                .withKind("Kibana").withName("mw-elasticsearch-abc12345").build();
        var meta = meta("kibana-operator-managed");
        meta.setOwnerReferences(List.of(owner));
        assertThat(MiddlewareInstanceService.belongsToInstance(meta, "mw-elasticsearch-abc12345", "mwi_1")).isTrue();
    }

    @Test
    void workloadIsOwnedWhenHelmInstanceLabelMatches() {
        // chart 安装的中间件（Nacos）工作负载名不一定带实例名，但 Helm 会打 app.kubernetes.io/instance
        var meta = meta("nacos-server");
        meta.setLabels(java.util.Map.of("app.kubernetes.io/instance", "mw-nacos-abc12345"));
        assertThat(MiddlewareInstanceService.belongsToInstance(meta, "mw-nacos-abc12345", "mwi_1")).isTrue();
    }

    @Test
    void workloadIsOwnedWhenPlatformLabelMatches() {
        var meta = meta("something-else");
        meta.setLabels(java.util.Map.of("platform.coding/middleware-instance", "mwi_1"));
        assertThat(MiddlewareInstanceService.belongsToInstance(meta, "mw-redis-abc12345", "mwi_1")).isTrue();
    }

    @Test
    void unrelatedWorkloadIsNotOwned() {
        // 同一命名空间里别的实例（前缀相同但没跟着 '-'）不能算进来
        assertThat(MiddlewareInstanceService.belongsToInstance(
                meta("mw-redis-abc12345x"), "mw-redis-abc12345", "mwi_1")).isFalse();
        assertThat(MiddlewareInstanceService.belongsToInstance(
                meta("platform-middleware-redis-operator"), "mw-redis-abc12345", "mwi_1")).isFalse();
        assertThat(MiddlewareInstanceService.belongsToInstance(null, "mw-redis-abc12345", "mwi_1")).isFalse();
    }

    // ---------------- CR conditions ----------------

    @Test
    void conditionsAreParsedFromCrStatus() {
        var cr = new GenericKubernetesResource();
        cr.setAdditionalProperties(java.util.Map.of("status", java.util.Map.of("conditions", List.of(
                java.util.Map.of("type", "Ready", "status", "True", "reason", "Running",
                        "message", "all good", "lastTransitionTime", "2026-10-23T10:00:00Z"),
                java.util.Map.of("type", "Initializing", "status", "False")))));

        var out = MiddlewareInstanceService.conditionsOf(cr);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getType()).isEqualTo("Ready");
        assertThat(out.get(0).getStatus()).isEqualTo("True");
        assertThat(out.get(0).getReason()).isEqualTo("Running");
        assertThat(out.get(1).getLastTransitionTime()).isNull();
    }

    @Test
    void conditionsAreEmptyWhenCrHasNoStatus() {
        assertThat(MiddlewareInstanceService.conditionsOf(new GenericKubernetesResource())).isEmpty();
    }

    private static ObjectMeta meta(String name) {
        ObjectMeta meta = new ObjectMeta();
        meta.setName(name);
        return meta;
    }
}
