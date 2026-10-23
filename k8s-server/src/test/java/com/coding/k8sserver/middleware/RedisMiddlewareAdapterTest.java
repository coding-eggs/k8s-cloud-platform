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
    void sentinelCrUsesSentinelImage() {
        // 真集群验证：sentinel CR 用 redis 镜像时，容器以 standalone 起不来（30s 后被 SIGTERM），
        // 三个哨兵 Pod 永远 CrashLoop；必须用 redis-sentinel 镜像
        var out = adapter.buildCreate(req("SENTINEL", 3));

        assertThat(imageOf(out.get(0).resource())).isEqualTo("quay.io/opstree/redis:v7.0.15");
        assertThat(imageOf(out.get(1).resource())).isEqualTo("quay.io/opstree/redis-sentinel:v7.0.15");
    }

    @SuppressWarnings("unchecked")
    private Object imageOf(GenericKubernetesResource res) {
        Map<String, Object> kc = (Map<String, Object>) spec(res).get("kubernetesConfig");
        return kc.get("image");
    }

    @Test
    void sentinelStatusSelectorCoversSentinelPods() {
        // 哨兵 Pod 打的是 app=<name>-sentinel：只看 app=<name> 会把 CrashLoop 的哨兵漏掉、误报 READY
        assertThat(adapter.statusSelector("mw-redis-x", "RedisSentinel"))
                .isEqualTo("app in (mw-redis-x,mw-redis-x-sentinel)");
        assertThat(adapter.statusSelector("mw-redis-x", "Redis")).isEqualTo("app=mw-redis-x");
        assertThat(adapter.statusSelector("mw-redis-x", "RedisReplication")).isEqualTo("app=mw-redis-x");
    }

    @Test
    void desiredPodsCountsSentinelPods() {
        var out = adapter.buildCreate(req("SENTINEL", 2));

        // 数据节点从 RedisReplication 里读，哨兵固定 3 个另加
        assertThat(adapter.desiredPods(out.get(0).resource(), "RedisReplication")).isEqualTo(5);
        assertThat(adapter.desiredPods(adapter.buildCreate(req("STANDALONE", 1)).get(0).resource(), "Redis"))
                .isEqualTo(1);
    }

    @Test
    void statusSelectorPrefersThePlatformInstanceLabel() {
        // 集合式 selector 在 fabric8 里只命中数据 Pod（5 个只数到 2 个）；
        // 两个 CR 的 Pod 都带平台实例标签，按它取最稳
        assertThat(adapter.statusSelector("mw-redis-x", "RedisReplication", "mwi_9"))
                .isEqualTo("platform.coding/middleware-instance=mwi_9");
        // 没有 id 时退回按 app 标签（兼容手工调用）
        assertThat(adapter.statusSelector("mw-redis-x", "Redis", null)).isEqualTo("app=mw-redis-x");
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
    void clusterDefaultKeepsOneFollowerPerShard() {
        var res = adapter.buildCreate(req("CLUSTER", 3)).get(0).resource();

        // Operator 的 redisFollower.replicas 是从节点总数；不设时默认等于 clusterSize
        assertThat((Map<String, Object>) spec(res).get("redisFollower"))
                .containsEntry("replicas", 3);
        assertThat(adapter.desiredPods(res, "RedisCluster")).isEqualTo(6);   // 3 leader + 3 follower
    }

    @Test
    void clusterHonoursReplicasPerShard() {
        var r = req("CLUSTER", 3);
        r.setReplicasPerShard(2);
        var res = adapter.buildCreate(r).get(0).resource();

        assertThat((Map<String, Object>) spec(res).get("redisFollower")).containsEntry("replicas", 6);
        assertThat(res.getMetadata().getAnnotations())
                .containsEntry(RedisMiddlewareAdapter.REPLICAS_PER_SHARD_ANNOTATION, "2");
        assertThat(adapter.desiredPods(res, "RedisCluster")).isEqualTo(9);   // 3 + 6
    }

    @Test
    void scalingClusterKeepsFollowersInSync() {
        var live = adapter.buildCreate(req("CLUSTER", 3)).get(0).resource();
        var scale = new com.coding.common.models.middleware.MiddlewareScaleRequestDTO();
        scale.setReplicas(5);

        adapter.applyScale(live, RedisMiddlewareAdapter.CLUSTER, scale);

        assertThat(spec(live)).containsEntry("clusterSize", 5);
        assertThat((Map<String, Object>) spec(live).get("redisFollower")).containsEntry("replicas", 5);
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

    // ---------------- 业务指标（redis-exporter 边车） ----------------

    @Test
    @SuppressWarnings("unchecked")
    void exporterSidecarIsEnabledWithPasswordFromSecret() {
        var r = req("STANDALONE", 1);
        r.setMetricsEnabled(true);
        var out = adapter.buildCreate(r);
        Map<String, Object> exporter = (Map<String, Object>) spec(out.get(0).resource()).get("redisExporter");

        assertThat(exporter).containsEntry("enabled", true).containsEntry("port", 9121);
        assertThat((String) exporter.get("image")).contains("redis-exporter");
        // 口令必须从 Secret 取：不带 REDIS_PASSWORD 时 exporter 的 INFO 会失败，指标全空
        var env = (List<Map<String, Object>>) exporter.get("env");
        assertThat(env).anySatisfy(e -> assertThat(e).containsEntry("name", "REDIS_PASSWORD"));
    }

    @Test
    void metricsAreNotCollectedUnlessTheSwitchIsOn() {
        // 默认关：不装 exporter 边车（资源用量不受影响）
        var out = adapter.buildCreate(req("STANDALONE", 1));

        assertThat(spec(out.get(0).resource())).doesNotContainKey("redisExporter");
    }

    @Test
    void sentinelCrHasNoExporterSidecar() {
        var r = req("SENTINEL", 3);
        r.setMetricsEnabled(true);
        var out = adapter.buildCreate(r);

        // 数据 CR 装 exporter；哨兵 CR 不装（哨兵 Pod 没有可导出的数据）
        assertThat(spec(out.get(0).resource())).containsKey("redisExporter");
        assertThat(spec(out.get(1).resource())).doesNotContainKey("redisExporter");
    }

    @Test
    void metricsSelectorOnlyMatchesDataPods() {
        var spec = adapter.metricsSpec("mw-redis-abc12345", "RedisReplication", "mwi_1");

        assertThat(spec.port()).isEqualTo(9121);
        // 哨兵 Pod 是 app=<name>-sentinel：选进来只会得到 down 的 target
        assertThat(spec.podSelector()).isEqualTo("app=mw-redis-abc12345");
    }
}
