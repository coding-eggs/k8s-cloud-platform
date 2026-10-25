package com.coding.platformapi.services;

import com.coding.common.models.app.AppObjectRefDTO;
import com.coding.common.models.app.AppProblemDTO;
import com.coding.common.models.app.AppSpecDTO;
import com.coding.platformapi.app.AppSpecValidator;
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
        out.setProblems(withGraphProblems(renderReq.getSpec(), app.getNamespace(), rendered.getProblems()));
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

    /** 预览也要报图本身的问题（循环依赖/非法 key），否则用户要等「保存」才知道。 */
    private List<AppProblemDTO> withGraphProblems(AppSpecDTO spec, String namespace,
                                                 List<AppProblemDTO> rendered) {
        List<AppProblemDTO> out = new java.util.ArrayList<>(AppSpecValidator.validateDraft(spec));
        if (spec != null && spec.getNamespace() != null && !spec.getNamespace().isBlank()
                && !spec.getNamespace().equals(namespace)) {
            out.add(AppProblemDTO.error(null, "应用不能换命名空间：" + spec.getNamespace()));
        }
        if (rendered != null) {
            out.addAll(rendered);
        }
        return out;
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
