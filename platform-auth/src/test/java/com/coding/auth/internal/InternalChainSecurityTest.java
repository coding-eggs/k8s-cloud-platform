package com.coding.auth.internal;

import com.coding.auth.service.ClientAdminService;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.auth.Oauth2JwkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /internal/**} 改由 JWT 认证（网关透传用户 token）：内部令牌头不再有任何作用；
 * JWK 生成挪进 {@code /internal/jwk}（nginx 对 /auth/internal/ 回 404，前门随之关闭）。
 */
@SpringJUnitWebConfig(InternalSliceConfig.class)
@TestPropertySource(properties = "platform.internal.jwt.audiences=" + InternalSliceConfig.AUDIENCE)
class InternalChainSecurityTest {

    @Autowired
    WebApplicationContext ctx;

    @Autowired
    ClientAdminService clientAdminService;

    @Autowired
    Oauth2JwkMapper jwkMapper;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        Mockito.reset(clientAdminService, jwkMapper);
        when(clientAdminService.list()).thenReturn(List.of());
        mvc = InternalSliceConfig.mockMvc(ctx);
    }

    @Test
    void noTokenIs401Json() throws Exception {
        mvc.perform(post("/internal/clients/list"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":" + EnumResponseType.USER_UN_LOGIN.getCode()
                        + ",\"msg\":\"" + EnumResponseType.USER_UN_LOGIN.getMsg() + "\",\"data\":null}"));
    }

    @Test
    void foreignAudienceIs401() throws Exception {
        mvc.perform(post("/internal/clients/list").header("Authorization", "Bearer " + InternalSliceConfig.token("other")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenPasses() throws Exception {
        mvc.perform(post("/internal/clients/list").header("Authorization", "Bearer " + InternalSliceConfig.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /** 旧的共享密钥头不再被认：只带它不带 JWT → 401 */
    @Test
    void internalTokenHeaderNoLongerAuthenticates() throws Exception {
        mvc.perform(post("/internal/clients/list").header("X-Platform-Internal-Token", "anything"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void jwkGenerateMovedUnderInternal() throws Exception {
        mvc.perform(post("/internal/jwk/generate").header("Authorization", "Bearer " + InternalSliceConfig.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(jwkMapper).insertSelective(any());
    }

    @Test
    void jwkGenerateRequiresToken() throws Exception {
        mvc.perform(post("/internal/jwk/generate")).andExpect(status().isUnauthorized());
        verify(jwkMapper, never()).insertSelective(any());
    }

    /** 旧路径不再有处理器：不会生成密钥（GlobalExceptionHandler 把「无处理器」包成 HTTP 200 + 非 200 业务码） */
    @Test
    void legacyJwkPathIsGone() throws Exception {
        mvc.perform(post("/jwk/generate").header("Authorization", "Bearer " + InternalSliceConfig.token()))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(200)));
        verify(jwkMapper, never()).insertSelective(any());
    }
}
