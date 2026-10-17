package com.coding.platformapi.controllers;

import com.coding.data.models.system.PlatformComponentOperation;
import com.coding.data.models.system.TokenUserInfo;
import com.coding.platformapi.models.ComponentApplyRequest;
import com.coding.platformapi.security.AuthContext;
import com.coding.platformapi.services.ComponentInstallService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** S3.6 批① 三条组件生命周期端点的路径与委托。 */
class ComponentLifecycleEndpointsTest {

    private final ComponentInstallService service = mock(ComponentInstallService.class);
    private final AuthContext auth = mock(AuthContext.class);

    @BeforeEach
    void currentUser() {
        TokenUserInfo me = new TokenUserInfo();
        me.setUsername("admin");
        when(auth.current()).thenReturn(me);
    }

    private MockMvc mvc() {
        ClusterController controller = new ClusterController();
        ReflectionTestUtils.setField(controller, "componentInstallService", service);
        ReflectionTestUtils.setField(controller, "auth", auth);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void applyDelegatesAndReturnsOperationId() throws Exception {
        when(service.apply(any(ComponentApplyRequest.class), eq("admin"))).thenReturn(42L);

        mvc().perform(post("/cluster/components/apply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clusterId\":\"c1\",\"componentCode\":\"metrics-server\",\"action\":\"INSTALL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(42));

        verify(service).apply(any(ComponentApplyRequest.class), eq("admin"));
    }

    @Test
    void operationGetReadsById() throws Exception {
        PlatformComponentOperation op = new PlatformComponentOperation();
        op.setId(7L);
        op.setStatus("RUNNING");
        when(service.get(7L)).thenReturn(op);

        mvc().perform(post("/cluster/components/operations/get").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operationId\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        verify(service).get(7L);
    }

    @Test
    void operationListReturnsLatestPerComponent() throws Exception {
        PlatformComponentOperation op = new PlatformComponentOperation();
        op.setComponentCode("metrics-server");
        op.setStatus("SUCCESS");
        when(service.latestByCluster("c1")).thenReturn(List.of(op));

        mvc().perform(post("/cluster/components/operations/list").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clusterId\":\"c1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].componentCode").value("metrics-server"));

        verify(service).latestByCluster("c1");
    }
}
