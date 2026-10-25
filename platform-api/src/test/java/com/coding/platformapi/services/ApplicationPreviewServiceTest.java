package com.coding.platformapi.services;

import com.coding.common.models.app.AppObjectRefDTO;
import com.coding.common.models.app.AppRenderResultDTO;
import com.coding.platformapi.k8s.K8sAppClient;
import com.coding.platformapi.models.ApplicationPreview;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationPreviewServiceTest {

    private static AppObjectRefDTO ref(String kind, String name) {
        AppObjectRefDTO r = new AppObjectRefDTO();
        r.setApiVersion("v1");
        r.setKind(kind);
        r.setNamespace("demo");
        r.setName(name);
        return r;
    }

    @Test
    void reportsAddedAndRemovedObjects() {
        AppRenderResultDTO rendered = new AppRenderResultDTO();
        rendered.setManifest("kind: Deployment\n");
        rendered.setObjects(List.of(ref("Deployment", "demo-web"), ref("Service", "demo-web")));
        rendered.setProblems(List.of());

        ApplicationPreviewService svc = new ApplicationPreviewService(null, null, mockClient(rendered));
        ApplicationPreview p = svc.diff(List.of(ref("Deployment", "demo-web"), ref("Service", "demo-old")),
                rendered);

        assertThat(p.getAdded()).containsExactly("Service/demo-web");
        assertThat(p.getRemoved()).containsExactly("Service/demo-old");
    }

    @Test
    void firstPreviewTreatsEveryObjectAsAdded() {
        AppRenderResultDTO rendered = new AppRenderResultDTO();
        rendered.setManifest("");
        rendered.setObjects(List.of(ref("Deployment", "demo-web")));
        rendered.setProblems(List.of());

        ApplicationPreview p = new ApplicationPreviewService(null, null, mockClient(rendered))
                .diff(List.of(), rendered);

        assertThat(p.getAdded()).containsExactly("Deployment/demo-web");
        assertThat(p.getRemoved()).isEmpty();
    }

    private static K8sAppClient mockClient(AppRenderResultDTO rendered) {
        return new K8sAppClient(null) {
            @Override
            public AppRenderResultDTO render(com.coding.common.models.app.AppRenderRequestDTO body) {
                return rendered;
            }
        };
    }
}
