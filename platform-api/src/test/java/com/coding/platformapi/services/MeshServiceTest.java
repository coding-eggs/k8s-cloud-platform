package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.MeshStatusDTO;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.platformapi.k8s.K8sMeshClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * mesh-status 的组装口径（四个字段两个来源）。2026-10-10 起 discovery 那半由本层从 capability 列派生
 * （以前在 k8s-core 的 {@code MeshOperations} 里），故把原 {@code MeshOperationsTest} 的 capability 用例
 * 搬到这一层，再加 ambient 的降级用例。
 *
 * <p>三条要钉死的口径：
 * <ol>
 *   <li>三个 discovery flag 只由 capability 列决定 —— <b>与集群当前连不连得上无关</b>；
 *       未探测（空列）的语义是"未探测"而非"没装"（全 false，前端提示未探测）。</li>
 *   <li>{@code gatewayApiVersions} 只取该 group 的 versions，不混进别的 group。</li>
 *   <li>ambient 活探测失败 → {@code istioAmbient=false} 且<b>不抛</b>；而同一个集群的
 *       {@code hasGatewayApi} 仍照常为 true —— "不知道有没有 ztunnel"不等于"没装 Gateway API"。</li>
 * </ol>
 * capability 用真 {@link ClusterCapabilityService} + mock mapper（照 {@code ClusterServiceTest} 的做法），
 * k8s-server 那一跳用 mock client。
 */
class MeshServiceTest {

    private final K8sClusterMapper clusterMapper = mock(K8sClusterMapper.class);
    private final K8sMeshClient k8s = mock(K8sMeshClient.class);
    private final MeshService svc = new MeshService(k8s,
            new ClusterCapabilityService(clusterMapper, JsonMapper.builder().build()));

    @Test
    void meshStatus_derives_discovery_from_capability_and_ambient_from_live_probe() {
        stubCapability("{\"gateway.networking.k8s.io\":[\"v1\",\"v1beta1\"],\"networking.istio.io\":[\"v1\"]}");
        when(k8s.meshAmbient("c1")).thenReturn(true);

        MeshStatusDTO dto = svc.meshStatus("c1");

        assertThat(dto.isHasGatewayApi()).isTrue();
        assertThat(dto.getGatewayApiVersions()).containsExactly("v1", "v1beta1");
        assertThat(dto.isHasIstio()).isTrue();   // 命中 networking.istio.io
        assertThat(dto.isIstioAmbient()).isTrue();
    }

    @Test
    void meshStatus_hasIstio_also_true_for_istio_io_group() {
        stubCapability("{\"istio.io\":[\"v1alpha1\"]}");
        when(k8s.meshAmbient("c1")).thenReturn(false);

        MeshStatusDTO dto = svc.meshStatus("c1");

        assertThat(dto.isHasIstio()).isTrue();
        assertThat(dto.isHasGatewayApi()).isFalse();
        assertThat(dto.getGatewayApiVersions()).isEmpty();
        assertThat(dto.isIstioAmbient()).isFalse();
    }

    @Test
    void meshStatus_empty_capability_means_not_probed_not_absent() {
        stubCapability(null);
        when(k8s.meshAmbient("c1")).thenReturn(false);

        MeshStatusDTO dto = svc.meshStatus("c1");

        // 「未探测」→ 全 false（不瞎猜"没装"）；versions 空列表而不是 null
        assertThat(dto.isHasGatewayApi()).isFalse();
        assertThat(dto.getGatewayApiVersions()).isEmpty();
        assertThat(dto.isHasIstio()).isFalse();
    }

    @Test
    void meshStatus_tolerates_broken_capability_json() {
        stubCapability("{ not json");
        when(k8s.meshAmbient("c1")).thenReturn(false);

        assertThat(svc.meshStatus("c1").isHasGatewayApi()).isFalse();
    }

    @Test
    void meshStatus_ambient_probe_failure_degrades_to_false_without_throwing() {
        stubCapability("{\"gateway.networking.k8s.io\":[\"v1\"],\"networking.istio.io\":[\"v1\"]}");
        when(k8s.meshAmbient("c1")).thenThrow(new RuntimeException("集群不可达"));

        MeshStatusDTO dto = svc.meshStatus("c1");

        // ambient 是信息位不是门禁：探测失败 → false，且不抛
        assertThat(dto.isIstioAmbient()).isFalse();
        // 而 discovery 读 DB 列，不受集群连通性影响 —— 这正是本 Phase 要保住的那条线
        assertThat(dto.isHasGatewayApi()).isTrue();
        assertThat(dto.isHasIstio()).isTrue();
    }

    private void stubCapability(String capabilityJson) {
        K8sCluster c = new K8sCluster();
        c.setClusterId("c1");
        c.setCapability(capabilityJson);
        when(clusterMapper.selectByPrimaryKey("c1")).thenReturn(c);
    }
}
