package com.coding.platformapi.services;

import com.coding.data.models.system.PlatformAppExecution;

/** 发布前校验（引用可解析、中间件已 READY 等）。不通过时抛异常，执行进入 FAILED。 */
public interface AppReleasePrecheck {

    void check(PlatformAppExecution execution);

    /** 下线完成（对象已清掉）之后：释放应用占用的网关端口等。失败只记日志，不改变下线结论。 */
    default void afterOffline(PlatformAppExecution execution) {
    }
}
