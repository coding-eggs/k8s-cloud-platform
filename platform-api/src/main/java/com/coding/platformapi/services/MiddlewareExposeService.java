package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.IngressListenerSetStatusDTO;
import com.coding.common.models.middleware.MiddlewareExposeRequestDTO;
import com.coding.data.mapper.system.PlatformMiddlewareExposeMapper;
import com.coding.data.mapper.system.PlatformMiddlewareInstanceMapper;
import com.coding.data.models.system.PlatformIngressPort;
import com.coding.data.models.system.PlatformMiddlewareExpose;
import com.coding.data.models.system.PlatformMiddlewareInstance;
import com.coding.platformapi.app.AppServiceFeatures;
import com.coding.platformapi.k8s.K8sAppClient;
import com.coding.platformapi.k8s.K8sMiddlewareClient;
import com.coding.platformapi.models.MiddlewareExposeView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 中间件对外暴露（spec 2026-11-09）：改了立即生效（中间件没有「发布」）。
 *
 * <ul>
 *   <li>网关：从应用端口池分配（owner_kind=MIDDLEWARE，appId=实例 id、serviceKey=端点 key）→ 重算命名空间
 *       ListenerSet → 下发路由。ListenerSet 下发失败时把这次新分到的端口放掉；</li>
 *   <li>NodePort：平台分配（与应用共用避让集合），存表后不变；</li>
 *   <li>离开网关：先下发新对象（删掉路由），ListenerSet 重算成功后才释放端口（同应用 I4）。</li>
 * </ul>
 */
@Slf4j
@Service
public class MiddlewareExposeService {

    static final String NONE = "NONE";

    private final PlatformMiddlewareInstanceMapper instances;
    private final PlatformMiddlewareExposeMapper rows;
    private final IngressPortService ports;
    private final IngressListenerSync listeners;
    private final AppNodePortAllocator nodePorts;
    private final K8sMiddlewareClient client;
    private final K8sAppClient appClient;
    private final ComponentFeatureGuard guard;

    public MiddlewareExposeService(PlatformMiddlewareInstanceMapper instances, PlatformMiddlewareExposeMapper rows,
                                   IngressPortService ports, IngressListenerSync listeners,
                                   AppNodePortAllocator nodePorts, K8sMiddlewareClient client,
                                   K8sAppClient appClient, ComponentFeatureGuard guard) {
        this.instances = instances;
        this.rows = rows;
        this.ports = ports;
        this.listeners = listeners;
        this.nodePorts = nodePorts;
        this.client = client;
        this.appClient = appClient;
        this.guard = guard;
    }

    public MiddlewareExposeView list(String instanceId, String tenantId) {
        PlatformMiddlewareInstance instance = require(instanceId, tenantId);
        MiddlewareExposeCatalog.Result catalog = MiddlewareExposeCatalog.of(instance);
        boolean unauthenticated = MiddlewareExposeCatalog.unauthenticated(instance);
        if (catalog.unsupportedReason() != null) {
            return new MiddlewareExposeView(catalog.unsupportedReason(), null, unauthenticated, List.of());
        }
        Map<String, PlatformMiddlewareExpose> byKey = byKey(instance.getId());
        Map<String, IngressListenerSetStatusDTO.Listener> listenerStatus =
                byKey.values().stream().anyMatch(r -> PlatformMiddlewareExpose.GATEWAY.equals(r.getMode()))
                        ? listenerStatus(instance) : Map.of();
        List<MiddlewareExposeView.Endpoint> out = new ArrayList<>();
        for (MiddlewareExposeCatalog.Endpoint ep : catalog.endpoints()) {
            PlatformMiddlewareExpose r = byKey.get(ep.key());
            String target = ep.service() + ":" + ep.port();
            if (r == null) {
                out.add(new MiddlewareExposeView.Endpoint(ep.key(), ep.label(), target, ep.protocol(), NONE,
                        null, null, null, null));
            } else if (PlatformMiddlewareExpose.NODEPORT.equals(r.getMode())) {
                out.add(new MiddlewareExposeView.Endpoint(ep.key(), ep.label(), target, ep.protocol(),
                        PlatformMiddlewareExpose.NODEPORT, r.getNodePort(), null, null, null));
            } else {
                Integer port = ports.portOf(instance.getId(), ep.key());
                var l = port == null ? null : listenerStatus.get(IngressListenerSync.sectionName(port));
                String reason = l == null ? null : (nz(l.getReason()) + " " + nz(l.getMessage())).trim();
                out.add(new MiddlewareExposeView.Endpoint(ep.key(), ep.label(), target, ep.protocol(),
                        PlatformMiddlewareExpose.GATEWAY, port,
                        l == null ? null : l.isProgrammed(), l == null ? null : l.isConflicted(),
                        reason == null || reason.isEmpty() ? null : reason));
            }
        }
        return new MiddlewareExposeView(null, gatewayUnavailable(instance.getClusterId()), unauthenticated, out);
    }

