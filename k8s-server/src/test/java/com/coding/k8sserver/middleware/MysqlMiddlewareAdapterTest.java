package com.coding.k8sserver.middleware;

import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MysqlMiddlewareAdapterTest {

    private final MysqlMiddlewareAdapter adapter = new MysqlMiddlewareAdapter();

    private MiddlewareCreateRequestDTO req(String mode, int replicas) {
        MiddlewareCreateRequestDTO r = new MiddlewareCreateRequestDTO();
        r.setInstanceId("mwi_1");
        r.setClusterId("c1");
        r.setCode("mysql");
        r.setMode(mode);
        r.setNamespace("demo");
        r.setName("mw-mysql-abc12345");
        r.setReplicas(replicas);
        r.setCpuRequest("500m");
        r.setCpuLimit("2");
        r.setMemoryRequest("1Gi");
        r.setMemoryLimit("4Gi");
        r.setStorageSize("20Gi");
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mysqlOf(GenericKubernetesResource res) {
        return (Map<String, Object>) ((Map<String, Object>) res.getAdditionalProperties().get("spec")).get("mysql");
    }

    @Test
    void singleNodeBuildsGroupReplicationWithOneMember() {
        var out = adapter.buildCreate(req("SINGLE", 3));

        assertThat(out).hasSize(1);
        var res = out.get(0).resource();
        assertThat(res.getApiVersion()).isEqualTo("ps.percona.com/v1");
        assertThat(res.getKind()).isEqualTo("PerconaServerMySQL");
        assertThat(mysqlOf(res)).containsEntry("clusterType", "group-replication").containsEntry("size", 1);
    }

    @Test
    void clusterEnforcesThreeMembers() {
        var res = adapter.buildCreate(req("CLUSTER", 2)).get(0).resource();

        assertThat(mysqlOf(res)).containsEntry("size", 3);
    }

    @Test
    void clusterKeepsRequestedSizeWhenAboveThree() {
        var res = adapter.buildCreate(req("CLUSTER", 5)).get(0).resource();

        assertThat(mysqlOf(res)).containsEntry("size", 5);
    }

    @Test
    void storageAndResourcesAreRendered() {
        var res = adapter.buildCreate(req("CLUSTER", 3)).get(0).resource();
        @SuppressWarnings("unchecked")
        var volumeSpec = (Map<String, Object>) mysqlOf(res).get("volumeSpec");
        @SuppressWarnings("unchecked")
        var pvc = (Map<String, Object>) volumeSpec.get("persistentVolumeClaim");
        @SuppressWarnings("unchecked")
        var requests = (Map<String, Object>) ((Map<String, Object>) pvc.get("resources")).get("requests");
        assertThat(requests).containsEntry("storage", "20Gi");
        assertThat((Map<String, Object>) mysqlOf(res).get("resources")).isNotNull();
    }

    @Test
    void operatorGeneratesCredentials() {
        // Percona 用 <cr>-secrets，平台不写口令 Secret
        assertThat(adapter.usesPlatformPassword()).isFalse();
        var res = adapter.buildCreate(req("SINGLE", 1)).get(0).resource();
        assertThat(res.getAdditionalProperties().toString()).doesNotContain("-credentials");
    }

    @Test
    void scalingASingleNodeToMoreReplicasIsRejected() {
        var live = adapter.buildCreate(req("SINGLE", 1)).get(0).resource();
        var scale = new com.coding.common.models.middleware.MiddlewareScaleRequestDTO();
        scale.setMode("SINGLE");
        scale.setReplicas(2);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> adapter.applyScale(live, MysqlMiddlewareAdapter.CRD, scale))
                .hasMessageContaining("单节点形态不支持调整节点数");
    }

    @Test
    void scalingAClusterKeepsHaproxyInSync() {
        var live = adapter.buildCreate(req("CLUSTER", 3)).get(0).resource();
        var scale = new com.coding.common.models.middleware.MiddlewareScaleRequestDTO();
        scale.setMode("CLUSTER");
        scale.setReplicas(5);

        adapter.applyScale(live, MysqlMiddlewareAdapter.CRD, scale);

        assertThat(mysqlOf(live)).containsEntry("size", 5);
        @SuppressWarnings("unchecked")
        var haproxy = (Map<String, Object>) ((Map<String, Object>) live.getAdditionalProperties().get("spec"))
                .get("proxy");
        assertThat((Map<String, Object>) haproxy.get("haproxy")).containsEntry("size", 5);
    }

    @Test
    void singleNodeCarriesUnsafeFlags() {
        // 真集群验证：Group Replication 的 size<3 被 CRD 校验直接拒（no such key: unsafeFlags）
        var res = adapter.buildCreate(req("SINGLE", 1)).get(0).resource();
        @SuppressWarnings("unchecked")
        var unsafe = (Map<String, Object>) res.getAdditionalProperties().get("spec");
        assertThat((Map<String, Object>) unsafe.get("unsafeFlags"))
                .containsEntry("mysqlSize", true)
                .containsEntry("proxySize", true);
    }

    @Test
    void clusterDoesNotNeedUnsafeFlags() {
        var res = adapter.buildCreate(req("CLUSTER", 3)).get(0).resource();
        @SuppressWarnings("unchecked")
        var spec = (Map<String, Object>) res.getAdditionalProperties().get("spec");
        assertThat(spec.get("unsafeFlags")).isNull();
    }

    @Test
    void proxyDisablesRouterAndPinsHaproxyImage() {
        // 真集群验证：不显式关 router 会报「MySQL Router 与 HAProxy 不能同时启用」；
        // haproxy 启用时 CRD 要求显式 image
        var res = adapter.buildCreate(req("CLUSTER", 3)).get(0).resource();
        @SuppressWarnings("unchecked")
        var proxy = (Map<String, Object>) ((Map<String, Object>) res.getAdditionalProperties().get("spec"))
                .get("proxy");
        @SuppressWarnings("unchecked")
        var haproxy = (Map<String, Object>) proxy.get("haproxy");
        assertThat(haproxy).containsEntry("enabled", true);
        assertThat((String) haproxy.get("image")).startsWith("percona/");
        // 配额按容器校验：HAProxy 不写 resources 会被 quota 挡住（真集群验证）
        assertThat(haproxy.get("resources")).isNotNull();
        @SuppressWarnings("unchecked")
        var router = (Map<String, Object>) proxy.get("router");
        assertThat(router).containsEntry("enabled", false);
    }

    @Test
    void desiredPodsCountMysqlAndHaproxy() {
        var res = adapter.buildCreate(req("CLUSTER", 5)).get(0).resource();

        // 5 个数据节点 + 5 个 HAProxy
        assertThat(adapter.desiredPods(res, "PerconaServerMySQL")).isEqualTo(10);
    }

    @Test
    void statusSelectorTargetsMysqlPods() {
        // 真集群验证：Percona 给数据 Pod 打的 component 是 database（不是 mysql）；
        // 这里按实例标签取全部 Pod，让 HAProxy 缺失也能被看见
        assertThat(adapter.statusSelector("mw-mysql-x", "PerconaServerMySQL"))
                .isEqualTo("app.kubernetes.io/instance=mw-mysql-x");
    }

    // ---------------- 业务指标（Percona 只有 PMM → 平台挂 mysqld_exporter） ----------------

    @Test
    @SuppressWarnings("unchecked")
    void metricsSidecarIsOptInAndUsesTheMonitorUser() {
        var off = (Map<String, Object>) adapter.buildCreate(req("SINGLE", 1)).get(0)
                .resource().getAdditionalProperties().get("spec");
        assertThat((Map<String, Object>) off.get("mysql")).doesNotContainKey("sidecars");

        var r = req("SINGLE", 1);
        r.setMetricsEnabled(true);
        var on = (Map<String, Object>) adapter.buildCreate(r).get(0)
                .resource().getAdditionalProperties().get("spec");
        var sidecars = (List<Map<String, Object>>) ((Map<String, Object>) on.get("mysql")).get("sidecars");

        assertThat(sidecars).hasSize(1);
        assertThat(sidecars.get(0)).containsEntry("name", "mysqld-exporter");
        var env = (List<Map<String, Object>>) sidecars.get(0).get("env");
        var valueFrom = (Map<String, Object>) env.get(0).get("valueFrom");
        assertThat((Map<String, Object>) valueFrom.get("secretKeyRef"))
                .containsEntry("name", "mw-mysql-abc12345-secrets")
                .containsEntry("key", "monitor");
    }

    @Test
    void metricsSelectorSkipsHaproxyPods() {
        assertThat(adapter.metricsSpec("mw-mysql-x", "PerconaServerMySQL", "mwi_1").podSelector())
                .isEqualTo("app.kubernetes.io/instance=mw-mysql-x,app.kubernetes.io/component=database");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ensureMetricsAddsExporterSidecarIdempotently() {
        var live = adapter.buildCreate(req("SINGLE", 1)).get(0).resource();
        var scaleReq = new com.coding.common.models.middleware.MiddlewareScaleRequestDTO();
        scaleReq.setName("mw-mysql-abc12345");

        assertThat(adapter.ensureMetrics(live, MysqlMiddlewareAdapter.CRD, scaleReq)).isTrue();
        var spec = (Map<String, Object>) live.getAdditionalProperties().get("spec");
        var sidecars = (List<Map<String, Object>>) ((Map<String, Object>) spec.get("mysql")).get("sidecars");
        assertThat(sidecars).anySatisfy(s -> assertThat(s).containsEntry("name", "mysqld-exporter"));
        assertThat(adapter.ensureMetrics(live, MysqlMiddlewareAdapter.CRD, scaleReq)).isFalse();
    }

}
