package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.app.AppApplyRequestDTO;
import com.coding.common.models.app.AppObjectRefDTO;
import com.coding.common.models.app.AppObjectStatusDTO;
import com.coding.common.models.app.AppPruneRequestDTO;
import com.coding.common.models.app.AppRenderRequestDTO;
import com.coding.common.models.app.AppRenderResultDTO;
import com.coding.common.models.app.AppSpecDTO;
import com.coding.common.models.app.AppStatusRequestDTO;
import com.coding.common.models.app.AppStatusResultDTO;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.mapper.system.PlatformApplicationOperationMapper;
import com.coding.data.mapper.system.PlatformApplicationResourceMapper;
import com.coding.data.mapper.system.PlatformApplicationRevisionMapper;
import com.coding.data.models.system.PlatformApplication;
import com.coding.data.models.system.PlatformApplicationOperation;
import com.coding.data.models.system.PlatformApplicationResource;
import com.coding.data.models.system.PlatformApplicationRevision;
import com.coding.platformapi.k8s.K8sAppClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 部署的阶段机。每一步成功后把 {@code current_step} 落库，异常 → FAILED；
 * worker 下一次节拍从落库的那一步继续，所以 platform-api 重启不会把操作永远留在 RUNNING。
 */
@Service
public class ApplicationDeployService {

    /** 阶段顺序；最后一步之后进入 DONE（由 finish 收尾）。 */
    static final List<String> STEPS =
            List.of("PRECHECK", "RENDER", "CONFLICT", "APPLY", "PRUNE", "HEALTH");

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final JavaType OBJECT_LIST =
            JSON.getTypeFactory().constructCollectionType(List.class, AppObjectRefDTO.class);

    /** 整个部署操作的超时：超过仍未就绪就判失败，避免 worker 无限重试。 */
    @Value("${platform.application.timeout-minutes:30}")
    private long timeoutMinutes = 30;

    private final PlatformApplicationMapper apps;
    private final PlatformApplicationRevisionMapper revisions;
    private final PlatformApplicationOperationMapper operations;
    private final PlatformApplicationResourceMapper resources;
    private final K8sAppClient client;

    public ApplicationDeployService(PlatformApplicationMapper apps,
                                    PlatformApplicationRevisionMapper revisions,
                                    PlatformApplicationOperationMapper operations,
                                    PlatformApplicationResourceMapper resources,
                                    K8sAppClient client) {
        this.apps = apps;
        this.revisions = revisions;
        this.operations = operations;
        this.resources = resources;
        this.client = client;
    }

    public Long start(String appId, Integer revision, String operator) {
        PlatformApplication app = requireApp(appId);
        PlatformApplicationOperation active = operations.selectActiveByApp(appId);
        if (active != null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "该应用已有进行中的部署，请等它结束或先重试");
        }
        PlatformApplicationRevision rev;
        if (revision != null) {
            rev = revisions.selectByRevision(appId, revision);
            if (rev == null) {
                throw new CloudPlatformException(EnumResponseType.ERROR, "找不到版本：" + revision);
            }
        } else {
            rev = newRevision(app, nextRevision(appId), operator);
        }

        PlatformApplicationOperation op = new PlatformApplicationOperation();
        op.setAppId(appId);
        op.setRevision(rev.getRevision());
        op.setAction(revision != null ? "RETRY"
                : (Integer.valueOf(0).equals(app.getCurrentRevision()) ? "DEPLOY" : "UPGRADE"));
        op.setStatus("PENDING");
        op.setCurrentStep("PRECHECK");
        op.setNodeStatesJson("{}");
        op.setStartedAt(new Date());
        op.setCreatedBy(operator);
        operations.insert(op);