    public MiddlewareExposeView update(String instanceId, String endpointKey, String mode, String tenantId) {
        PlatformMiddlewareInstance instance = require(instanceId, tenantId);
        MiddlewareExposeCatalog.Result catalog = MiddlewareExposeCatalog.of(instance);
        if (catalog.unsupportedReason() != null) {
            throw error(catalog.unsupportedReason());
        }
        MiddlewareExposeCatalog.Endpoint ep = catalog.endpoints().stream()
                .filter(e -> e.key().equals(endpointKey)).findFirst()
                .orElseThrow(() -> error("该实例没有可暴露的端点 " + endpointKey));
        String target = mode == null ? NONE : mode.trim().toUpperCase();
        PlatformMiddlewareExpose current = byKey(instance.getId()).get(ep.key());
        boolean wasGateway = current != null && PlatformMiddlewareExpose.GATEWAY.equals(current.getMode());
        switch (target) {
            case PlatformMiddlewareExpose.GATEWAY -> toGateway(instance, ep);
            case PlatformMiddlewareExpose.NODEPORT -> {
                toNodePort(instance, ep, current);
                if (wasGateway) {
                    releaseGateway(instance, ep.key());
                }
            }
            case NONE -> {
                client.exposeApply(request(instance, ep, NONE));
                rows.deleteByKey(instance.getId(), ep.key());
                if (wasGateway) {
                    releaseGateway(instance, ep.key());
                }
            }
            default -> throw error("不支持的暴露方式：" + mode + "（可选 不暴露 / 网关 / NodePort）");
        }
        return list(instanceId, tenantId);
    }

    /** 删实例时清理：集群对象、端口、台账行。每步失败只记日志，不挡删除 */
    public void cleanup(PlatformMiddlewareInstance instance) {
        try {
            MiddlewareExposeRequestDTO r = new MiddlewareExposeRequestDTO();
            r.setClusterId(instance.getClusterId());
            r.setNamespace(instance.getNamespace());
            r.setTenantId(instance.getTenantId());
            r.setInstanceName(instance.getName());
            client.exposeDeleteAll(r);
        } catch (RuntimeException e) {
            log.warn("删除实例 {} 的对外暴露对象失败：{}", instance.getId(), e.getMessage());
        }
        try {
            List<PlatformIngressPort> held = ports.activeOfApp(instance.getId());
            if (held != null && !held.isEmpty()) {
                listeners.syncReleasing(instance.getClusterId(), instance.getNamespace(),
                        p -> instance.getId().equals(p.getAppId()), () -> ports.releaseApp(instance.getId()));
            }
        } catch (RuntimeException e) {
            // 实例已删，端口不能挂在不存在的实例名下：照样释放（残留 listener 下次同命名空间同步时摘掉）
            ports.releaseApp(instance.getId());
            log.warn("删除实例 {} 后同步 ListenerSet 失败：{}", instance.getId(), e.getMessage());
        }
        rows.deleteByInstance(instance.getId());
    }

    private void toGateway(PlatformMiddlewareInstance instance, MiddlewareExposeCatalog.Endpoint ep) {
        guard.require(instance.getClusterId(), AppServiceFeatures.ROUTES, "开启网关暴露");
        IngressPortService.PortRange pool = ports.currentPool(instance.getClusterId());
        if (pool == null) {
            throw error("集群未启用应用端口池（Istio 组件编辑里配置）");
        }
        boolean created = ports.lock(instance.getClusterId(), instance.getNamespace(),
                PlatformIngressPort.OWNER_MIDDLEWARE, instance.getId(), ep.key(), ep.protocol(), pool);
        Integer port = ports.portOf(instance.getId(), ep.key());
        try {
            if (port == null) {
                throw error("网关端口分配失败，请重试");
            }
            listeners.sync(instance.getClusterId(), instance.getNamespace());
            MiddlewareExposeRequestDTO r = request(instance, ep, PlatformMiddlewareExpose.GATEWAY);
            r.setProtocol(ep.protocol());
            r.setListenerSetName(IngressListenerSync.LISTENER_SET);
            r.setSectionName(IngressListenerSync.sectionName(port));
            client.exposeApply(r);
        } catch (RuntimeException e) {
            if (created) {
                ports.release(instance.getId(), ep.key());
            }
            throw e;
        }
        save(instance, ep, PlatformMiddlewareExpose.GATEWAY, null);
    }

