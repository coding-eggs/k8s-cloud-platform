package com.coding.k8sserver.middleware;

import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresqlMiddlewareAdapterTest {

    private final PostgresqlMiddlewareAdapter adapter = new PostgresqlMiddlewareAdapter();

    private MiddlewareCreateRequestDTO req(String mode, int replicas) {
        MiddlewareCreateRequestDTO r = new MiddlewareCreateRequestDTO();
        r.setInstanceId("mwi_1");
        r.setClusterId("c1");
        r.setCode("postgresql");
        r.setMode(mode);
        r.setNamespace("demo");
        r.setName("mw-postgresql-abc12345");
        r.setReplicas(replicas);
        r.setCpuRequest("500m");
        r.setCpuLimit("2");
        r.setMemoryRequest("1Gi");
        r.setMemoryLimit("4Gi");
        r.setStorageSize("20Gi");
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> spec(GenericKubernetesResource res) {
        return (Map<String, Object>) res.getAdditionalProperties().get("spec");
    }

    @Test
    void singleBuildsOneInstance() {
        var out = adapter.buildCreate(req("SINGLE", 3));

        assertThat(out).hasSize(1);
        var res = out.get(0).resource();
        assertThat(res.getApiVersion()).isEqualTo("postgresql.cnpg.io/v1");
        assertThat(res.getKind()).isEqualTo("Cluster");
        assertThat(spec(res)).containsEntry("instances", 1);
    }

    @Test
    void haEnforcesThreeInstances() {
        assertThat(spec(adapter.buildCreate(req("HA", 2)).get(0).resource())).containsEntry("instances", 3);
        assertThat(spec(adapter.buildCreate(req("HA", 5)).get(0).resource())).containsEntry("instances", 5);
    }

    @Test
    void storageAndResourcesAreRendered() {
        var res = adapter.buildCreate(req("HA", 3)).get(0).resource();
        @SuppressWarnings("unchecked")
        var storage = (Map<String, Object>) spec(res).get("storage");
        assertThat(storage).containsEntry("size", "20Gi");
        assertThat((Map<String, Object>) spec(res).get("resources")).isNotNull();
    }

    @Test
    void explicitStorageClassIsRendered() {
        var r = req("SINGLE", 1);
        r.setStorageClass("fast-ssd");

        @SuppressWarnings("unchecked")
        var storage = (Map<String, Object>) spec(adapter.buildCreate(r).get(0).resource()).get("storage");
        assertThat(storage).containsEntry("storageClass", "fast-ssd");
    }

    @Test
    void operatorGeneratesCredentials() {
        // CNPG 生成 <cr>-app，平台不写口令 Secret
        assertThat(adapter.usesPlatformPassword()).isFalse();
        assertThat(adapter.buildCreate(req("SINGLE", 1)).get(0).resource()
                .getAdditionalProperties().toString()).doesNotContain("-credentials");
    }

    @Test
    void scalingSingleToMoreInstancesIsRejected() {
        var live = adapter.buildCreate(req("SINGLE", 1)).get(0).resource();
        var scale = new com.coding.common.models.middleware.MiddlewareScaleRequestDTO();
        scale.setMode("SINGLE");
        scale.setReplicas(3);

        assertThatThrownBy(() -> adapter.applyScale(live, PostgresqlMiddlewareAdapter.CRD, scale))
                .hasMessageContaining("单实例形态不支持调整节点数");
    }

    @Test
    void scalingHaUpdatesInstancesAndResources() {
        var live = adapter.buildCreate(req("HA", 3)).get(0).resource();
        var scale = new com.coding.common.models.middleware.MiddlewareScaleRequestDTO();
        scale.setMode("HA");
        scale.setReplicas(5);
        scale.setCpuRequest("1");

        adapter.applyScale(live, PostgresqlMiddlewareAdapter.CRD, scale);

        assertThat(spec(live)).containsEntry("instances", 5);
        @SuppressWarnings("unchecked")
        var requests = (Map<String, Object>) ((Map<String, Object>) spec(live).get("resources")).get("requests");
        assertThat(requests).containsEntry("cpu", "1");
    }

    @Test
    void selectorsUseCnpgLabels() {
        // initdb/join 的 Job Pod 也带 cnpg.io/cluster：状态必须再按 podRole=instance 过滤
        assertThat(adapter.statusSelector("mw-pg-x", "Cluster"))
                .isEqualTo("cnpg.io/cluster=mw-pg-x,cnpg.io/podRole=instance");
        assertThat(adapter.pvcSelector("mw-pg-x", "mwi_1")).isEqualTo("cnpg.io/cluster=mw-pg-x");
        assertThat(adapter.desiredPods(adapter.buildCreate(req("HA", 3)).get(0).resource(), "Cluster"))
                .isEqualTo(3);
    }

    /**
     * 2026-10-27 用户实报：拿平台给的默认账号（{@code app}）`CREATE DATABASE` 报
     * `permission denied to create database` —— CNPG 的应用账号不是 superuser，也没 CREATEDB。
     * 中间件实例的默认账号连库都建不了就没法用，故 bootstrap 时补一条授权
     * （只给 CREATEDB，不摊开 superuser）。
     */
    @Test
    void appUserIsGrantedCreatedbAtBootstrap() {
        var res = adapter.buildCreate(req("SINGLE", 1)).get(0).resource();

        @SuppressWarnings("unchecked")
        var bootstrap = (Map<String, Object>) spec(res).get("bootstrap");
        assertThat(bootstrap).as("实例必须带 bootstrap.initdb").isNotNull();

        @SuppressWarnings("unchecked")
        var initdb = (Map<String, Object>) bootstrap.get("initdb");
        // 库名与属主写死成 app：下面那条 ALTER ROLE 的对象必须与 operator 生成的应用账号一致
        assertThat(initdb).containsEntry("database", "app").containsEntry("owner", "app");
        assertThat(initdb.get("postInitSQL"))
                .isEqualTo(java.util.List.of("ALTER ROLE app CREATEDB;"));
    }
}