        app.setStatus("DEPLOYING");
        app.setUpdatedAt(new Date());
        apps.updateById(app);
        return op.getId();
    }

    /** 幂等推进：每一步成功后写 current_step；异常 → FAILED（人工重试从失败步继续）。 */
    public void advance(PlatformApplicationOperation op) {
        try {
            op.setStatus("RUNNING");
            operations.updateById(op);
            String step = op.getCurrentStep();
            while (true) {
                step = switch (step) {
                    case "PRECHECK" -> precheck(op);
                    case "RENDER" -> render(op);
                    case "CONFLICT" -> conflict(op);
                    case "APPLY" -> apply(op);
                    case "PRUNE" -> prune(op);
                    case "HEALTH" -> health(op);
                    default -> "DONE";
                };
                if ("DONE".equals(step)) {
                    finish(op, "SUCCESS", null);
                    return;
                }
                op.setCurrentStep(step);
                operations.updateById(op);
            }
        } catch (DeploymentPending pending) {
            // 还在等资源就绪：保持 RUNNING，让 worker 下一拍再进一次 HEALTH（不算失败）
        } catch (RuntimeException e) {
            finish(op, "FAILED", e.getMessage());
        }
    }

    public PlatformApplicationOperation operation(Long operationId) {
        PlatformApplicationOperation op = operations.selectById(operationId);
        if (op == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "操作记录不存在");
        }
        return op;
    }

    private String precheck(PlatformApplicationOperation op) {
        PlatformApplication app = requireApp(op.getAppId());
        if (app.getNamespace() == null || app.getNamespace().isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "应用没有命名空间");
        }
        if (revisions.selectByRevision(op.getAppId(), op.getRevision()) == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "版本不存在：" + op.getRevision());
        }
        return "RENDER";
    }

    private String render(PlatformApplicationOperation op) {
        PlatformApplicationRevision rev = revisions.selectByRevision(op.getAppId(), op.getRevision());
        if (rev.getManifest() != null && !rev.getManifest().isBlank()) {
            return "CONFLICT";   // 续跑：清单已渲染过，直接进冲突检查
        }
        PlatformApplication app = requireApp(op.getAppId());
        AppRenderRequestDTO req = new AppRenderRequestDTO();
        req.setClusterId(app.getClusterId());
        req.setNamespace(app.getNamespace());
        req.setAppId(app.getId());
        req.setAppSlug(app.getSlug());
        req.setRevision(rev.getRevision());
        req.setSpec(readSpec(rev.getSpecJson()));

        AppRenderResultDTO rendered = client.render(req);
        String error = rendered.getProblems() == null ? null : rendered.getProblems().stream()
                .filter(p -> "ERROR".equals(p.getSeverity()))
                .map(com.coding.common.models.app.AppProblemDTO::getMessage)
                .findFirst().orElse(null);
        if (error != null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, error);
        }
        rev.setManifest(rendered.getManifest());
        rev.setObjectsJson(JSON.writeValueAsString(rendered.getObjects()));
        rev.setStatus("RENDERED");
        revisions.updateById(rev);
        return "CONFLICT";
    }

    /**
     * 冲突检查：要写的对象在集群里已存在、但不在本应用的历史台账里 → 拒绝。
     *
     * <p>为什么必须拦：SSA 会把「别人的手工对象」直接接管（fieldManager 一改就成了我们的），
     * 用户收不到任何提示，而下次 prune 还可能把它删掉。
     */
    private String conflict(PlatformApplicationOperation op) {
        PlatformApplication app = requireApp(op.getAppId());
        PlatformApplicationRevision rev = revisions.selectByRevision(op.getAppId(), op.getRevision());
        AppStatusRequestDTO req = new AppStatusRequestDTO();
        req.setClusterId(app.getClusterId());
        req.setObjects(objectsOf(rev.getObjectsJson()));
        AppStatusResultDTO st = client.status(req);
        Set<String> ours = resources.selectByApp(op.getAppId()).stream()
                .map(r -> r.getKind() + "/" + r.getName())
                .collect(Collectors.toSet());
        if (st != null && st.getObjects() != null) {
            for (AppObjectStatusDTO s : st.getObjects()) {
                String id = s.getKind() + "/" + s.getName();
                if (!"MISSING".equals(s.getState()) && !"UNKNOWN".equals(s.getState())
                        && !ours.contains(id)) {
                    throw new CloudPlatformException(EnumResponseType.ERROR,
                            "对象已存在且不属于本应用：" + id + "（请改名或先手工处理）");
                }
            }
        }
        return "APPLY";
    }

    private String apply(PlatformApplicationOperation op) {
        PlatformApplication app = requireApp(op.getAppId());
        PlatformApplicationRevision rev = revisions.selectByRevision(op.getAppId(), op.getRevision());
        AppApplyRequestDTO req = new AppApplyRequestDTO();
        req.setClusterId(app.getClusterId());
        req.setManifest(rev.getManifest());
        client.apply(req);

        // 先删本 revision 旧记录再写，保证重试幂等
        resources.deleteByAppRevision(op.getAppId(), op.getRevision());
        List<PlatformApplicationResource> rows = objectsOf(rev.getObjectsJson()).stream()
                .map(ref -> toResource(op, ref))
                .toList();
        if (!rows.isEmpty()) {
            resources.batchInsert(rows);
        }
        return "PRUNE";
    }

    private String prune(PlatformApplicationOperation op) {
        PlatformApplication app = requireApp(op.getAppId());
        int previous = op.getRevision() - 1;
        if (previous < 1) {
            return "HEALTH";
        }
        Set<String> current = objectsOf(objectsJsonOf(op)).stream()
                .map(r -> r.getKind() + "/" + r.getName())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<AppObjectRefDTO> stale = resources.selectByRevision(op.getAppId(), previous).stream()
                .map(ApplicationDeployService::toRef)
                .filter(r -> !current.contains(r.getKind() + "/" + r.getName()))
                .toList();
        if (!stale.isEmpty()) {
            AppPruneRequestDTO req = new AppPruneRequestDTO();
            req.setClusterId(app.getClusterId());
            req.setObjects(stale);
            req.setDeletePvc(false);
            client.prune(req);
        }
        return "HEALTH";
    }

    private String health(PlatformApplicationOperation op) {
        PlatformApplication app = requireApp(op.getAppId());
        AppStatusRequestDTO req = new AppStatusRequestDTO();
        req.setClusterId(app.getClusterId());
        req.setObjects(objectsOf(objectsJsonOf(op)));
        AppStatusResultDTO status = client.status(req);

        String prefix = app.getSlug() + "-";
        Map<String, String> nodeStates = new LinkedHashMap<>();
        boolean allReady = true;
        if (status != null && status.getObjects() != null) {
            for (AppObjectStatusDTO s : status.getObjects()) {
                String key = nodeKeyOf(s.getName(), prefix);
                boolean ready = "READY".equals(s.getState());
                allReady &= ready;
                nodeStates.merge(key, ready ? "READY" : s.getState(),
                        (a, b) -> "READY".equals(a) && "READY".equals(b) ? "READY"
                                : ("MISSING".equals(a) || "MISSING".equals(b) ? "MISSING" : "PENDING"));
            }
        }
        // 先落库：画布在等待期间要能看见每个节点的灯
        op.setNodeStatesJson(JSON.writeValueAsString(nodeStates));
        operations.updateById(op);

        if (!allReady) {
            long minutes = op.getStartedAt() == null ? 0
                    : Duration.between(op.getStartedAt().toInstant(), Instant.now()).toMinutes();
            if (minutes > timeoutMinutes) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "部署超时（" + timeoutMinutes + " 分钟）：仍有节点未就绪");
            }
            throw new DeploymentPending();
        }
        return "DONE";
    }

    /** 对象名 = &lt;slug&gt;-&lt;nodeKey&gt;；headless Service 与 env ConfigMap 归到同一节点。 */
    static String nodeKeyOf(String objectName, String slugPrefix) {
        String key = objectName != null && objectName.startsWith(slugPrefix)
                ? objectName.substring(slugPrefix.length()) : objectName;
        if (key != null && key.endsWith("-headless")) {
            key = key.substring(0, key.length() - "-headless".length());
        }
        if (key != null && (key.endsWith("-env") || key.endsWith("-secret"))) {
            key = key.substring(0, key.lastIndexOf('-'));
        }
        return key;
    }

    private String objectsJsonOf(PlatformApplicationOperation op) {
        PlatformApplicationRevision rev = revisions.selectByRevision(op.getAppId(), op.getRevision());
        return rev == null ? null : rev.getObjectsJson();
    }

    private PlatformApplication requireApp(String appId) {
        PlatformApplication app = apps.selectById(appId);
        if (app == null || app.getDeletedAt() != null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "应用不存在或已删除");
        }
        return app;
    }

    private int nextRevision(String appId) {
        Integer max = revisions.selectMaxRevision(appId);
        return max == null ? 1 : max + 1;
    }

    private PlatformApplicationRevision newRevision(PlatformApplication app, int revision, String operator) {
        PlatformApplicationRevision rev = new PlatformApplicationRevision();
        rev.setAppId(app.getId());
        rev.setRevision(revision);
        rev.setSpecJson(app.getSpecJson());
        rev.setStatus("RENDERED");
        rev.setCreatedBy(operator);
        rev.setCreatedAt(new Date());
        revisions.insert(rev);
        return rev;
    }

    private void finish(PlatformApplicationOperation op, String status, String message) {
        op.setStatus(status);
        op.setMessage(message);
        op.setFinishedAt(new Date());
        operations.updateById(op);
        PlatformApplication app = apps.selectById(op.getAppId());
        if (app == null) {
            return;
        }
        app.setStatus("FAILED".equals(status) ? "FAILED" : "READY");
        if ("SUCCESS".equals(status)) {
            app.setCurrentRevision(op.getRevision());
        }
        app.setUpdatedAt(new Date());
        apps.updateById(app);
    }

    private static AppSpecDTO readSpec(String json) {
        return JSON.readValue(json, AppSpecDTO.class);
    }

    /** 版本里存的对象清单（JSON）；渲染阶段由 k8s-server 返回，platform-api 不解析 YAML。 */
    static List<AppObjectRefDTO> objectsOf(String objectsJson) {
        if (objectsJson == null || objectsJson.isBlank()) {
            return List.of();
        }
        return JSON.readValue(objectsJson, OBJECT_LIST);
    }

    private static PlatformApplicationResource toResource(PlatformApplicationOperation op, AppObjectRefDTO ref) {
        PlatformApplicationResource r = new PlatformApplicationResource();
        r.setAppId(op.getAppId());
        r.setRevision(op.getRevision());
        r.setApiVersion(ref.getApiVersion());
        r.setKind(ref.getKind());
        r.setNamespace(ref.getNamespace());
        r.setName(ref.getName());
        r.setCreatedAt(new Date());
        return r;
    }

    private static AppObjectRefDTO toRef(PlatformApplicationResource r) {
        AppObjectRefDTO ref = new AppObjectRefDTO();
        ref.setApiVersion(r.getApiVersion());
        ref.setKind(r.getKind());
        ref.setNamespace(r.getNamespace());
        ref.setName(r.getName());
        return ref;
    }

    /** 控制流信号：本轮还没就绪，保持 RUNNING（不是错误，不留栈）。 */
    private static final class DeploymentPending extends RuntimeException {
        DeploymentPending() {
            super(null, null, false, false);
        }
    }
}
