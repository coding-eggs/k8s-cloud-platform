package com.coding.platformapi.controllers;

import com.coding.data.models.system.PlatformAuditLog;
import com.coding.data.models.system.PlatformAuditLogQuery;
import com.coding.platformapi.services.AuditService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AuditController}：两个端点的路由与包装。
 *
 * <p>业务在 {@link AuditService}（已单独测过），这里只钉 HTTP 形状 —— 与本仓
 * {@code TenantOverviewControllerTest} 同一写法（standalone MockMvc + 替身 service，不起 Spring 上下文）。
 */
class AuditControllerTest {

    AuditService auditService = mock(AuditService.class);

    MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuditController(auditService)).build();

    @Test
    void listReturnsRows() throws Exception {
        PlatformAuditLog row = new PlatformAuditLog();
        row.setActorUsername("alice");
        row.setResult("SUCCESS");
        when(auditService.query(any())).thenReturn(List.of(row));

        mvc.perform(post("/audit/list").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].actorUsername").value("alice"))
                .andExpect(jsonPath("$.data[0].result").value("SUCCESS"));
    }

    @Test
    void listMapsFiltersIntoQuery() throws Exception {
        when(auditService.query(any())).thenReturn(List.of());

        mvc.perform(post("/audit/list").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"DENIED\",\"actorUsername\":\"bob\",\"limit\":10}"))
                .andExpect(status().isOk());

        ArgumentCaptor<PlatformAuditLogQuery> captor = ArgumentCaptor.forClass(PlatformAuditLogQuery.class);
        verify(auditService).query(captor.capture());
        assertThat(captor.getValue().getResult()).isEqualTo("DENIED");
        assertThat(captor.getValue().getActorUsername()).isEqualTo("bob");
        assertThat(captor.getValue().getLimit()).isEqualTo(10);
    }

    @Test
    void purgeReturnsDeletedCount() throws Exception {
        when(auditService.purge(any(), any())).thenReturn(7);

        mvc.perform(post("/audit/purge").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-01-01T00:00:00\",\"to\":\"2026-02-01T00:00:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(7));

        verify(auditService).purge(any(), any());
    }
}
