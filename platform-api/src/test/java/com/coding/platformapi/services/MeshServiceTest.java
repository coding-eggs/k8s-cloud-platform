package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.MeshStatusDTO;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.platformapi.models.ComponentStatusDTO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * mesh-status 的组装口径（S1.2 收口后：四个字段全部来自组件清单 + capability 列的 versions）。
 *
 * <p>收口前的三层来源（ztunnel 活探 + 两组 capability 各判一次）已合并成一份派生实现
 * （{@link ClusterComponentService}）。本测试钉住三件事：
 * <ol>
 *   <li>三个布尔按组件 <b>state == INSTALLED_HEALTHY</b> 派生 —— 「装了但不健康」不算可用；</li>
 *   <li>{@code gatewayApiVersions} 仍来自 capability 列（组件清单不回答"哪些版本"）；</li>
 *   <li>未探测（全部 UNKNOWN）→ 全 false，<b>不抛</b> —— "不知道"不是"没装"，但 DTO 只有布尔，
 *       全 false 与既有"未探测"渲染一致。</li>
 * </ol>
 */
class MeshServiceTest {

    private final K8sClusterMapper clusterMapper = mock(K8sClusterMapper.class);
    private final ClusterComponentService componentService = mock(ClusterComponentService.class);
    private final ClusterCapabilityService capabilityService =
            new ClusterCapabilityService(clusterMapper, JsonMapper.builder().build());
    private final MeshService svc =
            new MeshService(mock(com.coding.platformapi.k8s.K8sMeshClient.class), componentService, capabilityService);

    @Test
    void meshStatus_derives_from_the_component_states() {
        stubCapability("{\"gateway.networking.k8s.io\":[\"v1\",\"v1beta1\"],\"networking.istio.io\":[\"v1\"]}");
        when(componentService.status("c1")).thenReturn(List.of(
                status("istio", ClusterComponentService.HEALTHY),
                status("gateway-api", ClusterComponentService.HEALTHY)));

        MeshStatusDTO dto = svc.meshStatus("c1");

        assertThat(dto.isHasIstio()).isTrue();
        assertThat(dto.isHasGatewayApi()).isTrue();
        // ambient 跟着 istio 走（同一个安装单元，2026-10-18 合并）
        assertThat(dto.isIstioAmbient()).isTrue();
        // versions 仍来自 capability 列（组件清单不回答"哪些版本"）
        assertThat(dto.getGatewayApiVersions()).containsExactly("v1", "v1beta1");
    }

    @Test
    void anUnknownIstioDoesNotLeakIntoGatewayApi() {
        // Istio 探测不到 → 退回 capability 快照；不影响 Gateway API 自己的判定
        stubCapability("{\"networking.istio.io\":[\"v1\"],\"gateway.networking.k8s.io\":[\"v1\"]}");
        when(componentService.status("c1")).thenReturn(List.of(
                status("istio", ClusterComponentService.UNKNOWN),
                status("gateway-api", ClusterComponentService.HEALTHY)));

        MeshStatusDTO dto = svc.meshStatus("c1");

        assertThat(dto.isIstioAmbient()).isFalse();
        assertThat(dto.isHasIstio()).isTrue();
        assertThat(dto.isHasGatewayApi()).isTrue();
    }

    @Test
    void allUnknownMeansNotProbedNotAbsent() {
        // 集群探测不到（或尚未探测）→ 全 false；与既有「未探测」的渲染一致，不抛
        stubCapability(null);
        when(componentService.status("c1")).thenReturn(List.of(
                status("istio", ClusterComponentService.UNKNOWN),
                status("gateway-api", ClusterComponentService.UNKNOWN)));

        MeshStatusDTO dto = svc.meshStatus("c1");

        assertThat(dto.isHasGatewayApi()).isFalse();
        assertThat(dto.getGatewayApiVersions()).isEmpty();   // 空列表而不是 null
        assertThat(dto.isHasIstio()).isFalse();
        assertThat(dto.isIstioAmbient()).isFalse();
    }

    @Test
    void aNotInstalledIstioIsNotAmbient() {
        stubCapability("{\"gateway.networking.k8s.io\":[\"v1\"]}");
        when(componentService.status("c1")).thenReturn(List.of(
                status("istio", ClusterComponentService.NOT_INSTALLED),
                status("gateway-api", ClusterComponentService.HEALTHY)));

        assertThat(svc.meshStatus("c1").isIstioAmbient()).isFalse();
    }

    @Test
    void unknownComponentsFallBackToTheCapabilitySnapshot() {
        // ⭐ 评审修正：集群不可达 → 组件 UNKNOWN。此时**不能**说"没装"（本仓修过的坑），
        //    退回 DB 快照（capability 列非空即装过）
        stubCapability("{\"gateway.networking.k8s.io\":[\"v1\"],\"networking.istio.io\":[\"v1\"]}");
        when(componentService.status("c1")).thenReturn(List.of(
                status("istio", ClusterComponentService.UNKNOWN),
                status("gateway-api", ClusterComponentService.UNKNOWN)));

        MeshStatusDTO dto = svc.meshStatus("c1");

        assertThat(dto.isHasIstio()).isTrue();
        assertThat(dto.isHasGatewayApi()).isTrue();
        assertThat(dto.isIstioAmbient()).isFalse(); // 快照不回答 ztunnel
    }

    @Test
    void aConfirmedMissingIstioStaysFalse() {
        // 探测**成功了**且没命中（NOT_INSTALLED）时，快照虽还留着 group 也要判 false ——
        // 那是"CRD 在、istiod 没了"，不是"不知道"
        stubCapability("{\"networking.istio.io\":[\"v1\"]}");
        when(componentService.status("c1")).thenReturn(List.of(
                status("istio", ClusterComponentService.NOT_INSTALLED)));

        assertThat(svc.meshStatus("c1").isHasIstio()).isFalse();
    }

    @Test
    void aMissingComponentRowIsTreatedAsAbsent() {
        // 组件清单里没有这一条（理论上不会发生）→ 按不可用处理，别 NPE
        stubCapability("{\"gateway.networking.k8s.io\":[\"v1\"]}");
        when(componentService.status("c1")).thenReturn(List.of(status("gateway-api", ClusterComponentService.HEALTHY)));

        MeshStatusDTO dto = svc.meshStatus("c1");

        assertThat(dto.isHasGatewayApi()).isTrue();
        assertThat(dto.isHasIstio()).isFalse();
        assertThat(dto.isIstioAmbient()).isFalse();
    }

    private static ComponentStatusDTO status(String code, String state) {
        ComponentStatusDTO s = new ComponentStatusDTO();
        s.setCode(code);
        s.setState(state);
        return s;
    }

    private void stubCapability(String capabilityJson) {
        K8sCluster c = new K8sCluster();
        c.setClusterId("c1");
        c.setCapability(capabilityJson);
        when(clusterMapper.selectByPrimaryKey("c1")).thenReturn(c);
    }
}
