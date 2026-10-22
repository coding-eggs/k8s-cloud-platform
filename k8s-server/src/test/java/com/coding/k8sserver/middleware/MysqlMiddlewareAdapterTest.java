package com.coding.k8sserver.middleware;

import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import org.junit.jupiter.api.Test;

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
    void statusSelectorTargetsMysqlPods() {
        assertThat(adapter.statusSelector("mw-mysql-x"))
                .isEqualTo("app.kubernetes.io/instance=mw-mysql-x,app.kubernetes.io/component=mysql");
    }
}
