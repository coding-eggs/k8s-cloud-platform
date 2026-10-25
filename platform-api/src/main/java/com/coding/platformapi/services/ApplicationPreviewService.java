package com.coding.platformapi.services;

import com.coding.common.models.app.AppObjectRefDTO;
import com.coding.common.models.app.AppRenderRequestDTO;
import com.coding.common.models.app.AppRenderResultDTO;
import com.coding.data.mapper.system.PlatformApplicationResourceMapper;
import com.coding.data.models.system.PlatformApplicationResource;
import com.coding.platformapi.k8s.K8sAppClient;
import com.coding.platformapi.models.ApplicationPreview;
import com.coding.platformapi.models.ApplicationPreviewRequest;
import com.coding.platformapi.models.ApplicationView;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 部署前预览：渲染（不写集群）+ 与上一版本的对象差异。 */
@Service
public class ApplicationPreviewService {

    private final ApplicationService apps;
    private final PlatformApplicationResourceMapper resources;
    private final K8sAppClient client;

    public ApplicationPreviewService(ApplicationService apps,
                                     PlatformApplicationResourceMapper resources,
                                     K8sAppClient client) {
        this.apps = apps;
        this.resources = resources;
        this.client = client;
    }

    /** 用草稿 spec 预览（画布上还没保存的图也能看）；previous 取最近一次部署的资源台账。 */
    public ApplicationPreview preview(ApplicationPreviewRequest req, String tenantId) {
        ApplicationView app = apps.get(req.getAppId(), tenantId);
        AppRenderRequestDTO renderReq = new AppRenderRequestDTO();
        renderReq.setClusterId(app.getClusterId());
        renderReq.setNamespace(app.getNamespace());
        renderReq.setAppId(app.getId());
        renderReq.setAppSlug(app.getSlug());
        renderReq.setRevision(app.getCurrentRevision() == null ? 1 : app.getCurrentRevision() + 1);
        renderReq.setSpec(req.getSpec() != null ? req.getSpec() : app.getSpec());

        AppRenderResultDTO rendered = client.render(renderReq);
        ApplicationPreview out = diff(previousObjects(app), rendered);
        out.setManifest(rendered.getManifest());
        out.setObjects(rendered.getObjects());
        out.setProblems(rendered.getProblems());
        return out;
    }

    /** 只比对象身份（kind/name）；字段级变化交给 SSA，预览里不重复做 diff。 */
    ApplicationPreview diff(List<AppObjectRefDTO> previous, AppRenderResultDTO rendered) {
        Set<String> before = previous.stream().map(ApplicationPreviewService::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> after = rendered.getObjects().stream().map(ApplicationPreviewService::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        ApplicationPreview p = new ApplicationPreview();
        p.setAdded(after.stream().filter(x -> !before.contains(x)).toList());
        p.setRemoved(before.stream().filter(x -> !after.contains(x)).toList());
        return p;
    }

    private List<AppObjectRefDTO> previousObjects(ApplicationView app) {
        if (app.getCurrentRevision() == null || app.getCurrentRevision() == 0) {
            return List.of();
        }
        return resources.selectByRevision(app.getId(), app.getCurrentRevision()).stream()
                .map(ApplicationPreviewService::toRef)
                .toList();
    }

    private static AppObjectRefDTO toRef(PlatformApplicationResource r) {
        AppObjectRefDTO ref = new AppObjectRefDTO();
        ref.setApiVersion(r.getApiVersion());
        ref.setKind(r.getKind());
        ref.setNamespace(r.getNamespace());
        ref.setName(r.getName());
        return ref;
    }

    private static String id(AppObjectRefDTO r) {
        return r.getKind() + "/" + r.getName();
    }
}
