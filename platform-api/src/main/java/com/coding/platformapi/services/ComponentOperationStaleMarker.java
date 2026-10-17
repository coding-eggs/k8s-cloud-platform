package com.coding.platformapi.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时把"上次进程中断留下的 RUNNING"标成 UNKNOWN（S3.6）。
 *
 * <p>不这么做的话，这些记录会永远停在 RUNNING：并发拒绝会把该组件锁死，
 * 而用户从页面上看不出到底装没装。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComponentOperationStaleMarker implements ApplicationRunner {

    private final ComponentInstallService service;

    @Override
    public void run(ApplicationArguments args) {
        int marked = service.markStaleRunning();
        if (marked > 0) {
            log.warn("标记了 {} 条中断的组件操作记录为 UNKNOWN", marked);
        }
    }
}
