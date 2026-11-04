package com.coding.auth.internal;

import com.coding.auth.client.ClientCreated;
import com.coding.auth.service.ClientAdminService;
import com.coding.common.audit.AuditAction;
import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.auth.Oauth2JwkMapper;
import com.coding.data.mapper.system.PlatformAuditLogMapper;
import com.coding.data.models.system.PlatformAuditLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 客户端管理与 JWK 生成的审计改在 auth 自己写（api 转发层删掉后，写操作只经过这里）。
 * action / target 与原 api {@code OAuthClientController} 一致，审计页的筛选不受影响。
 */
@SpringJUnitWebConfig(InternalSliceConfig.class)
@TestPropertySource(properties = "platform.internal.jwt.audiences=" + InternalSliceConfig.AUDIENCE)
class InternalClientControllerAuditTest {

    @Autowired
    WebApplicationContext ctx;

    @Autowired
    ClientAdminService clientAdminService;

    @Autowired
    Oauth2JwkMapper jwkMapper;

    @Autowired
    PlatformAuditLogMapper auditMapper;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        Mockito.reset(clientAdminService, jwkMapper, auditMapper);
        mvc = InternalSliceConfig.mockMvc(ctx);
    }

    private PlatformAuditLog recorded() {
        ArgumentCaptor<PlatformAuditLog> c = ArgumentCaptor.forClass(PlatformAuditLog.class);
        verify(auditMapper).insert(c.capture());
        return c.getValue();
    }

    @Test
    void createSuccessIsAudited() throws Exception {
        when(clientAdminService.create(any())).thenReturn(new ClientCreated(null, null));

        mvc.perform(post("/internal/clients/create").header("Authorization", "Bearer " + InternalSliceConfig.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"clientId\":\"cli-1\"}"))
                .andExpect(jsonPath("$.code").value(200));

        PlatformAuditLog r = recorded();
        assertThat(r.getAction()).isEqualTo(AuditAction.OAUTH_CLIENT_CREATE);
        assertThat(r.getResult()).isEqualTo("SUCCESS");
        assertThat(r.getActorUsername()).isEqualTo("alice");
        assertThat(r.getTargetName()).isEqualTo("cli-1");
        assertThat(r.getRequestPath()).isEqualTo("/internal/clients/create");
    }

    @Test
    void ruleViolationIsAuditedAsFailed() throws Exception {
        when(clientAdminService.create(any()))
                .thenThrow(new CloudPlatformException(EnumResponseType.OAUTH_CLIENT_INVALID, "redirect_uri 不能为空"));

        mvc.perform(post("/internal/clients/create").header("Authorization", "Bearer " + InternalSliceConfig.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"clientId\":\"cli-1\"}"))
                .andExpect(jsonPath("$.code").value(EnumResponseType.OAUTH_CLIENT_INVALID.getCode()));

        PlatformAuditLog r = recorded();
        assertThat(r.getResult()).isEqualTo("FAILED");
        assertThat(r.getErrorCode()).isEqualTo(String.valueOf(EnumResponseType.OAUTH_CLIENT_INVALID.getCode()));
    }

    @Test
    void deleteTargetsId() throws Exception {
        mvc.perform(post("/internal/clients/delete").header("Authorization", "Bearer " + InternalSliceConfig.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"row-9\"}"))
                .andExpect(jsonPath("$.code").value(200));

        PlatformAuditLog r = recorded();
        assertThat(r.getAction()).isEqualTo(AuditAction.OAUTH_CLIENT_DELETE);
        assertThat(r.getTargetName()).isEqualTo("row-9");
    }

    @Test
    void jwkGenerateIsAudited() throws Exception {
        mvc.perform(post("/internal/jwk/generate").header("Authorization", "Bearer " + InternalSliceConfig.token()))
                .andExpect(jsonPath("$.code").value(200));

        assertThat(recorded().getAction()).isEqualTo(AuditAction.JWK_GENERATE);
    }

    /** 只读接口不记审计 */
    @Test
    void listIsNotAudited() throws Exception {
        mvc.perform(post("/internal/clients/list").header("Authorization", "Bearer " + InternalSliceConfig.token()))
                .andExpect(jsonPath("$.code").value(200));

        Mockito.verifyNoInteractions(auditMapper);
    }
}