    private void toNodePort(PlatformMiddlewareInstance instance, MiddlewareExposeCatalog.Endpoint ep,
                            PlatformMiddlewareExpose current) {
        Integer port = current != null && PlatformMiddlewareExpose.NODEPORT.equals(current.getMode())
                ? current.getNodePort() : null;
        if (port == null) {
            port = nodePorts.allocate(instance.getClusterId(), instance.getNamespace(),
                    objectName(instance, ep), instance.getId(), ep.key());
        }
        MiddlewareExposeRequestDTO r = request(instance, ep, PlatformMiddlewareExpose.NODEPORT);
        r.setNodePort(port);
        client.exposeApply(r);
        save(instance, ep, PlatformMiddlewareExpose.NODEPORT, port);
    }

    private void releaseGateway(PlatformMiddlewareInstance instance, String key) {
        listeners.syncReleasing(instance.getClusterId(), instance.getNamespace(),
                p -> instance.getId().equals(p.getAppId()) && key.equals(p.getServiceKey()),
                () -> ports.release(instance.getId(), key));
    }

    private void save(PlatformMiddlewareInstance instance, MiddlewareExposeCatalog.Endpoint ep, String mode,
                      Integer nodePort) {
        Date now = new Date();
        PlatformMiddlewareExpose row = new PlatformMiddlewareExpose();
        row.setInstanceId(instance.getId());
        row.setEndpointKey(ep.key());
        row.setMode(mode);
        row.setNodePort(nodePort);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        rows.upsert(row);
    }

    private static MiddlewareExposeRequestDTO request(PlatformMiddlewareInstance instance,
                                                      MiddlewareExposeCatalog.Endpoint ep, String mode) {
        MiddlewareExposeRequestDTO r = new MiddlewareExposeRequestDTO();
        r.setClusterId(instance.getClusterId());
        r.setNamespace(instance.getNamespace());
        r.setTenantId(instance.getTenantId());
        r.setInstanceName(instance.getName());
        r.setEndpointKey(ep.key());
        r.setMode(mode);
        r.setServiceName(ep.service());
        r.setServicePort(ep.port());
        return r;
    }

    /** 与 k8s-server MiddlewareExposeObjects.objectName 同规则：NodePort 分配时排除自己的 Service */
    static String objectName(PlatformMiddlewareInstance instance, MiddlewareExposeCatalog.Endpoint ep) {
        return instance.getName() + "-expose-" + ep.key();
    }

    private Map<String, PlatformMiddlewareExpose> byKey(String instanceId) {
        Map<String, PlatformMiddlewareExpose> out = new HashMap<>();
        List<PlatformMiddlewareExpose> list = rows.selectByInstance(instanceId);
        for (PlatformMiddlewareExpose r : list == null ? List.<PlatformMiddlewareExpose>of() : list) {
            out.put(r.getEndpointKey(), r);
        }
        return out;
    }

    private Map<String, IngressListenerSetStatusDTO.Listener> listenerStatus(PlatformMiddlewareInstance instance) {
        Map<String, IngressListenerSetStatusDTO.Listener> out = new HashMap<>();
        try {
            var st = appClient.ingressListenerSetStatus(instance.getClusterId(), instance.getNamespace(),
                    IngressListenerSync.LISTENER_SET);
            if (st != null && st.getListeners() != null) {
                st.getListeners().forEach(l -> out.put(l.getName(), l));
            }
        } catch (RuntimeException e) {
            log.debug("读取实例 {} 的 ListenerSet 状态失败：{}", instance.getId(), e.getMessage());
        }
        return out;
    }

    /** 网关方式当前不可用的原因；可用返回 null */
    private String gatewayUnavailable(String clusterId) {
        try {
            guard.require(clusterId, AppServiceFeatures.ROUTES, "开启网关暴露");
            if (ports.currentPool(clusterId) == null) {
                return "集群未启用应用端口池（Istio 组件编辑里配置）";
            }
            return null;
        } catch (CloudPlatformException e) {
            return e.getMessage();
        }
    }

    private PlatformMiddlewareInstance require(String instanceId, String tenantId) {
        PlatformMiddlewareInstance instance = instanceId == null ? null : instances.selectByPrimaryKey(instanceId);
        if (instance == null || instance.getDeletedAt() != null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST);
        }
        if (tenantId != null && !tenantId.equals(instance.getTenantId())) {
            throw error("无权操作该实例");
        }
        return instance;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static CloudPlatformException error(String message) {
        return new CloudPlatformException(EnumResponseType.ERROR, message);
    }
}
