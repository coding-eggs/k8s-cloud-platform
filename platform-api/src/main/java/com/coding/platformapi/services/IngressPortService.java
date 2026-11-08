package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.system.PlatformComponentOperationMapper;
import com.coding.data.mapper.system.PlatformIngressPortMapper;
import com.coding.data.models.system.PlatformIngressPort;
import com.coding.platformapi.components.IngressPortPool;
import com.coding.platformapi.k8s.K8sAppClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 应用网关端口池的分配（spec §5.2）。
 *
 * <ul>
 *   <li>只分配<b>网关 Service 上已声明</b>的端口：用了未声明的端口，整个 Gateway 会变成
 *       {@code Programmed=False / AddressNotUsable}（2026-11-08 实测）；</li>
 *   <li>一个服务一个端口，分配后锁定：重复保存返回原端口，换协议只改协议；</li>
 *   <li>释放后冷却 10 分钟才复用，避免客户端还连着旧服务；</li>
 *   <li>并发靠 {@code (cluster_id, port)} 唯一键兜底：撞了换下一个端口，最多 3 次。</li>
 * </ul>
 */
@Service
public class IngressPortService {

    public static final String GATEWAY_NAMESPACE = "istio-system";
    public static final String GATEWAY_SERVICE = "external-gateway";
    static final Duration COOLDOWN = Duration.ofMinutes(10);
    private static final int MAX_ATTEMPTS = 3;
    /** 一个 ListenerSet 最多 64 条 listener（Gateway API 上限） */
    public static final int MAX_LISTENERS_PER_NAMESPACE = 64;

    /** 端口区间（闭区间） */
    public record PortRange(int from, int to) {
        public boolean contains(int port) {
            return port >= from && port <= to;
        }

        public int size() {
            return to - from + 1;
        }
    }

    private final PlatformIngressPortMapper mapper;
    private final K8sAppClient components;
    private final PlatformComponentOperationMapper operations;
    private final Clock clock;

    @Autowired
    public IngressPortService(PlatformIngressPortMapper mapper, K8sAppClient components,
                              PlatformComponentOperationMapper operations) {
        this(mapper, components, operations, Clock.systemDefaultZone());
    }

    IngressPortService(PlatformIngressPortMapper mapper, K8sAppClient components,
                       PlatformComponentOperationMapper operations, Clock clock) {
        this.mapper = mapper;
        this.components = components;
        this.operations = operations;
        this.clock = clock;
    }

