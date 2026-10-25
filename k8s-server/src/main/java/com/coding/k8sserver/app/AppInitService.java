package com.coding.k8sserver.app;

import com.coding.common.models.app.AppInitRequestDTO;
import com.coding.common.models.app.AppInitResultDTO;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8sserver.helm.HelmResult;
import com.coding.k8sserver.helm.HelmRunner;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 跑一次中间件初始化：ConfigMap（脚本正文）+ Job（执行），等它完成并取日志。
 *
 * <p>不使用 Helm release：初始化是一次性动作，跑完就该被 TTL 清掉；用 release 会在集群里留一堆历史。
 */
@Service
public class AppInitService {

    private static final Duration JOB_TIMEOUT = Duration.ofMinutes(10);

    private final HelmRunner helm;
    private final KubernetesClientFactory clients;

    public AppInitService(HelmRunner helm, KubernetesClientFactory clients) {
        this.helm = helm;
        this.clients = clients;
    }

    public AppInitResultDTO run(AppInitRequestDTO req) {
        String kubeconfig = clients.adminKubeconfig(req.getClusterId());

        HelmResult configMap = helm.applyServerSide(kubeconfig, AppInitScripts.configMapFor(req));
        if (!configMap.ok()) {
            return result(false, "初始化配置创建失败：" + configMap.output(), "");
        }
        HelmResult job = helm.applyServerSide(kubeconfig, AppInitScripts.jobFor(req));
        if (!job.ok()) {
            return result(false, "初始化任务创建失败：" + job.output(), "");
        }

        HelmResult waited = helm.waitJob(kubeconfig, req.getJobName(), req.getNamespace(), JOB_TIMEOUT);
        String logs = helm.jobLogs(kubeconfig, req.getJobName(), req.getNamespace());
        return waited.ok()
                ? result(true, "初始化完成", logs)
                : result(false, "初始化失败：" + waited.output(), logs);
    }

    private static AppInitResultDTO result(boolean ok, String message, String logs) {
        AppInitResultDTO dto = new AppInitResultDTO();
        dto.setOk(ok);
        dto.setMessage(message);
        dto.setLogTail(logs);
        return dto;
    }
}
