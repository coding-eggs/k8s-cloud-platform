package com.coding.platformapi.services;

import com.coding.data.mapper.system.PlatformApplicationOperationMapper;
import com.coding.data.models.system.PlatformApplicationOperation;
import com.coding.platformapi.k8s.K8sServerGateway;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 部署操作的推进器。
 *
 * <p><b>一次只推进一条</b>最早的活动操作：并发推进会与 PRUNE 的差集打架（一条按旧台账删、
 * 一条按新台账写）。代价是队头阻塞 —— 一条卡在 HEALTH 的部署会让别的应用等着；
 * v1 接受，节点就绪通常远快于超时。
 *
 * <p><b>凭据</b>：worker 线程没有请求上下文，k8s-server 对匿名调用一律拒绝。发起部署时把当次请求的
 * Authorization 存进 {@link ApplicationDeployCredentials}（内存），这里用 {@code runAs} 带过去；
 * 重启后凭据没了 → 明确失败，而不是让操作永远 RUNNING。
 */
@Component
public class ApplicationOperationWorker {

    private final ApplicationDeployService deploy;
    private final PlatformApplicationOperationMapper operations;
    private final ApplicationDeployCredentials credentials;

    public ApplicationOperationWorker(ApplicationDeployService deploy,
                                      PlatformApplicationOperationMapper operations,
                                      ApplicationDeployCredentials credentials) {
        this.deploy = deploy;
        this.operations = operations;
        this.credentials = credentials;
    }

    @Scheduled(fixedDelayString = "${platform.application.worker-delay-ms:3000}")
    public void tick() {
        PlatformApplicationOperation op = operations.selectFirstActive();
        if (op == null) {
            return;
        }
        String authorization = credentials.get(op.getId());
        if (authorization == null) {
            deploy.failWithoutCredentials(op);
            return;
        }
        K8sServerGateway.runAs(authorization, () -> deploy.advance(op));
    }
}
