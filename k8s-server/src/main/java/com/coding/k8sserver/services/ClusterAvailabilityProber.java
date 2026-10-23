package com.coding.k8sserver.services;

import com.coding.k8score.factory.KubernetesClientFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 集群探活定时任务：周期性调用 {@link KubernetesClientFactory#probeAllClusters()}，
 * 探活结果（CONNECTED / DISCONNECTED）由工厂写回 {@code k8s_cluster.status}。
 *
 * <p>启动时 {@code @PostConstruct init()} 已经探过一次，所以首轮延迟也取一个周期，不重复探。
 * 周期由 {@code platform.cluster.probe.interval} 控制（默认 2 分钟）。
 */
@Component
@RequiredArgsConstructor
public class ClusterAvailabilityProber {

    private final KubernetesClientFactory clientFactory;

    @Scheduled(initialDelayString = "${platform.cluster.probe.interval:2m}",
            fixedDelayString = "${platform.cluster.probe.interval:2m}")
    public void probeAll() {
        clientFactory.probeAllClusters();
    }
}
