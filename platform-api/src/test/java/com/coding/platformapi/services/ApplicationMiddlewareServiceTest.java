package com.coding.platformapi.services;

import com.coding.common.models.app.AppNodeDTO;
import com.coding.common.models.app.AppSpecDTO;
import com.coding.common.models.middleware.MiddlewareConnectionDTO;
import com.coding.data.mapper.system.PlatformMiddlewareInstanceMapper;
import com.coding.data.models.system.PlatformApplication;
import com.coding.data.models.system.PlatformMiddlewareInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplicationMiddlewareServiceTest {

    private MiddlewareService middleware;
    private PlatformMiddlewareInstanceMapper instances;
    private ApplicationMiddlewareService service;

    @BeforeEach
    void setUp() {
        middleware = mock(MiddlewareService.class);
        instances = mock(PlatformMiddlewareInstanceMapper.class);
        service = new ApplicationMiddlewareService(middleware, instances);
    }

    private static PlatformApplication app() {
        PlatformApplication a = new PlatformApplication();
        a.setId("app_1");
        a.setTenantId("t1");
        a.setClusterId("c1");
        a.setNamespace("demo");
        a.setSlug("demo");
        return a;
    }

    private static AppNodeDTO mw(String key, Map<String, Object> params) {
        AppNodeDTO n = new AppNodeDTO();
        n.setKey(key);
        n.setKind("MIDDLEWARE");
        n.setParams(params);
        return n;
    }

    @Test
    void bindRejectsInstanceFromAnotherNamespace() {
        PlatformMiddlewareInstance foreign = new PlatformMiddlewareInstance();
        foreign.setId("mwi_1");
        foreign.setClusterId("c1");
        foreign.setNamespace("other-team");
        foreign.setTenantId("t1");
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(foreign);

        AppSpecDTO spec = new AppSpecDTO();
        spec.setNodes(List.of(mw("db", Map.of("code", "mysql", "provisionMode", "BIND", "instanceId", "mwi_1"))));

        assertThatThrownBy(() -> service.ensure(spec, app(), "admin"))
                .hasMessageContaining("同一个命名空间");
        verify(middleware, never()).connection(anyString(), any());
    }

    @Test
    void bindRejectsAnotherTenantsInstance() {
        PlatformMiddlewareInstance foreign = new PlatformMiddlewareInstance();
        foreign.setId("mwi_2");
        foreign.setClusterId("c1");
        foreign.setNamespace("demo");
        foreign.setTenantId("t2");
        when(instances.selectByPrimaryKey("mwi_2")).thenReturn(foreign);

        AppSpecDTO spec = new AppSpecDTO();
        spec.setNodes(List.of(mw("db", Map.of("code", "mysql", "provisionMode", "BIND", "instanceId", "mwi_2"))));

        assertThatThrownBy(() -> service.ensure(spec, app(), "admin"))
                .hasMessageContaining("别的租户");
    }

    @Test
    void externalUsesNodeParamsWithoutCreatingAnything() {
        AppSpecDTO spec = new AppSpecDTO();
        spec.setNodes(List.of(mw("db", Map.of(
                "code", "mysql", "provisionMode", "EXTERNAL",
                "host", "mysql.corp.internal", "port", 3306,
                "secretName", "corp-db", "usernameKey", "user", "passwordKey", "password"))));

        Map<String, MiddlewareConnectionDTO.ConnInfo> out = service.ensure(spec, app(), "admin");

        assertThat(out.get("db").getHost()).isEqualTo("mysql.corp.internal");
        assertThat(out.get("db").getPort()).isEqualTo(3306);
        assertThat(out.get("db").getSecretName()).isEqualTo("corp-db");
        verify(middleware, never()).create(any(), anyString(), anyString());
    }

    @Test
    void createCallsMiddlewareStoreAndReturnsConnection() {
        when(middleware.create(any(), anyString(), anyString())).thenReturn(11L);
        PlatformMiddlewareInstance created = new PlatformMiddlewareInstance();
        created.setId("mwi_9");
        created.setClusterId("c1");
        created.setNamespace("demo");
        created.setTenantId("t1");
        created.setCode("mysql");
        created.setName("mw-mysql-1234abcd");
        created.setStatus("READY");
        when(instances.selectLatestByAppNode("app_1", "db")).thenReturn(created);
        when(middleware.statusOf("mwi_9")).thenReturn("READY");
        MiddlewareConnectionDTO conn = new MiddlewareConnectionDTO();
        conn.setInfo(MiddlewareConnectionDTO.ConnInfo.of("mysql", "mw-mysql.demo.svc", 3306)
                .secret("mw-mysql-secrets", "root", "root").build());
        when(middleware.connection("mwi_9", "t1")).thenReturn(conn);

        AppSpecDTO spec = new AppSpecDTO();
        spec.setNodes(List.of(mw("db", Map.of(
                "code", "mysql", "provisionMode", "CREATE", "shape", "SINGLE"))));

        Map<String, MiddlewareConnectionDTO.ConnInfo> out = service.ensure(spec, app(), "admin");

        assertThat(out.get("db").getHost()).isEqualTo("mw-mysql.demo.svc");
        verify(middleware).create(any(), anyString(), anyString());
    }

    @Test
    void failedCreateFailsTheWholeDeployment() {
        when(middleware.create(any(), anyString(), anyString())).thenReturn(11L);
        PlatformMiddlewareInstance created = new PlatformMiddlewareInstance();
        created.setId("mwi_9");
        created.setName("mw-mysql-1234abcd");
        created.setStatus("FAILED");
        when(instances.selectLatestByAppNode("app_1", "db")).thenReturn(created);
        when(middleware.statusOf("mwi_9")).thenReturn("FAILED");

        AppSpecDTO spec = new AppSpecDTO();
        spec.setNodes(List.of(mw("db", Map.of("code", "mysql", "provisionMode", "CREATE"))));

        assertThatThrownBy(() -> service.ensure(spec, app(), "admin"))
                .hasMessageContaining("中间件创建失败");
    }
}
