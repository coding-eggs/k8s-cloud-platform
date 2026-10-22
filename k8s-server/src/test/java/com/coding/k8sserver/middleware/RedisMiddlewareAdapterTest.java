package com.coding.k8sserver.middleware;

import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RedisMiddlewareAdapterTest {

    private final RedisMiddlewareAdapter adapter = new RedisMiddlewareAdapter();

    private MiddlewareCreateRequestDTO req(String mode, int replicas) {
        MiddlewareCreateRequestDTO r = new MiddlewareCreateRequestDTO();
        r.setInstanceId("mwi_1");
        r.setClusterId("c1");
        r.setCode("redis");
        r.setMode(mode);
        r.setNamespace("demo");
        r.setName("mw-redis-abc12345");
        r.setReplicas(replicas);
        r.setCpuRequest("100m");
        r.setCpuLimit("500m");
        r.setMemoryRequest("128Mi");
        r.setMemoryLimit("512Mi");
        r.setStorageSize("10Gi");
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> spec(GenericKubernetesResource res) {
        return (Map<String, Object>) res.getAdditionalProperties().get("spec");
    }

    @Test
    void standaloneBuildsOneRedisResource() {
        var out = adapter.buildCreate(req("STANDALONE", 1));

        assertThat(out).hasSize(1);
        var res = out.get(0).resource();
        assertThat(res.getApiVersion()).isEqualTo("redis.redis.opstreelabs.in/v1beta2");
        assertThat(res.getKind()).isEqualTo("Redis");
        assertThat(res.getMetadata().getName()).isEqualTo("mw-redis-abc12345");
        assertThat(res.getMetadata().getNamespace()).isEqualTo("demo");
    }

    @Test
    void sentinelBuildsReplicationAndSentinel() {
        var out = adapter.buildCreate(req("SENTINEL", 3));

        assertThat(out).extracting(c -> c.resource().getKind())
                .containsExactly("RedisReplication", "RedisSentinel");
        assertThat(spec(out.get(0).resource())).containsEntry("clusterSize", 3);
        assertThat(spec(out.get(1).resource())).containsEntry("clusterSize", 3);
        assertThat(sentinelReplicationName(out.get(1).resource())).isEqualTo("mw-redis-abc12345");
    }

    @SuppressWarnings("unchecked")
    private Object sentinelReplicationName(GenericKubernetesResource res) {
        Map<String, Object> cfg = (Map<String, Object>) spec(res).get("redisSentinelConfig");
        return cfg.get("redisReplicationName");
    }

    @Test
    void clusterBuildsRedisClusterWithReplicas() {
        var out = adapter.buildCreate(req("CLUSTER", 3));

        assertThat(out.get(0).resource().getKind()).isEqualTo("RedisCluster");
        assertThat(spec(out.get(0).resource())).containsEntry("clusterSize", 3);
        assertThat(spec(out.get(0).resource())).containsEntry("persistenceEnabled", true);
    }

    @Test
    void clusterEnforcesMinimumOfThreeShards() {
        var out = adapter.buildCreate(req("CLUSTER", 1));

        assertThat(spec(out.get(0).resource())).containsEntry("clusterSize", 3);
    }

    @Test
    void passwordIsReferencedBySecretNotInlined() {
        var r = req("STANDALONE", 1);
        r.setPassword("s3cret-should-not-leak");
        var out = adapter.buildCreate(r);

        assertThat(out.get(0).resource().getAdditionalProperties().toString())
                .doesNotContain("s3cret-should-not-leak")
                .contains("mw-redis-abc12345-credentials");
    }

    @Test
    void labelsMarkTheResourceAsManaged() {
        var out = adapter.buildCreate(req("STANDALONE", 1));

        assertThat(out.get(0).resource().getMetadata().getLabels())
                .containsEntry("platform.coding/managed-by", "middleware")
                .containsEntry("platform.coding/middleware-instance", "mwi_1");
    }

    @Test
    void pvcTemplateCarriesPlatformLabels() {
        // 删除实例时按这个标签找 PVC：operator 自己打的标签不保证存在
        var out = adapter.buildCreate(req("STANDALONE", 1));
        @SuppressWarnings("unchecked")
        var storage = (Map<String, Object>) spec(out.get(0).resource()).get("storage");
        @SuppressWarnings("unchecked")
        var meta = (Map<String, Object>) ((Map<String, Object>) storage.get("volumeClaimTemplate")).get("metadata");
        @SuppressWarnings("unchecked")
        var labels = (Map<String, Object>) meta.get("labels");
        assertThat(labels).containsEntry("platform.coding/middleware-instance", "mwi_1");
    }

    @Test
    void scaleClampsClusterToThreeShards() {
        var live = adapter.buildCreate(req("CLUSTER", 3)).get(0).resource();
        var scaleReq = new com.coding.common.models.middleware.MiddlewareScaleRequestDTO();
        scaleReq.setReplicas(1);

        adapter.applyScale(live, RedisMiddlewareAdapter.CLUSTER, scaleReq);

        assertThat(spec(live)).containsEntry("clusterSize", 3);
    }

    @Test
    void unknownModeIsRejected() {
        var r = req("REPLICA", 1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.buildCreate(r))
                .hasMessageContaining("不支持的形态");
    }
}