    /**
     * 集群当前生效的应用端口池（istio 组件「上次成功」的 values 快照）；没装 / 停用返回 null。
     * 以成功记录为准：只有成功下发的范围才真的声明在网关 Service 上。
     */
    public PortRange currentPool(String clusterId) {
        // 组件变更进行中：网关 Service 的端口可能正在缩，这时分出去的端口可能落进被删的段（终审 I5）
        if (operations.countRunning(clusterId, "istio") > 0) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "Istio 组件正在变更，暂不能分配网关端口，请等组件操作完成后重试");
        }
        var last = operations.selectLatestSuccess(clusterId, "istio");
        return last == null ? null : IngressPortPool.fromSnapshot(last.getValuesSnapshot());
    }

    /** 发布 PRECHECK 用：确保有端口，协议以快照为准（已有端口换协议只改协议） */
    public int allocate(String clusterId, String namespace, String appId, String serviceKey,
                        String protocol, PortRange pool) {
        PlatformIngressPort mine = mapper.selectActiveByService(appId, serviceKey);
        if (mine != null) {
            if (!protocol.equals(mine.getProtocol())) {
                mapper.updateProtocol(mine.getId(), protocol, Date.from(clock.instant()));
            }
            return mine.getPort();
        }
        return claim(clusterId, namespace, appId, serviceKey, protocol, pool).port();
    }

    /**
     * 保存时用：锁定端口但<b>不改已有端口的协议</b> —— 已上线的 HTTP 路由还挂在 listener 上，
     * 保存时就改协议，任何一次同命名空间的同步都会让线上入口在发布前断掉（终审 I1）。
     *
     * @return true = 这次新分配的（保存失败时调用方要把它放掉）；false = 原本就有 / 并发请求先分到了
     */
    public boolean lock(String clusterId, String namespace, String appId, String serviceKey,
                        String protocol, PortRange pool) {
        if (mapper.selectActiveByService(appId, serviceKey) != null) {
            return false;
        }
        return claim(clusterId, namespace, appId, serviceKey, protocol, pool).created();
    }

    private record Claim(int port, boolean created) {
    }

    private Claim claim(String clusterId, String namespace, String appId, String serviceKey,
                        String protocol, PortRange pool) {
        Date now = Date.from(clock.instant());
        List<PlatformIngressPort> inNamespace = mapper.selectActiveByNamespace(clusterId, namespace);
        if (inNamespace != null && inNamespace.size() >= MAX_LISTENERS_PER_NAMESPACE) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "命名空间 " + namespace
                    + " 的网关端口已达上限 " + MAX_LISTENERS_PER_NAMESPACE + " 个（一个 ListenerSet 最多 "
                    + MAX_LISTENERS_PER_NAMESPACE + " 条 listener），请改用 NodePort 或把服务拆到别的命名空间");
        }
        Set<Integer> declared = new HashSet<>(components.servicePorts(clusterId, GATEWAY_NAMESPACE, GATEWAY_SERVICE));
        Map<Integer, PlatformIngressPort> byPort = new HashMap<>();
        for (PlatformIngressPort r : mapper.selectByCluster(clusterId)) {
            byPort.put(r.getPort(), r);
        }
        Instant cutoff = clock.instant().minus(COOLDOWN);
        Claim previous = reclaimPrevious(byPort, declared, pool, namespace, appId, serviceKey, protocol, now);
        if (previous != null) {
            return previous;
        }
        Set<Integer> tried = new HashSet<>();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Integer candidate = null;
            for (int p = pool.from(); p <= pool.to(); p++) {
                if (!declared.contains(p) || tried.contains(p)) {
                    continue;
                }
                PlatformIngressPort r = byPort.get(p);
                if (r == null || reusable(r, cutoff)) {
                    candidate = p;
                    break;
                }
            }
            if (candidate == null) {
                if (attempt == 0) {
                    throw new CloudPlatformException(EnumResponseType.ERROR,
                            "端口池已用完（" + pool.from() + "-" + pool.to() + "），请在 Istio 组件编辑里扩大应用端口池");
                }
                break;
            }
            tried.add(candidate);
            PlatformIngressPort existing = byPort.get(candidate);
            PlatformIngressPort rec = new PlatformIngressPort();
            rec.setClusterId(clusterId);
            rec.setPort(candidate);
            rec.setNamespace(namespace);
            rec.setAppId(appId);
            rec.setServiceKey(serviceKey);
            rec.setProtocol(protocol);
            rec.setCreatedAt(now);
            rec.setUpdatedAt(now);
            try {
                if (existing == null) {
                    mapper.insert(rec);
                    return new Claim(candidate, true);
                }
                rec.setId(existing.getId());
                rec.setCooldownCutoff(Date.from(cutoff));
                if (mapper.reactivate(rec) > 0) {
                    return new Claim(candidate, true);
                }
            } catch (DuplicateKeyException e) {
                // 两种撞法：同一服务的并发请求先分到了（(app_id, service_key) ACTIVE 唯一）→ 用它的；
                // 别的服务抢了这个端口（(cluster_id, port) 唯一）→ 换下一个
                PlatformIngressPort winner = mapper.selectActiveByService(appId, serviceKey);
                if (winner != null) {
                    return new Claim(winner.getPort(), false);
                }
            }
        }
        throw new CloudPlatformException(EnumResponseType.ERROR, "端口分配冲突，请稍后重试");
    }

    /**
     * 下线再上线 / 改回网关方式：这个服务上次用的端口若还没被别人拿走（RELEASED 行的归属仍是它），
     * 直接拿回、不受冷却限制 —— 冷却是防「别的服务」接到旧客户端，自己拿回没有这个问题。地址因此不变。
     */
    private Claim reclaimPrevious(Map<Integer, PlatformIngressPort> byPort, Set<Integer> declared, PortRange pool,
                                  String namespace, String appId, String serviceKey, String protocol, Date now) {
        PlatformIngressPort last = byPort.values().stream()
                .filter(r -> PlatformIngressPort.RELEASED.equals(r.getStatus()))
                .filter(r -> appId.equals(r.getAppId()) && serviceKey.equals(r.getServiceKey()))
                .filter(r -> pool.contains(r.getPort()) && declared.contains(r.getPort()))
                .max(java.util.Comparator.comparing(r -> r.getReleasedAt() == null ? new Date(0) : r.getReleasedAt()))
                .orElse(null);
        if (last == null) {
            return null;
        }
        PlatformIngressPort rec = new PlatformIngressPort();
        rec.setId(last.getId());
        rec.setNamespace(namespace);
        rec.setAppId(appId);
        rec.setServiceKey(serviceKey);
        rec.setProtocol(protocol);
        rec.setUpdatedAt(now);
        // released_at < now+1s 恒成立：跳过冷却，但仍要求行还是 RELEASED（并发被抢走则走常规分配）
        rec.setCooldownCutoff(Date.from(clock.instant().plusSeconds(1)));
        try {
            return mapper.reactivate(rec) > 0 ? new Claim(last.getPort(), true) : null;
        } catch (DuplicateKeyException e) {
            PlatformIngressPort winner = mapper.selectActiveByService(appId, serviceKey);
            return winner == null ? null : new Claim(winner.getPort(), false);
        }
    }

    private static boolean reusable(PlatformIngressPort r, Instant cutoff) {
        return PlatformIngressPort.RELEASED.equals(r.getStatus()) && r.getReleasedAt() != null
                && r.getReleasedAt().toInstant().isBefore(cutoff);
    }

    /** 服务当前锁定的端口；没有返回 null */
    public Integer portOf(String appId, String serviceKey) {
        PlatformIngressPort r = mapper.selectActiveByService(appId, serviceKey);
        return r == null ? null : r.getPort();
    }

    public void release(String appId, String serviceKey) {
        mapper.releaseService(appId, serviceKey, Date.from(clock.instant()));
    }

    public void releaseApp(String appId) {
        mapper.releaseApp(appId, Date.from(clock.instant()));
    }

    public List<PlatformIngressPort> activeInNamespace(String clusterId, String namespace) {
        return mapper.selectActiveByNamespace(clusterId, namespace);
    }

    public List<PlatformIngressPort> activeOfApp(String appId) {
        return mapper.selectActiveByApp(appId);
    }

    /** 落在 keep 之外、仍在使用的端口（缩小端口池时拦截用） */
    public List<PlatformIngressPort> activeOutside(String clusterId, PortRange keep) {
        return mapper.selectByCluster(clusterId).stream()
                .filter(r -> PlatformIngressPort.ACTIVE.equals(r.getStatus()))
                .filter(r -> keep == null || !keep.contains(r.getPort()))
                .toList();
    }
}
