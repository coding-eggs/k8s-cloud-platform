package com.coding.platformapi.services;

import com.coding.data.mapper.system.PlatformApplicationOperationMapper;
import com.coding.data.models.system.PlatformApplicationOperation;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 部署操作的推进器。
 *
 * <p>一次只推进一条最早的活动操作：并发推进会在 PRUNE 的差集上互相打架（一条按旧台账删、
 * 一条按新台账写）。platform-api 假定单副本；多副本时要改成 {@code select ... for update skip locked}。
 */
@Component
public class ApplicationOperationWorker {

    private final ApplicationDeployService deploy;
    private final PlatformApplicationOperationMapper operations;

    public ApplicationOperationWorker(ApplicationDeployService deploy,
                                      PlatformApplicationOperationMapper operations) {
        this.deploy = deploy;
        this.operations = operations;
    }

    @Scheduled(fixedDelayString = "${platform.application.worker-delay-ms:3000}")
    public void tick() {
        PlatformApplicationOperation op = operations.selectFirstActive();
        if (op != null) {
            deploy.advance(op);
        }
    }
}
