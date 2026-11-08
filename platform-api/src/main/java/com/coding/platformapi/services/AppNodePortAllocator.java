package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.app.NodePortUseDTO;
import com.coding.data.mapper.system.PlatformAppServiceMapper;
import com.coding.data.models.system.PlatformAppService;
import com.coding.data.models.system.PlatformApplication;
import com.coding.platformapi.app.AppObjectNames;
import com.coding.platformapi.app.AppServiceDesired;
import com.coding.platformapi.app.AppSnapshot;
import com.coding.platformapi.k8s.K8sAppClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * NodePort 暴露的端口由平台定，不交给 K8s 随机分配（spec §5.3）：本集群 nodePort 范围放开到了 1-65535，
 * 随机会落到低端口、撞节点服务端口或网关端口池段。
 *
 * <ul>
 *   <li>没填：保存时在 30000-32767 里挑一个空闲的写进期望配置，之后发布 / 回滚都不变；</li>
 *   <li>填了：查冲突——别的 Service 已经占着、或别的应用服务已经保存了同一个端口，就拒绝。</li>
 * </ul>
 * 「空闲」= 集群里所有 Service 已占用的 nodePort ∪ 同集群其他应用服务已保存（可能还没发布）的 nodePort
 * ∪ 中间件端点已分配的 nodePort，
 * 自己线上 Service 和自己台账行占着的不算。并发两次保存挑到同一个的极小概率，由发布时 K8s 报端口已占用兜底。
 * 读不到集群就让保存失败，不猜。
 */
@Slf4j
@Service
public class AppNodePortAllocator {

    static final int MIN = 30000;
    static final int MAX = 32767;

    private final K8sAppClient client;
    private final PlatformAppServiceMapper services;
    private final com.coding.data.mapper.system.PlatformMiddlewareExposeMapper middleware;
    private final Random random;

    @Autowired
    public AppNodePortAllocator(K8sAppClient client, PlatformAppServiceMapper services,
                                com.coding.data.mapper.system.PlatformMiddlewareExposeMapper middleware) {
        this(client, services, middleware, new SecureRandom());
    }

    AppNodePortAllocator(K8sAppClient client, PlatformAppServiceMapper services,
                         com.coding.data.mapper.system.PlatformMiddlewareExposeMapper middleware, Random random) {
        this.client = client;
        this.services = services;
        this.middleware = middleware;
        this.random = random;
    }

    /**
     * 中间件端点的 NodePort：只自动分配（不提供手填）。{@code selfService} 是该端点自己的 NodePort Service 名，
     * {@code ownerId / key} 是实例 id / 端点 key —— 自己占着的不算冲突。
     */
    public int allocate(String clusterId, String namespace, String selfService, String ownerId, String key) {
        return pick(usedByOthers(clusterId, namespace, selfService, ownerId, key), random);
    }

    /** @return 原 desired（非 NodePort 方式）或补 / 校验过 nodePort 的新 desired */
    public Map<String, Object> assign(PlatformApplication app, String key, Map<String, Object> desired) {
        if (!AppServiceDesired.EXPOSE_NODEPORT.equals(desired.get("expose"))) {
            return desired;
        }
        Set<Integer> used = usedByOthers(app.getClusterId(), app.getNamespace(),
                AppObjectNames.objectName(app.getSlug(), key), app.getId(), key);
        Integer wanted = desired.get("nodePort") instanceof Number n ? n.intValue() : null;
        if (wanted != null) {
            if (used.contains(wanted)) {
                throw error("NodePort 端口 " + wanted + " 已被集群里其他服务占用，请换一个或留空由平台分配");
            }
            return desired;
        }
        Map<String, Object> out = new LinkedHashMap<>(desired);
        out.put("nodePort", pick(used, random));
        return out;
    }

    static int pick(Set<Integer> used, Random random) {
        List<Integer> free = new ArrayList<>();
        for (int p = MIN; p <= MAX; p++) {
            if (!used.contains(p)) {
                free.add(p);
            }
        }
        if (free.isEmpty()) {
            throw error("NodePort 端口 " + MIN + "-" + MAX + " 已全部被占用");
        }
        return free.get(random.nextInt(free.size()));
    }

    /**
     * 集群全部 Service 的 nodePort ∪ 应用服务已保存的 ∪ 中间件端点已分配的；
     * 自己的 Service（namespace/selfService）和自己的台账行（ownerId/key）不算。
     * 应用 id（app_）与实例 id（mwi_）前缀不同，一组 (ownerId, key) 不会同时命中两张表。
     */
    private Set<Integer> usedByOthers(String clusterId, String namespace, String selfService, String ownerId,
                                      String key) {
        Set<Integer> used = new HashSet<>();
        List<NodePortUseDTO> live;
        try {
            live = client.usedNodePorts(clusterId);
        } catch (RuntimeException e) {
            log.warn("读取集群 {} 已占用的 nodePort 失败：{}", clusterId, e.getMessage());
            throw error("读取集群已占用的 NodePort 端口失败，请稍后重试");
        }
        for (NodePortUseDTO u : live) {
            boolean mine = namespace.equals(u.getNamespace()) && selfService.equals(u.getName());
            if (!mine && u.getNodePort() != null) {
                used.add(u.getNodePort());
            }
        }
        for (PlatformAppService s : services.selectNodePortServicesInCluster(clusterId)) {
            if (ownerId.equals(s.getAppId()) && key.equals(s.getServiceKey())) {
                continue;
            }
            Integer p = AppServiceDesired.nodePort(AppSnapshot.parseMap(s.getDesiredJson()));
            if (p != null) {
                used.add(p);
            }
        }
        for (var m : middleware.selectNodePortsInCluster(clusterId)) {
            if (ownerId.equals(m.getInstanceId()) && key.equals(m.getEndpointKey())) {
                continue;
            }
            if (m.getNodePort() != null) {
                used.add(m.getNodePort());
            }
        }
        return used;
    }

    private static CloudPlatformException error(String message) {
        return new CloudPlatformException(EnumResponseType.ERROR, message);
    }
}
