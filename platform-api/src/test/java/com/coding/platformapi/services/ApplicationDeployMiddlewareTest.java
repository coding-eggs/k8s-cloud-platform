package com.coding.platformapi.services;

import com.coding.common.models.app.AppRenderRequestDTO;
import com.coding.common.models.app.AppRenderResultDTO;
import com.coding.common.models.middleware.MiddlewareConnectionDTO;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.mapper.system.PlatformApplicationOperationMapper;
import com.coding.data.mapper.system.PlatformApplicationResourceMapper;
import com.coding.data.mapper.system.PlatformApplicationRevisionMapper;
import com.coding.data.models.system.PlatformApplication;
import com.coding.data.models.system.PlatformApplicationOperation;
import com.coding.data.models.system.PlatformApplicationRevision;
import com.coding.platformapi.k8s.K8sAppClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 中间件阶段解析连接信息，渲染阶段带上注入变量，resolvedJson 存档。 */
class ApplicationDeployMiddlewareTest {

    @Test
    void middlewareStageResolvesConnectionsAndRendersWithInjection() {
        PlatformApplicationMapper apps = mock(PlatformApplicationMapper.class);
        PlatformApplicationRevisionMapper revisions = mock(PlatformApplicationRevisionMapper.class);
        PlatformApplicationOperationMapper operations = mock(PlatformApplicationOperationMapper.class);
        PlatformApplicationResourceMapper resources = mock(PlatformApplicationResourceMapper.class);
        K8sAppClient client = mock(K8sAppClient.class);
        ApplicationMiddlewareService middleware = mock(ApplicationMiddlewareService.class);
        ApplicationDeployCredentials credentials = mock(ApplicationDeployCredentials.class);
        ApplicationDeployService service = new ApplicationDeployService(apps, revisions, operations,
                resources, client, credentials, middleware);

        PlatformApplication app = new PlatformApplication();
        app.setId("app_1");
        app.setTenantId("t1");
        app.setClusterId("c1");
        app.setNamespace("demo");
        app.setSlug("demo");
        app.setCurrentRevision(0);
        when(apps.selectById("app_1")).thenReturn(app);

        PlatformApplicationRevision rev = new PlatformApplicationRevision();
        rev.setAppId("app_1");
        rev.setRevision(1);
        rev.setSpecJson("""
                {"namespace":"demo",
                 "nodes":[{"key":"backend","kind":"SERVICE","params":{"image":"demo/backend:1"}},
                          {"key":"db","kind":"MIDDLEWARE","params":{"code":"mysql","provisionMode":"CREATE"}}],
                 "edges":[{"from":"backend","to":"db"}]}
                """);
        when(revisions.selectByRevision("app_1", 1)).thenReturn(rev);

        MiddlewareConnectionDTO.ConnInfo info = MiddlewareConnectionDTO.ConnInfo
                .of("mysql", "mw-mysql.demo.svc", 3306)
                .secret("mw-mysql-secrets", "root", "root").build();
        when(middleware.ensure(any(), any(), any())).thenReturn(Map.of("db", info));

        AppRenderResultDTO rendered = new AppRenderResultDTO();
        rendered.setManifest("");
        rendered.setObjects(List.of());
        rendered.setProblems(List.of());
        when(client.render(any())).thenReturn(rendered);

        PlatformApplicationOperation op = new PlatformApplicationOperation();
        op.setId(7L);
        op.setAppId("app_1");
        op.setRevision(1);
        op.setStatus("RUNNING");
        op.setCurrentStep("MIDDLEWARE");
        op.setStartedAt(new Date());

        service.advance(op);

        ArgumentCaptor<AppRenderRequestDTO> cap = ArgumentCaptor.forClass(AppRenderRequestDTO.class);
        verify(client).render(cap.capture());
        assertThat(cap.getValue().getInjectByNode()).containsKey("backend");
        assertThat(cap.getValue().getInjectByNode().get("backend"))
                .anySatisfy(d -> assertThat(d.getName()).isEqualTo("DB_MYSQL_HOST"));
        assertThat(rev.getResolvedJson()).contains("mw-mysql.demo.svc");
        verify(middleware).ensure(any(), any(), any());
    }
}
