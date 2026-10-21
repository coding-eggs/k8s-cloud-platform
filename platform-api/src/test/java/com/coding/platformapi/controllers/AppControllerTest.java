package com.coding.platformapi.controllers;

import com.coding.data.models.system.PlatformApp;
import com.coding.platformapi.models.AppQueryRequest;
import com.coding.platformapi.services.AppInstallService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppControllerTest {

    @Test
    void listReturnsAppsFromService() {
        AppInstallService service = mock(AppInstallService.class);
        PlatformApp app = new PlatformApp();
        app.setId("app_1");
        when(service.list("c1", "demo")).thenReturn(List.of(app));

        List<PlatformApp> out = new AppController(service).list(query()).getData();

        assertThat(out).extracting(PlatformApp::getId).containsExactly("app_1");
    }

    private static AppQueryRequest query() {
        AppQueryRequest r = new AppQueryRequest();
        r.setClusterId("c1");
        r.setNamespace("demo");
        return r;
    }
}
