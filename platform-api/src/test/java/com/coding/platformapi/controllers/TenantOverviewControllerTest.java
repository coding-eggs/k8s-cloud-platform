package com.coding.platformapi.controllers;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.NamespaceStatEntry;
import com.coding.common.models.k8s.dto.NamespaceSnapshotDTO;
import com.coding.common.models.k8s.dto.TenantOverviewDTO;
import com.coding.data.models.system.TokenUserInfo;
import com.coding.data.models.system.UserTenantInfo;
import com.coding.platformapi.metrics.MetricsService;
import com.coding.platformapi.metrics.dto.MetricSeriesResponse;
import com.coding.platformapi.models.TenantOverviewRequest;
import com.coding.platformapi.security.AuthContext;
import com.coding.platformapi.security.TenantContextResolver;
import com.coding.platformapi.services.TenantOverviewService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TenantOverviewController}：4 个端点的路由 + 生效租户身份解析。
 *
 * <p>身份解析用**真实** {@link TenantContextResolver}（它无 Spring 依赖，是纯函数）——所以这里的
 * "自管轨传别人的 tenantId 被拒"是一条端到端断言，钉住 controller 确实把
 * {@code requireContext(tokenTenantId(), req.getTenantId())} 放在方法首行、没有绕过它。
 * token 租户身份由 mock 的 {@link AuthContext} 提供（单测里没有 SecurityContext，见报告 ambiguous #1）。
 */
class TenantOverviewControllerTest {

    private final TenantOverviewService overviewService = mock(TenantOverviewService.class);
    private final MetricsService metricsService = mock(MetricsService.class);
    private final AuthContext auth = mock(AuthContext.class);
    /** 真实 resolver：单测直接构造（无状态、无依赖）。 */
    private final TenantContextResolver ctx = new TenantContextResolver();

    private final TenantOverviewController controller =
            new TenantOverviewController(overviewService, metricsService, ctx, auth);

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ===== 身份解析（4 个端点共用同一首行；这里以 overview 为代表钉死 + 一例代表所有）=====

    @Test
    void self_track_rejects_foreign_tenant() {
        when(auth.current()).thenReturn(selfTenant("t-self"));

        assertThatThrownBy(() -> controller.overview(req("t-other")))
                .isInstanceOf(CloudPlatformException.class)
                .hasFieldOrPropertyWithValue("code", EnumResponseType.TENANT_MISMATCH.getCode());
    }

    @Test
    void self_track_resolves_to_token_tenant() {
        when(auth.current()).thenReturn(selfTenant("t-self"));
        when(overviewService.overview("t-self")).thenReturn(new TenantOverviewDTO());

        controller.overview(req("t-self"));

        verify(overviewService).overview("t-self");
    }

    @Test
    void delegate_track_uses_explicit_tenant() {
        when(auth.current()).thenReturn(new TokenUserInfo());   // 平台管理员 / base token：无租户帽
        when(overviewService.overview("t1")).thenReturn(new TenantOverviewDTO());

        controller.overview(req("t1"));

        verify(overviewService).overview("t1");
    }

    @Test
    void delegate_track_requires_explicit_tenant() {
        when(auth.current()).thenReturn(new TokenUserInfo());

        assertThatThrownBy(() -> controller.breakdown(req(null)))
                .isInstanceOf(CloudPlatformException.class)
                .hasFieldOrPropertyWithValue("code", EnumResponseType.TOKEN_TENANT_MISSING.getCode());
    }

    // ===== 路由：4 个路径各落到对应方法 =====

    @Test
    void routes_overview() throws Exception {
        when(auth.current()).thenReturn(selfTenant("t1"));
        when(overviewService.overview("t1")).thenReturn(new TenantOverviewDTO());

        mvc().perform(post("/tenant/overview").contentType(MediaType.APPLICATION_JSON).content("{\"tenantId\":\"t1\"}"))
                .andExpect(status().isOk());

        verify(overviewService).overview("t1");
    }

    @Test
    void routes_breakdown() throws Exception {
        when(auth.current()).thenReturn(selfTenant("t1"));
        NamespaceSnapshotDTO snap = new NamespaceSnapshotDTO();
        when(overviewService.breakdown("t1")).thenReturn(List.of(new NamespaceStatEntry("c1", snap, null)));

        mvc().perform(post("/tenant/overview/breakdown").contentType(MediaType.APPLICATION_JSON).content("{\"tenantId\":\"t1\"}"))
                .andExpect(status().isOk());

        verify(overviewService).breakdown("t1");
    }

    @Test
    void routes_metrics_cpu() throws Exception {
        when(auth.current()).thenReturn(selfTenant("t1"));
        when(overviewService.allocationsByCluster("t1")).thenReturn(Map.of("c1", List.of("a")));
        when(metricsService.tenantCpu("t1", Map.of("c1", List.of("a")), 10L, 20L))
                .thenReturn(new MetricSeriesResponse("核", List.of()));

        mvc().perform(post("/tenant/overview/metrics/cpu").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"t1\",\"start\":10,\"end\":20}"))
                .andExpect(status().isOk());

        verify(metricsService).tenantCpu("t1", Map.of("c1", List.of("a")), 10L, 20L);
    }

    @Test
    void routes_metrics_memory() throws Exception {
        when(auth.current()).thenReturn(selfTenant("t1"));
        when(overviewService.allocationsByCluster("t1")).thenReturn(Map.of("c1", List.of("a")));
        when(metricsService.tenantMemory("t1", Map.of("c1", List.of("a")), 10L, 20L))
                .thenReturn(new MetricSeriesResponse("字节", List.of()));

        mvc().perform(post("/tenant/overview/metrics/memory").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"t1\",\"start\":10,\"end\":20}"))
                .andExpect(status().isOk());

        verify(metricsService).tenantMemory("t1", Map.of("c1", List.of("a")), 10L, 20L);
    }

    // ===== 夹具 =====

    private static TenantOverviewRequest req(String tenantId) {
        TenantOverviewRequest r = new TenantOverviewRequest();
        r.setTenantId(tenantId);
        return r;
    }

    private static TokenUserInfo selfTenant(String tenantId) {
        UserTenantInfo ti = new UserTenantInfo();
        ti.setTenantId(tenantId);
        TokenUserInfo u = new TokenUserInfo();
        u.setTenantInfo(ti);
        return u;
    }
}
