package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.middleware.MiddlewareUserCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareUserCredentialDTO;
import com.coding.common.models.middleware.MiddlewareUserDeleteRequestDTO;
import com.coding.data.mapper.system.PlatformMiddlewareInstanceMapper;
import com.coding.data.models.system.PlatformMiddlewareInstance;
import com.coding.platformapi.k8s.K8sMiddlewareClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台侧的归属校验 / 口令生成 / 一次性返回。
 */
class MiddlewareUserServiceTest {

    private final PlatformMiddlewareInstanceMapper instances = mock(PlatformMiddlewareInstanceMapper.class);
    private final K8sMiddlewareClient client = mock(K8sMiddlewareClient.class);
    private MiddlewareUserService service;

    @BeforeEach
    void setUp() {
        service = new MiddlewareUserService(instances, client, JsonMapper.builder().build());
    }

    @Test
    void createGeneratesAPasswordWhenBlankAndReturnsTheCredentialOnce() {
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(instance("mysql", "t1", null));
        when(client.createUser(anyString(), any())).thenAnswer(inv -> {
            MiddlewareUserCreateRequestDTO dto = inv.getArgument(1);
            MiddlewareUserCredentialDTO out = new MiddlewareUserCredentialDTO();
            out.setUsername(dto.getUsername());
            out.setPassword(dto.getPassword());
            out.setSecretName("mw-mysql-abcdefgh-users");
            out.setSecretKey(dto.getUsername());
            return out;
        });

        var out = service.create(req("alice", null, "只读"), "t1");

        assertThat(out.getUsername()).isEqualTo("alice");
        assertThat(out.getPassword()).hasSizeGreaterThanOrEqualTo(8);
        assertThat(out.getSecretName()).isEqualTo("mw-mysql-abcdefgh-users");
        ArgumentCaptor<MiddlewareUserCreateRequestDTO> sent =
                ArgumentCaptor.forClass(MiddlewareUserCreateRequestDTO.class);
        verify(client).createUser(anyString(), sent.capture());
        assertThat(sent.getValue().getName()).isEqualTo("mw-mysql-abcdefgh");
        assertThat(sent.getValue().getNamespace()).isEqualTo("demo");
        assertThat(sent.getValue().getPassword()).hasSizeGreaterThanOrEqualTo(8);
    }

    @Test
    void createKeepsACustomPasswordTrimmed() {
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(instance("mysql", "t1", null));
        when(client.createUser(anyString(), any())).thenReturn(new MiddlewareUserCredentialDTO());

        service.create(req("alice", "  my-own-pw-123  ", "读写"), "t1");

        ArgumentCaptor<MiddlewareUserCreateRequestDTO> sent =
                ArgumentCaptor.forClass(MiddlewareUserCreateRequestDTO.class);
        verify(client).createUser(anyString(), sent.capture());
        assertThat(sent.getValue().getPassword()).isEqualTo("my-own-pw-123");
    }

    @Test
    void unsupportedCodeIsRejected() {
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(instance("oracle", "t1", null));

        assertThatThrownBy(() -> service.create(req("alice", null, "只读"), "t1"))
                .hasMessageContaining("暂不支持用户管理");
        verify(client, never()).createUser(anyString(), any());
    }

    @Test
    void reservedBuiltinNamesCannotBeCreatedOrDropped() {
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(instance("mysql", "t1", null));
        assertThatThrownBy(() -> service.create(req("root", null, "只读"), "t1"))
                .hasMessageContaining("内置账号");

        var del = new MiddlewareUserDeleteRequestDTO();
        del.setInstanceId("mwi_1");
        del.setUsername("root");
        assertThatThrownBy(() -> service.drop(del, "t1"))
                .hasMessageContaining("内置账号");
    }

    @Test
    void redisCannotCreateUsers() {
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(instance("redis", "t1", null));

        assertThatThrownBy(() -> service.create(req("alice", null, "只读"), "t1"))
                .hasMessageContaining("Redis 不支持新增用户");
    }

    @Test
    void nacosWithoutAuthRejectsUserManagement() {
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(instance("nacos", "t1", "{\"authEnabled\":false}"));

        var q = new com.coding.common.models.middleware.MiddlewareUserListRequestDTO();
        q.setInstanceId("mwi_1");
        assertThatThrownBy(() -> service.list(q, "t1"))
                .hasMessageContaining("没有开启鉴权");
    }

    @Test
    void otherTenantsInstanceIsNotOperable() {
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(instance("mysql", "t2", null));

        assertThatThrownBy(() -> service.create(req("alice", null, "只读"), "t1"))
                .hasMessageContaining("无权操作该实例");
    }

    @Test
    void errorsNeverContainThePassword() {
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(instance("mysql", "t1", null));
        when(client.createUser(anyString(), any())).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> service.create(req("alice", "pw-SECRET-123", "只读"), "t1"))
                .hasMessageNotContaining("pw-SECRET-123");
    }

    // ---------- 夹具 ----------

    private static MiddlewareUserCreateRequestDTO req(String username, String password, String role) {
        MiddlewareUserCreateRequestDTO r = new MiddlewareUserCreateRequestDTO();
        r.setInstanceId("mwi_1");
        r.setUsername(username);
        r.setPassword(password);
        r.setRole(role);
        return r;
    }

    private static PlatformMiddlewareInstance instance(String code, String tenantId, String specJson) {
        PlatformMiddlewareInstance i = new PlatformMiddlewareInstance();
        i.setId("mwi_1");
        i.setClusterId("c1");
        i.setNamespace("demo");
        i.setName("mw-" + code + "-abcdefgh");
        i.setCode(code);
        i.setTenantId(tenantId);
        i.setSpecJson(specJson);
        return i;
    }
}
