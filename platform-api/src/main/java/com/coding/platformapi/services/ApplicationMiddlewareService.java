package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.app.AppNodeDTO;
import com.coding.common.models.app.AppSpecDTO;
import com.coding.common.models.middleware.MiddlewareConnectionDTO;
import com.coding.data.mapper.system.PlatformMiddlewareInstanceMapper;
import com.coding.data.models.system.PlatformApplication;
import com.coding.data.models.system.PlatformMiddlewareInstance;
import com.coding.platformapi.models.MiddlewareCreateRequest;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中间件节点的三种来源（平台创建 / 绑定已有实例 / 链接外部）统一产出「连接信息」。
 *
 * <p>只用这里产出的 {@link MiddlewareConnectionDTO.ConnInfo} 做注入 —— 在别处再拼一遍
 * Service 名/端口/Secret 键，两处一漂移就会注入到不存在的地址。
 */
@Service
public class ApplicationMiddlewareService {

    /** CREATE 时等实例就绪的上限：中间件拉起通常几分钟，给足余量。 */
    private static final long READY_TIMEOUT_MS = 15 * 60 * 1000;

    private static final long POLL_INTERVAL_MS = 3000;

    private final MiddlewareService middleware;
    private final PlatformMiddlewareInstanceMapper instances;

    public ApplicationMiddlewareService(MiddlewareService middleware,
                                        PlatformMiddlewareInstanceMapper instances) {
        this.middleware = middleware;
        this.instances = instances;
    }

    /** 对每个 MIDDLEWARE 节点给出连接信息；CREATE 会就地创建并等 Ready。 */
    public Map<String, MiddlewareConnectionDTO.ConnInfo> ensure(AppSpecDTO spec,
                                                                PlatformApplication app,
                                                                String operator) {
        Map<String, MiddlewareConnectionDTO.ConnInfo> out = new LinkedHashMap<>();
        for (AppNodeDTO node : spec.getNodes() == null ? List.<AppNodeDTO>of() : spec.getNodes()) {
            if (!"MIDDLEWARE".equals(node.getKind())) {
                continue;
            }
            Map<String, Object> params = node.getParams() == null ? Map.of() : node.getParams();
            String mode = str(params.get("provisionMode"));
            MiddlewareConnectionDTO.ConnInfo info = switch (mode) {
                case "CREATE" -> create(node, params, app, operator);
                case "BIND" -> bind(params, app);
                case "EXTERNAL" -> external(params, node.getKey());
                default -> throw new CloudPlatformException(EnumResponseType.ERROR,
                        "不支持的来源方式：" + mode + "（节点 " + node.getKey() + "）");
            };
            if (info == null) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "该中间件暂不提供连接信息：" + str(params.get("code")));
            }
            out.put(node.getKey(), info);
        }
        return out;
    }

    private MiddlewareConnectionDTO.ConnInfo create(AppNodeDTO node, Map<String, Object> params,
                                                    PlatformApplication app, String operator) {
        String code = str(params.get("code"));
        MiddlewareCreateRequest req = new MiddlewareCreateRequest();
        req.setClusterId(app.getClusterId());
        req.setNamespace(app.getNamespace());
        req.setCode(code);
        req.setMode(strOrDefault(params.get("shape"), middleware.defaultMode(code)));
        req.setDisplayName("应用 " + app.getSlug() + " · " + node.getKey());
        req.setCpuRequest(strOrNull(params.get("cpuRequest")));
        req.setCpuLimit(strOrNull(params.get("cpuLimit")));
        req.setMemoryRequest(strOrNull(params.get("memoryRequest")));
        req.setMemoryLimit(strOrNull(params.get("memoryLimit")));
        req.setStorageSize(strOrNull(params.get("storageSize")));
        req.setStorageClass(strOrNull(params.get("storageClass")));
        req.setMetricsEnabled(Boolean.TRUE.equals(params.get("metricsEnabled")));
        // 实例归属记到「应用 + 节点」上：实例页要能标注来源，重试时也能找回
        req.setAppId(app.getId());
        req.setAppNodeId(node.getKey());

        middleware.create(req, operator, app.getTenantId());

        PlatformMiddlewareInstance created = instances.selectLatestByAppNode(app.getId(), node.getKey());
        if (created == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "中间件实例创建后没有台账记录（节点 " + node.getKey() + "）");
        }
        awaitReady(created);
        return middleware.connection(created.getId(), app.getTenantId()).getInfo();
    }

    private MiddlewareConnectionDTO.ConnInfo bind(Map<String, Object> params, PlatformApplication app) {
        PlatformMiddlewareInstance existing = instances.selectByPrimaryKey(str(params.get("instanceId")));
        if (existing == null || existing.getDeletedAt() != null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "要绑定的中间件实例不存在");
        }
        if (!app.getClusterId().equals(existing.getClusterId())
                || !app.getNamespace().equals(existing.getNamespace())) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "只能绑定同一个命名空间里的实例：" + existing.getNamespace());
        }
        if (!app.getTenantId().equals(existing.getTenantId())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "不能绑定别的租户的实例");
        }
        return middleware.connection(existing.getId(), app.getTenantId()).getInfo();
    }

    private MiddlewareConnectionDTO.ConnInfo external(Map<String, Object> params, String nodeKey) {
        String code = str(params.get("code"));
        String host = str(params.get("host"));
        if (host.isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "外部中间件没有填写地址（节点 " + nodeKey + "）");
        }
        return MiddlewareConnectionDTO.ConnInfo
                .of(code, host, params.get("port") instanceof Number n ? n.intValue() : null)
                .secret(strOrNull(params.get("secretName")),
                        strOrNull(params.get("usernameKey")),
                        strOrNull(params.get("passwordKey")))
                .build();
    }

    /** 轮询到 READY；FAILED/DELETED 立刻失败，超时也失败（不允许把半成品当就绪）。 */
    private void awaitReady(PlatformMiddlewareInstance instance) {
        long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            String status = middleware.statusOf(instance.getId());
            if ("READY".equals(status)) {
                return;
            }
            if ("FAILED".equals(status) || "DELETED".equals(status)) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "中间件创建失败：" + instance.getDisplayName());
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CloudPlatformException(EnumResponseType.ERROR, "等待中间件就绪被中断");
            }
        }
        throw new CloudPlatformException(EnumResponseType.ERROR,
                "等待中间件就绪超时：" + instance.getDisplayName());
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String strOrNull(Object o) {
        String s = str(o);
        return s.isBlank() ? null : s;
    }

    private static String strOrDefault(Object o, String def) {
        String s = str(o);
        return s.isBlank() ? def : s;
    }
}
