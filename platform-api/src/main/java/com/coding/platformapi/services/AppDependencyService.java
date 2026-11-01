package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.middleware.MiddlewareConnectionDTO;
import com.coding.common.models.middleware.MiddlewareConnectionDTO.ConnInfo;
import com.coding.data.mapper.system.PlatformAppDependencyMapper;
import com.coding.data.models.system.PlatformAppDependency;
import com.coding.platformapi.app.AppSnapshot;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用依赖的只读视图：连接信息与就绪状态。
 *
 * <p>连接信息只从 {@code MiddlewareService.connection().getInfo()} 取，不在别处拼。
 * 添加 / 移除 / 初始化在 Task 10 加进这个类。
 */
@Service
public class AppDependencyService {

    static final String MIDDLEWARE = "MIDDLEWARE";
    static final String EXTERNAL = "EXTERNAL";

    private final PlatformAppDependencyMapper dependencies;
    private final MiddlewareService middleware;

    public AppDependencyService(PlatformAppDependencyMapper dependencies, MiddlewareService middleware) {
        this.dependencies = dependencies;
        this.middleware = middleware;
    }

    public List<PlatformAppDependency> list(String appId) {
        return dependencies.selectByApp(appId);
    }

    public Map<String, ConnInfo> connections(String appId, String tenantId) {
        return connectionsOf(list(appId), tenantId);
    }

    /** 任何一个依赖取不到连接信息都抛错并点名：静默跳过会让引用变成「不存在」这种误导的报错。 */
    public Map<String, ConnInfo> connectionsOf(List<PlatformAppDependency> deps, String tenantId) {
        Map<String, ConnInfo> out = new LinkedHashMap<>();
        for (PlatformAppDependency dep : deps) {
            ConnInfo info;
            try {
                info = EXTERNAL.equals(dep.getType()) ? externalInfo(dep) : middlewareInfo(dep, tenantId);
            } catch (CloudPlatformException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "依赖 " + dep.getDepKey() + " 的连接信息不可用：" + e.getMessage());
            }
            if (info != null) {
                out.put(dep.getDepKey(), info);
            }
        }
        return out;
    }

    /** 依赖 key → 状态（READY / 中间件状态 / EXTERNAL / UNKNOWN）。 */
    public Map<String, String> statusOf(List<PlatformAppDependency> deps) {
        Map<String, String> out = new LinkedHashMap<>();
        for (PlatformAppDependency dep : deps) {
            out.put(dep.getDepKey(), statusOf(dep));
        }
        return out;
    }

    /** 未就绪的依赖，每条一句话且含依赖名；空列表 = 全部就绪。EXTERNAL 不探测，视为就绪。 */
    public List<String> notReady(List<PlatformAppDependency> deps) {
        List<String> out = new ArrayList<>();
        for (PlatformAppDependency dep : deps) {
            if (EXTERNAL.equals(dep.getType())) {
                continue;
            }
            String status = statusOf(dep);
            if (!"READY".equals(status)) {
                out.add("依赖 " + dep.getDepKey() + " 未就绪（当前状态 " + status + "）");
            }
        }
        return out;
    }

    private String statusOf(PlatformAppDependency dep) {
        if (EXTERNAL.equals(dep.getType())) {
            return "EXTERNAL";
        }
        if (dep.getInstanceId() == null || dep.getInstanceId().isBlank()) {
            return "NO_INSTANCE";
        }
        try {
            String s = middleware.statusOf(dep.getInstanceId());
            return s == null ? "UNKNOWN" : s;
        } catch (RuntimeException e) {
            return "UNKNOWN";
        }
    }

    private ConnInfo middlewareInfo(PlatformAppDependency dep, String tenantId) {
        if (dep.getInstanceId() == null || dep.getInstanceId().isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "依赖 " + dep.getDepKey() + " 还没有关联的中间件实例");
        }
        MiddlewareConnectionDTO dto = middleware.connection(dep.getInstanceId(), tenantId);
        return dto == null ? null : dto.getInfo();
    }

    private ConnInfo externalInfo(PlatformAppDependency dep) {
        if (dep.getExternalJson() == null || dep.getExternalJson().isBlank()) {
            return null;
        }
        JsonNode n = AppSnapshot.JSON.readTree(dep.getExternalJson());
        ConnInfo info = new ConnInfo();
        info.setProtocol(text(n, "protocol"));
        info.setHost(text(n, "host"));
        info.setPort(n.hasNonNull("port") ? n.get("port").asInt() : null);
        info.setSecretName(text(n, "secretName"));
        info.setUsernameKey(text(n, "usernameKey"));
        info.setPasswordKey(text(n, "passwordKey"));
        return info;
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) && !n.get(field).asString().isBlank() ? n.get(field).asString() : null;
    }
}
